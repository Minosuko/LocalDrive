package com.minosuko.clouddrive

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.ContentProviderOperation
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.media.MediaScannerConnection
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Telephony
import android.system.Os
import android.telephony.SubscriptionManager
import android.util.Base64
import android.util.JsonWriter
import android.util.JsonReader
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.IOException
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.security.DigestOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MediaSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val state = context.getSharedPreferences("media_sync_state", Context.MODE_PRIVATE)
    private val lastProgressUpdate = AtomicLong(0)
    private var notificationChannelReady = false

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val automatic = inputData.getBoolean(KEY_AUTOMATIC, false)
        val generation = inputData.getString(KEY_GENERATION).orEmpty()
        val lockChannel = RandomAccessFile(applicationContext.filesDir.resolve("media-sync.lock"), "rw").channel
        val syncLock = try {
            lockChannel.tryLock()
        } catch (_: OverlappingFileLockException) {
            null
        }
        if (syncLock == null) {
            lockChannel.close()
            return@withContext Result.retry()
        }

        var scheduleFollowingRun = false
        val temporaryExports = mutableListOf<File>()
        try {
            try {
            val categories = inputData.getString(KEY_CATEGORIES)?.split(',')
                ?.mapNotNullTo(linkedSetOf()) { name -> SyncCategory.entries.firstOrNull { it.name == name } }
                ?: AppSettings.syncCategories(applicationContext)
            val direction = inputData.getString(KEY_DIRECTION)
                ?.let { name -> SyncDirection.entries.firstOrNull { it.name == name } }
                ?: AppSettings.syncDirection(applicationContext)
            if (automatic && direction == SyncDirection.CloudToDevice) {
                val message = "Automatic restore is disabled; use Sync to device"
                AppSettings.saveLastSyncStatus(applicationContext, message, direction)
                return@withContext Result.success(Data.Builder().putString(KEY_MESSAGE, message).putString(KEY_DIRECTION, direction.name).build())
            }
            if (categories.isEmpty()) {
                val message = "No sync categories selected"
                AppSettings.saveLastSyncStatus(applicationContext, message, direction)
                scheduleFollowingRun = true
                return@withContext Result.success(
                    Data.Builder().putString(KEY_MESSAGE, message).putString(KEY_DIRECTION, direction.name).build(),
                )
            }
            val drives = AppSettings.drives(applicationContext)
            if (drives.isEmpty()) return@withContext failure("Add a CloudDrive from Files", automatic)
            val signedInDrives = drives.filter { AccountStore.hasSession(applicationContext, it.id) }
            if (signedInDrives.isEmpty()) return@withContext failure("Sign in to a CloudDrive before syncing", automatic)
            if (!isOnWifi()) {
                scheduleFollowingRun = false
                return@withContext Result.retry()
            }

            setForeground(foregroundInfo("Preparing sync", 0, 0))
            val device = syncDeviceFolder(applicationContext)
            val preferredId = if (inputData.keyValueMap.containsKey(KEY_DRIVE_ID)) {
                inputData.getString(KEY_DRIVE_ID)?.ifEmpty { null }
            } else {
                AppSettings.syncDriveId(applicationContext)
            }
            val preferred = drives.firstOrNull { it.id == preferredId }
            val pinnedManualDrive = inputData.keyValueMap.containsKey(KEY_DRIVE_ID) &&
                !inputData.getString(KEY_DRIVE_ID).isNullOrEmpty()
            if (pinnedManualDrive && preferred == null) {
                return@withContext failure("The selected CloudDrive was disconnected", automatic)
            }
            if (preferred != null && preferred !in signedInDrives) {
                return@withContext failure("Sign in to ${preferred.name} before syncing", automatic)
            }
            val candidates = if (preferred != null) listOf(preferred) else signedInDrives
            var selectedDrive: DriveProfile? = null
            var syncClient: DavClient? = null
            var restoreDevice = device
            var folderError = "Server unavailable"
            for (drive in candidates) {
                val candidate = davClient(applicationContext, drive)
                try {
                    if (direction == SyncDirection.DeviceToCloud) {
                        candidate.storageStats(force = true)
                    } else {
                        val configuredDevice = inputData.getString(KEY_RESTORE_DEVICE)?.trim()
                            ?: AppSettings.restoreDevice(applicationContext).trim()
                        require(!configuredDevice.contains('/') && !configuredDevice.contains('\\')) { "Invalid backup device folder" }
                        val source = if (configuredDevice.isNotEmpty()) {
                            if (!candidate.exists(listOf("Sync", configuredDevice, BACKUP_COMPLETE_MARKER))) {
                                error("The selected device has no complete backup")
                            }
                            BrowserEntry(
                                source = BrowserSource.CloudDrive,
                                name = configuredDevice,
                                isDirectory = true,
                                size = 0,
                                mimeType = "httpd/unix-directory",
                                cloudSegments = listOf("Sync", configuredDevice),
                            )
                        } else {
                            val backups = candidate.listCloud(listOf("Sync"), force = true).filter { it.isDirectory }
                            val complete = backups.mapNotNull { backup ->
                                val marker = syncRunCatching {
                                    candidate.listCloud(backup.cloudSegments, force = true)
                                        .firstOrNull { it.name == BACKUP_COMPLETE_MARKER && !it.isDirectory }
                                }.getOrNull() ?: return@mapNotNull null
                                backup to marker.modified
                            }
                            complete.firstOrNull { it.first.name == device }?.first
                                ?: complete.maxByOrNull { it.second }?.first
                        } ?: error("No matching device backup")
                        restoreDevice = source.name
                    }
                    selectedDrive = drive
                    syncClient = candidate
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    if (isStopped) throw CancellationException("Sync cancelled", error)
                    folderError = "${drive.name}: ${error.message ?: "server error"}"
                }
            }
            val drive = selectedDrive ?: return@withContext failure("Cannot connect to CloudDrive. $folderError", automatic)
            val client = requireNotNull(syncClient)
            val stateScope = "${drive.address}|${drive.id}"

            setForeground(foregroundInfo("Scanning selected data", 0, 0))
            val allowed = categories.filterTo(linkedSetOf()) { hasSyncCategoryPermission(applicationContext, it, direction) }
            val missing = categories - allowed
            if (direction == SyncDirection.CloudToDevice) {
                val outcome = restoreFromCloud(client, stateScope, drive.address, restoreDevice, allowed, missing)
                AppSettings.saveLastSyncStatus(applicationContext, outcome.summary, direction)
                val output = outcome.toData(direction)
                if (outcome.failed > 0) {
                    scheduleFollowingRun = false
                    return@withContext if (automatic) Result.retry() else Result.failure(output)
                }
                scheduleFollowingRun = true
                return@withContext Result.success(output)
            }
            val priorIndex = loadSyncIndex(client, device)
            val currentMarker = backupGeneration(client, device)
            val expectedPriorMarker = "$SYNC_INDEX_MARKER_PREFIX${priorIndex.revision}"
            if (priorIndex.present && currentMarker != null && currentMarker != expectedPriorMarker) {
                return@withContext failure("Sync index does not match the backup completion marker", automatic)
            }
            if (!priorIndex.present && currentMarker?.startsWith(SYNC_INDEX_MARKER_PREFIX) == true) {
                return@withContext failure("Backup completion marker refers to a missing sync index", automatic)
            }
            val recoveringMissingMarker = priorIndex.present && currentMarker == null
            val priorIndexTrustedForDeletion = priorIndex.present &&
                (currentMarker == null || currentMarker == expectedPriorMarker)
            val preparationErrors = mutableListOf<String>()
            val mountedMediaVolumesBefore = if (allowed.any { it in MEDIA_CATEGORIES }) {
                MediaStore.getExternalVolumeNames(applicationContext)
            } else {
                emptySet()
            }
            val mediaVolumeTokensBefore = mountedMediaVolumesBefore.associateWith(::mediaVolumeToken)
            val mediaDeferred = async { queryMedia(allowed, mountedMediaVolumesBefore) }
            val downloadsDeferred = async {
                if (SyncCategory.Downloads in allowed) queryDownloads() else DownloadSnapshot(emptyList(), complete = false)
            }
            val exportsDeferred = async {
                allowed.filter { it in EXPORT_CATEGORIES }.mapNotNull { category ->
                syncRunCatching { createExport(category).also { temporaryExports += it.file } }
                    .onFailure { preparationErrors += "${category.label}: ${it.message ?: "could not read data"}" }
                    .getOrNull()
                }
            }
            val mediaSnapshot = mediaDeferred.await()
            val media = mediaSnapshot.items
            val mountedMediaVolumesAfter = if (allowed.any { it in MEDIA_CATEGORIES }) {
                MediaStore.getExternalVolumeNames(applicationContext)
            } else {
                emptySet()
            }
            val mediaVolumeTokensAfter = mountedMediaVolumesAfter.associateWith(::mediaVolumeToken)
            val authoritativeMediaVolumes = mountedMediaVolumesBefore.intersect(mountedMediaVolumesAfter).filterTo(linkedSetOf()) {
                mediaVolumeTokensBefore[it] == mediaVolumeTokensAfter[it]
            }
            val downloadSnapshot = downloadsDeferred.await()
            val downloads = downloadSnapshot.items
            val exports = exportsDeferred.await()
            val authoritativeCategories = buildSet {
                allowed.filterTo(this) { category ->
                    when {
                        category in MEDIA_CATEGORIES -> hasCompleteMediaAccess(category) &&
                            category in mediaSnapshot.completeCategories
                        category == SyncCategory.Downloads -> downloadSnapshot.complete
                        category in EXPORT_CATEGORIES -> exports.any { it.category == category }
                        else -> true
                    }
                }
            }
            val originalAccess = Build.VERSION.SDK_INT >= 29 && hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
            val preparedUploads = prepareUploads(
                media = media,
                downloads = downloads,
                exports = exports,
                priorIndex = priorIndex,
                stateScope = stateScope,
                device = device,
                originalAccess = originalAccess,
            )
            val remotePlan = planRemoteUploads(client, preparedUploads, priorIndex, device)
            val uploads = remotePlan.uploads
            val remoteFiles = remotePlan.remoteFiles
            val completionMarker = listOf("Sync", device, BACKUP_COMPLETE_MARKER)
            val currentByKey = uploads.associateBy(PreparedUpload::localKey)
            val currentPaths = uploads.mapTo(hashSetOf(), PreparedUpload::relativeRemoteSegments)
            require(currentByKey.size == uploads.size) { "Local sync data contains duplicate identities" }
            require(currentPaths.size == uploads.size) { "Local sync data contains duplicate cloud paths" }
            fun canReplaceIndexedEntry(indexed: SyncIndexEntry): Boolean = priorIndexTrustedForDeletion &&
                indexed.category in authoritativeCategories &&
                (indexed.category !in MEDIA_CATEGORIES || indexed.sourceVolume in authoritativeMediaVolumes)
            val deletions = if (priorIndex.present) priorIndex.entries.values.filter { indexed ->
                canReplaceIndexedEntry(indexed) &&
                    (currentByKey[indexed.localKey]?.relativeRemoteSegments != indexed.relativeRemoteSegments) &&
                    indexed.relativeRemoteSegments !in currentPaths
            } else {
                emptyList()
            }
            val nextEntries = priorIndex.entries.toMutableMap()
            nextEntries.values.removeAll(::canReplaceIndexedEntry)
            uploads.forEach { upload -> nextEntries[upload.localKey] = upload.toSyncIndexEntry() }
            requireValidSyncIndexEntries(nextEntries)
            val nextMigratedCategories = (priorIndex.migratedCategories - MEDIA_CATEGORIES) +
                authoritativeCategories.filterNot { it in MEDIA_CATEGORIES }
            val nextMigratedMediaVolumes = priorIndex.migratedMediaVolumes
                .mapValuesTo(linkedMapOf()) { it.value.toMutableSet() }
            authoritativeCategories.intersect(MEDIA_CATEGORIES).forEach { category ->
                nextMigratedMediaVolumes.getOrPut(category) { linkedSetOf() } += authoritativeMediaVolumes
            }
            val immutableMigratedMediaVolumes = nextMigratedMediaVolumes.mapValues { it.value.toSet() }
            val indexChanged = !priorIndex.present || nextEntries != priorIndex.entries ||
                nextMigratedCategories != priorIndex.migratedCategories ||
                immutableMigratedMediaVolumes != priorIndex.migratedMediaVolumes
            val markerInvalidated = AtomicBoolean(false)
            fun beforeRemoteMutation() {
                if (markerInvalidated.get()) return
                synchronized(markerInvalidated) {
                    if (markerInvalidated.get()) return@synchronized
                    try {
                        client.delete(completionMarker)
                    } catch (error: DavException) {
                        if (error.status != 404) throw error
                    }
                    markerInvalidated.set(true)
                }
            }
            val totalItems = uploads.size + deletions.size
            var done = 0
            var uploaded = 0
            var deleted = 0
            var skipped = 0
            var failed = preparationErrors.size + missing.size
            var remoteFailed = 0
            var lastError: String? = preparationErrors.lastOrNull()
            suspend fun recordAttempts(attempts: List<UploadAttempt>) {
                val editor = state.edit()
                var stateChanged = false
                attempts.forEach { attempt ->
                    when {
                        attempt.skipped -> {
                            skipped++
                            if (state.getString(attempt.stateKey, null) != attempt.signature) {
                                editor.putString(attempt.stateKey, attempt.signature)
                                stateChanged = true
                            }
                        }
                        attempt.error != null -> {
                            failed++
                            remoteFailed++
                            lastError = attempt.error
                        }
                        else -> {
                            editor.putString(attempt.stateKey, attempt.signature)
                            stateChanged = true
                            remoteFiles.getValue(attempt.category) += attempt.remoteKey
                            uploaded++
                        }
                    }
                }
                if (stateChanged) editor.apply()
                done += attempts.size
                publishProgress(done, totalItems, uploaded)
            }

            uploads.chunked(SYNC_STATE_CHECKPOINT_SIZE).forEach { checkpoint ->
                ensureCanContinue()?.let {
                    scheduleFollowingRun = false
                    return@withContext it
                }
                val attempts = mapConcurrentOrdered(checkpoint, SYNC_TRANSFER_CONCURRENCY) { item ->
                    uploadPrepared(client, item, remoteFiles.getValue(item.category)) {
                        beforeRemoteMutation()
                    }
                }
                recordAttempts(attempts)
            }

            if (remoteFailed == 0) {
                val deletionAttempts = mapConcurrentOrdered(deletions, SYNC_TRANSFER_CONCURRENCY) { entry ->
                    try {
                        ensureCanContinue()?.let { throw IOException("Wi-Fi connection lost") }
                        beforeRemoteMutation()
                        try {
                            client.delete(listOf("Sync", device) + entry.relativeRemoteSegments)
                        } catch (error: DavException) {
                            if (error.status != 404) throw error
                        }
                        entry to null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        if (isStopped) throw CancellationException("Sync cancelled", error)
                        entry to (error.message ?: "Delete failed")
                    }
                }
                val editor = state.edit()
                deletionAttempts.forEach { (entry, error) ->
                    if (error == null) {
                        deleted++
                        if (entry.localKey !in currentByKey) {
                            editor.remove("item_${sha256("$stateScope|${entry.localKey}")}")
                        }
                    } else {
                        failed++
                        remoteFailed++
                        lastError = "${entry.category.label}: $error"
                    }
                }
                if (deletionAttempts.any { it.second == null }) editor.apply()
                done += deletionAttempts.size
                publishProgress(done, totalItems, uploaded)
            }

            if (remoteFailed == 0) {
                syncRunCatching {
                    val root = listOf("Sync", device)
                    val existingFolders = try {
                        client.listCloud(root, force = true)
                            .filterTo(linkedSetOf()) { it.isDirectory }
                            .mapTo(linkedSetOf(), BrowserEntry::name)
                    } catch (error: DavException) {
                        if (error.status == 404) emptySet() else throw error
                    }
                    val missingFolders = authoritativeCategories.mapTo(linkedSetOf(), SyncCategory::cloudFolder) - existingFolders
                    if (missingFolders.isNotEmpty()) {
                        beforeRemoteMutation()
                        missingFolders.forEach { folder -> client.ensureDirectories(root + folder) }
                    }
                }.onFailure {
                    failed++
                    remoteFailed++
                    lastError = "Could not prepare sync folders: ${it.message ?: "server error"}"
                }
            }

            if (remoteFailed == 0 && recoveringMissingMarker) {
                syncRunCatching {
                    val pathsByEntry = nextEntries.values.associateBy { listOf("Sync", device) + it.relativeRemoteSegments }
                    val existing = existingRemoteSizes(client, pathsByEntry.keys.toList())
                    pathsByEntry.count { (path, entry) -> existing[path] != entry.size }
                }.onSuccess { missingFiles ->
                    if (missingFiles > 0) {
                        failed++
                        lastError = "Backup recovery is waiting for $missingFiles missing ${if (missingFiles == 1) "file" else "files"}"
                    }
                }.onFailure {
                    failed++
                    remoteFailed++
                    lastError = "Could not verify backup recovery: ${it.message ?: "server error"}"
                }
            }

            var publishedRevision = priorIndex.revision
            if (remoteFailed == 0 && indexChanged) {
                syncRunCatching {
                    beforeRemoteMutation()
                    publishedRevision = UUID.randomUUID().toString()
                    publishSyncIndex(
                        client,
                        device,
                        publishedRevision,
                        nextMigratedCategories,
                        immutableMigratedMediaVolumes,
                        nextEntries,
                    )
                }.onFailure {
                    failed++
                    remoteFailed++
                    lastError = "Could not update sync index: ${it.message ?: "server error"}"
                }
            }

            if (failed == 0) {
                val desiredMarker = "$SYNC_INDEX_MARKER_PREFIX$publishedRevision"
                if (markerInvalidated.get() || currentMarker != desiredMarker) {
                    syncRunCatching {
                        val marker = desiredMarker.toByteArray()
                        marker.inputStream().use { input ->
                            client.upload(listOf("Sync", device), BACKUP_COMPLETE_MARKER, "text/plain", marker.size.toLong(), input)
                        }
                    }.onFailure {
                        failed++
                        lastError = "Could not update backup completion marker: ${it.message ?: "server error"}"
                    }
                }
            }

            val summary = buildString {
                append("Uploaded $uploaded, deleted $deleted, current $skipped, failed $failed")
                if (missing.isNotEmpty()) append(". Permission needed: ${missing.joinToString { it.label }}")
                if (lastError != null) append(". $lastError")
            }
            AppSettings.saveLastSyncStatus(applicationContext, summary, direction)
            val output = Data.Builder()
                .putInt(KEY_UPLOADED, uploaded)
                .putInt(KEY_DELETED, deleted)
                .putInt(KEY_SKIPPED, skipped)
                .putInt(KEY_FAILED, failed)
                .putInt(KEY_TOTAL, totalItems)
                .putString(KEY_MESSAGE, summary)
                .putString(KEY_DIRECTION, direction.name)
                .build()
            if (failed > 0 && !isOnWifi()) {
                scheduleFollowingRun = false
                Result.retry()
            } else if (failed > 0) {
                scheduleFollowingRun = false
                if (automatic) Result.retry() else Result.failure(output)
            } else {
                scheduleFollowingRun = true
                Result.success(output)
            }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isStopped) throw CancellationException("Sync cancelled", error)
                failure("Sync failed: ${error.message ?: "unexpected error"}", automatic)
            }
        } finally {
            temporaryExports.forEach { it.delete() }
            syncLock.release()
            lockChannel.close()
            if (automatic && scheduleFollowingRun) AppSettings.scheduleNext(applicationContext, generation)
        }
    }

    private fun loadSyncIndex(client: DavClient, device: String): SyncIndex {
        val root = listOf("Sync", device)
        val remote = try {
            client.listCloud(root, force = true).firstOrNull { !it.isDirectory && it.name == SYNC_INDEX_FILE }
        } catch (error: DavException) {
            if (error.status == 404) return SyncIndex() else throw error
        } ?: return SyncIndex()
        require(remote.size in 1..SYNC_INDEX_MAX_BYTES) { "Sync index is too large" }
        val temporary = File.createTempFile("sync-index-", ".json", applicationContext.cacheDir)
        return try {
            temporary.outputStream().use { output ->
                client.download(root + SYNC_INDEX_FILE, output, expectedSize = remote.size)
            }
            readSyncIndex(temporary, device)
        } finally {
            temporary.delete()
        }
    }

    private fun readSyncIndex(file: File, device: String): SyncIndex {
        var schema = -1
        var indexedDevice = ""
        var revision = ""
        val migratedCategories = linkedSetOf<SyncCategory>()
        val migratedMediaVolumes = linkedMapOf<SyncCategory, MutableSet<String>>()
        val entries = linkedMapOf<String, SyncIndexEntry>()
        val paths = hashSetOf<List<String>>()
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "schema_version" -> schema = reader.nextInt()
                    "device" -> indexedDevice = reader.nextString()
                    "revision" -> revision = reader.nextString()
                    "migrated_categories" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            val categoryName = reader.nextString()
                            val category = SyncCategory.entries.firstOrNull { it.name == categoryName }
                                ?: error("Sync index migration category is invalid")
                            require(migratedCategories.add(category)) { "Sync index contains duplicate migration categories" }
                        }
                        reader.endArray()
                    }
                    "migrated_media_volumes" -> {
                        reader.beginObject()
                        while (reader.hasNext()) {
                            val categoryName = reader.nextName()
                            val category = SyncCategory.entries.firstOrNull { it.name == categoryName && it in MEDIA_CATEGORIES }
                                ?: error("Sync index media migration category is invalid")
                            val volumes = migratedMediaVolumes.getOrPut(category) { linkedSetOf() }
                            reader.beginArray()
                            while (reader.hasNext()) {
                                val volume = reader.nextString()
                                require(volume.length in 1..128 && !volume.contains(Regex("[\u0000-\u001f]")) && volumes.add(volume)) {
                                    "Sync index media migration volume is invalid"
                                }
                            }
                            reader.endArray()
                        }
                        reader.endObject()
                    }
                    "entries" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            require(entries.size < SYNC_INDEX_MAX_ENTRIES) { "Sync index has too many entries" }
                            val entry = readSyncIndexEntry(reader)
                            require(entries.put(entry.localKey, entry) == null) { "Sync index contains duplicate keys" }
                            require(paths.add(entry.relativeRemoteSegments)) { "Sync index contains duplicate paths" }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        require(schema == SYNC_INDEX_SCHEMA && indexedDevice == device) { "Sync index does not match this device backup" }
        require(runCatching { UUID.fromString(revision) }.isSuccess) { "Sync index revision is invalid" }
        return SyncIndex(
            present = true,
            revision = revision,
            migratedCategories = migratedCategories,
            migratedMediaVolumes = migratedMediaVolumes.mapValues { it.value.toSet() },
            entries = entries,
        )
    }

    private fun readSyncIndexEntry(reader: JsonReader): SyncIndexEntry {
        var localKey = ""
        var categoryName = ""
        var signature = ""
        var size = -1L
        var mimeType = ""
        var sourceVolume: String? = null
        val path = mutableListOf<String>()
        reader.beginObject()
        while (reader.hasNext()) {
            when (reader.nextName()) {
                "key" -> localKey = reader.nextString()
                "category" -> categoryName = reader.nextString()
                "signature" -> signature = reader.nextString()
                "size" -> size = reader.nextLong()
                "mime" -> mimeType = reader.nextString()
                "volume" -> sourceVolume = reader.nextString()
                "path" -> {
                    reader.beginArray()
                    while (reader.hasNext()) path += reader.nextString()
                    reader.endArray()
                }
                else -> reader.skipValue()
            }
        }
        reader.endObject()
        val category = SyncCategory.entries.firstOrNull { it.name == categoryName }
            ?: error("Sync index category is invalid")
        require(localKey.matches(Regex("v1:[a-f0-9]{64}")) && signature.length in 4..256 && size >= 0 && mimeType.length in 1..256) {
            "Sync index entry is invalid"
        }
        require(sourceVolume == null || sourceVolume.length in 1..128 && !sourceVolume.contains(Regex("[\u0000-\u001f]"))) {
            "Sync index entry volume is invalid"
        }
        requireValidIndexedPath(category, path)
        return SyncIndexEntry(localKey, category, path, signature, size, mimeType, sourceVolume)
    }

    private fun requireValidIndexedPath(category: SyncCategory, path: List<String>) {
        require(path.size in 2..64 && path.first() in setOf(category.cloudFolder, category.folder)) {
            "Sync index path is invalid"
        }
        path.forEach { segment ->
            require(segment.isNotBlank() && segment == segment.trim() && segment != "." && segment != ".." &&
                !segment.contains('/') && !segment.contains('\\') && !segment.contains(Regex("[\u0000-\u001f]")) &&
                !segment.startsWith(".clouddrive-stage-", true) && segment != SYNC_INDEX_FILE && segment != BACKUP_COMPLETE_MARKER
            ) { "Sync index path is invalid" }
        }
    }

    private fun requireValidSyncIndexEntries(entries: Map<String, SyncIndexEntry>) {
        require(entries.size <= SYNC_INDEX_MAX_ENTRIES) { "Sync index has too many entries" }
        val paths = hashSetOf<List<String>>()
        entries.forEach { (key, entry) ->
            require(key == entry.localKey && key.matches(Regex("v1:[a-f0-9]{64}"))) { "Sync index entry is invalid" }
            require(entry.signature.length in 4..256 && entry.size >= 0 && entry.mimeType.length in 1..256) {
                "Sync index entry is invalid"
            }
            require(entry.sourceVolume == null || entry.sourceVolume.length in 1..128 &&
                !entry.sourceVolume.contains(Regex("[\u0000-\u001f]"))) { "Sync index entry volume is invalid" }
            requireValidIndexedPath(entry.category, entry.relativeRemoteSegments)
            require(paths.add(entry.relativeRemoteSegments)) { "Sync index contains duplicate paths" }
        }
    }

    private fun publishSyncIndex(
        client: DavClient,
        device: String,
        revision: String,
        migratedCategories: Set<SyncCategory>,
        migratedMediaVolumes: Map<SyncCategory, Set<String>>,
        entries: Map<String, SyncIndexEntry>,
    ) {
        requireValidSyncIndexEntries(entries)
        val temporary = File.createTempFile("sync-index-publish-", ".json", applicationContext.cacheDir)
        try {
            JsonWriter(OutputStreamWriter(temporary.outputStream(), Charsets.UTF_8)).use { writer ->
                writer.beginObject()
                writer.name("schema_version").value(SYNC_INDEX_SCHEMA.toLong())
                writer.name("device").value(device)
                writer.name("revision").value(revision)
                writer.name("migrated_categories").beginArray()
                migratedCategories.sortedBy(SyncCategory::name).forEach { writer.value(it.name) }
                writer.endArray()
                writer.name("migrated_media_volumes").beginObject()
                migratedMediaVolumes.toSortedMap(compareBy(SyncCategory::name)).forEach { (category, volumes) ->
                    writer.name(category.name).beginArray()
                    volumes.sorted().forEach(writer::value)
                    writer.endArray()
                }
                writer.endObject()
                writer.name("entries").beginArray()
                entries.toSortedMap().values.forEach { entry ->
                    writer.beginObject()
                    writer.name("key").value(entry.localKey)
                    writer.name("category").value(entry.category.name)
                    writer.name("path").beginArray()
                    entry.relativeRemoteSegments.forEach { writer.value(it) }
                    writer.endArray()
                    writer.name("signature").value(entry.signature)
                    writer.name("size").value(entry.size)
                    writer.name("mime").value(entry.mimeType)
                    entry.sourceVolume?.let { writer.name("volume").value(it) }
                    writer.endObject()
                }
                writer.endArray()
                writer.endObject()
            }
            require(temporary.length() <= SYNC_INDEX_MAX_BYTES) { "Sync index is too large" }
            temporary.inputStream().use { input ->
                client.upload(listOf("Sync", device), SYNC_INDEX_FILE, "application/json", temporary.length(), input)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun PreparedUpload.toSyncIndexEntry() = SyncIndexEntry(
        localKey = localKey,
        category = category,
        relativeRemoteSegments = relativeRemoteSegments,
        signature = signature,
        size = size,
        mimeType = mimeType,
        sourceVolume = sourceVolume,
    )

    private fun PreparedUpload.withRelativeRemoteSegments(device: String, segments: List<String>): PreparedUpload = copy(
        relativeRemoteSegments = segments,
        alternativeRemoteSegments = emptyList(),
        fallbackRemoteSegments = null,
        remoteDirectory = listOf("Sync", device) + segments.dropLast(1),
        remoteName = segments.last(),
        remoteKey = remotePathKey(listOf("Sync", device) + segments),
    )

    private fun PreparedUpload.wasMigrated(index: SyncIndex): Boolean = if (category in MEDIA_CATEGORIES) {
        sourceVolume != null && sourceVolume in index.migratedMediaVolumes[category].orEmpty()
    } else {
        category in index.migratedCategories
    }

    private suspend fun planRemoteUploads(
        client: DavClient,
        uploads: List<PreparedUpload>,
        priorIndex: SyncIndex,
        device: String,
    ): RemoteUploadPlan = coroutineScope {
        val probes = uploads.mapNotNull { upload ->
            val indexed = priorIndex.entries[upload.localKey]
            val indexedCurrent = indexed?.let {
                it.signature == upload.signature &&
                    it.size == upload.size &&
                    it.relativeRemoteSegments == upload.relativeRemoteSegments
            } == true
            val migrationCandidate = indexed == null && !upload.wasMigrated(priorIndex)
            if (!indexedCurrent && !migrationCandidate) {
                null
            } else {
                RemoteUploadProbe(
                    upload = upload,
                    migration = migrationCandidate,
                    paths = (listOf(upload.relativeRemoteSegments) + upload.alternativeRemoteSegments)
                        .distinct()
                        .map { listOf("Sync", device) + it },
                )
            }
        }
        val expectedPaths = probes.flatMap(RemoteUploadProbe::paths).distinct()
        val existingSizes = existingRemoteSizes(client, expectedPaths)
        val matchingPaths = probes.associate { probe ->
            probe.upload.localKey to probe.paths.filter { existingSizes[it] == probe.upload.size }
        }
        val pathClaims = matchingPaths.values.flatten().groupingBy { it }.eachCount()
        val pathOwners = linkedMapOf<List<String>, MutableSet<String>>()
        priorIndex.entries.values.forEach { entry ->
            pathOwners.getOrPut(listOf("Sync", device) + entry.relativeRemoteSegments) { linkedSetOf() } += entry.localKey
        }
        uploads.forEach { upload ->
            pathOwners.getOrPut(listOf("Sync", device) + upload.relativeRemoteSegments) { linkedSetOf() } += upload.localKey
        }
        val matchedPaths = matchingPaths.mapValues { (localKey, paths) ->
            paths.firstOrNull { path ->
                pathClaims[path] == 1 && pathOwners[path].orEmpty().all { owner -> owner == localKey }
            }
        }.toMutableMap()
        val unresolvedMigrations = probes.filter { it.migration && matchedPaths[it.upload.localKey] == null }
        if (unresolvedMigrations.isNotEmpty()) {
            val roots = unresolvedMigrations.flatMap { probe ->
                listOf(probe.upload.category.cloudFolder, probe.upload.category.folder).distinct().map { folder ->
                    listOf("Sync", device, folder)
                }
            }.distinct()
            val discovered = mapConcurrentOrdered(roots, SYNC_TRANSFER_CONCURRENCY) { root ->
                buildList {
                    try {
                        client.forEachCloudTree(root) { entry -> if (!entry.isDirectory) add(entry) }
                    } catch (error: DavException) {
                        if (error.status != 404) throw error
                    }
                }
            }.flatten()
            val candidatesByName = linkedMapOf<MigrationNameKey, MutableList<MigrationRemoteCandidate>>()
            discovered.forEach { entry ->
                generatedMigrationNames(entry.name).forEach { (candidateName, distance) ->
                    val key = MigrationNameKey(entry.size, candidateName.lowercase(Locale.ROOT))
                    candidatesByName.getOrPut(key) { mutableListOf() } += MigrationRemoteCandidate(
                        entry = entry,
                        nameDistance = distance,
                        preferredRoot = false,
                    )
                }
            }
            val assignedPaths = matchedPaths.values.filterNotNull().toMutableSet()
            fun assignMigrationMatches(exactNamesOnly: Boolean) {
                unresolvedMigrations.sortedBy { it.upload.localKey }.forEach { probe ->
                    val upload = probe.upload
                    if (matchedPaths[upload.localKey] != null) return@forEach
                    val key = MigrationNameKey(upload.size, upload.migrationName.lowercase(Locale.ROOT))
                    val match = candidatesByName[key].orEmpty().asSequence()
                        .filter { candidate -> (candidate.nameDistance == 0) == exactNamesOnly }
                        .filter { candidate -> candidate.entry.cloudSegments !in assignedPaths }
                        .filter { candidate ->
                            pathOwners[candidate.entry.cloudSegments].orEmpty().all { owner -> owner == upload.localKey }
                        }
                        .mapNotNull { candidate ->
                            migrationDirectoryDistance(upload, candidate.entry.cloudSegments)?.let { distance ->
                                candidate.copy(
                                    directoryDistance = distance,
                                    preferredRoot = candidate.entry.cloudSegments.getOrNull(2) == upload.category.cloudFolder,
                                )
                            }
                        }
                        .sortedWith(
                            compareBy<MigrationRemoteCandidate> { it.directoryDistance }
                                .thenBy { it.nameDistance }
                                .thenByDescending { it.preferredRoot }
                                .thenByDescending { it.entry.modified }
                                .thenBy { it.entry.cloudSegments.joinToString("/") },
                        )
                        .firstOrNull()
                        ?.entry
                    if (match != null) {
                        matchedPaths[upload.localKey] = match.cloudSegments
                        assignedPaths += match.cloudSegments
                    }
                }
            }
            assignMigrationMatches(exactNamesOnly = true)
            assignMigrationMatches(exactNamesOnly = false)
        }
        val provisionalPathCounts = uploads.groupingBy(PreparedUpload::relativeRemoteSegments).eachCount()
        val resolvedUploads = uploads.map { upload ->
            val matched = matchedPaths[upload.localKey]
            val provisionalOwners = pathOwners[listOf("Sync", device) + upload.relativeRemoteSegments].orEmpty()
            when {
                matched != null -> upload.withRelativeRemoteSegments(device, matched.drop(2))
                (provisionalPathCounts[upload.relativeRemoteSegments]!! > 1 ||
                    provisionalOwners.any { it != upload.localKey }) && upload.fallbackRemoteSegments != null ->
                    upload.withRelativeRemoteSegments(device, upload.fallbackRemoteSegments)
                else -> upload
            }
        }
        val remoteFiles = resolvedUploads.mapTo(linkedSetOf(), PreparedUpload::category)
            .associateWithTo(linkedMapOf()) { linkedSetOf<String>() }
        resolvedUploads.forEach { upload ->
            if (matchedPaths[upload.localKey] != null) remoteFiles.getValue(upload.category) += upload.remoteKey
        }
        RemoteUploadPlan(resolvedUploads, remoteFiles)
    }

    private fun migrationDirectoryDistance(upload: PreparedUpload, remotePath: List<String>): Int? {
        val expected = upload.relativeRemoteSegments.drop(1).dropLast(1)
        val actual = remotePath.drop(3).dropLast(1)
        fun samePath(left: List<String>, right: List<String>) = left.size == right.size &&
            left.indices.all { index -> left[index].equals(right[index], ignoreCase = true) }
        if (samePath(actual, expected)) return 0
        val wrappers = when (upload.category) {
            SyncCategory.Downloads -> listOf("Download", "Downloads")
            SyncCategory.Photos, SyncCategory.Videos -> listOf(upload.category.label, upload.category.folder)
            else -> emptyList()
        }
        return if (actual.isNotEmpty() && wrappers.any { actual.first().equals(it, ignoreCase = true) } &&
            samePath(actual.drop(1), expected)) 1 else null
    }

    private fun generatedMigrationNames(remoteName: String): List<Pair<String, Int>> {
        fun parts(name: String): Pair<String, String> {
            val dot = name.lastIndexOf('.')
            return if (dot > 0) name.substring(0, dot) to name.substring(dot) else name to ""
        }
        val (initialStem, extension) = parts(remoteName)
        return buildList {
            add(remoteName to 0)
            var stem = initialStem
            var removed = 0
            while (true) {
                val suffix = GENERATED_UPLOAD_SUFFIX.find(stem) ?: break
                stem = stem.removeRange(suffix.range)
                add(stem + extension to ++removed)
            }
        }
    }

    private suspend fun existingRemoteSizes(
        client: DavClient,
        paths: List<List<String>>,
    ): Map<List<String>, Long> = try {
        mapConcurrentOrdered(paths.chunked(REMOTE_STATUS_BATCH_SIZE), SYNC_TRANSFER_CONCURRENCY) { batch ->
            client.existingFileSizes(batch)
        }.fold(mutableMapOf<List<String>, Long>()) { combined, sizes -> combined.apply { putAll(sizes) } }
    } catch (error: DavException) {
        if (error.status !in setOf(404, 405, 501)) throw error
        val expectedPathSet = paths.toHashSet()
        mapConcurrentOrdered(paths.map { it.take(3) }.distinct(), SYNC_TRANSFER_CONCURRENCY) { base ->
            buildMap {
                try {
                    client.forEachCloudTree(base, force = true) { entry ->
                        if (!entry.isDirectory && entry.cloudSegments in expectedPathSet) put(entry.cloudSegments, entry.size)
                    }
                } catch (error: DavException) {
                    if (error.status != 404) throw error
                }
            }
        }.fold(mutableMapOf<List<String>, Long>()) { combined, sizes -> combined.apply { putAll(sizes) } }
    }

    private fun prepareUploads(
        media: List<MediaItem>,
        downloads: List<DownloadItem>,
        exports: List<ExportItem>,
        priorIndex: SyncIndex,
        stateScope: String,
        device: String,
        originalAccess: Boolean,
    ): List<PreparedUpload> = buildList {
        media.forEach { item ->
            val relativeDirectories = cloudRelativeSegments(item.relativePath)
            val localKey = stableLocalKey(
                "media",
                item.category.name,
                item.volume,
                normalizedLocalPath(item.relativePath),
                item.name,
            )
            val categoryMigrated = item.volume in priorIndex.migratedMediaVolumes[item.category].orEmpty()
            val defaultId = if (categoryMigrated) localKey.substringAfter(':').take(12) else item.uri.lastPathSegment.orEmpty()
            val defaultRemoteName = sanitizeCloudSegment(appendStableId(item.name, defaultId))
            val stableRemoteName = sanitizeCloudSegment(appendStableId(item.name, localKey.substringAfter(':').take(12)))
            val indexedSegments = priorIndex.entries[localKey]
                ?.takeIf { it.category == item.category }
                ?.relativeRemoteSegments
            val relativeRemoteSegments = indexedSegments
                ?: (listOf(item.category.cloudFolder) + relativeDirectories + defaultRemoteName)
            val alternativeRemoteSegments = if (indexedSegments == null && !categoryMigrated && item.category.folder != item.category.cloudFolder) {
                listOf(listOf(item.category.folder) + relativeDirectories + defaultRemoteName)
            } else {
                emptyList()
            }
            val remoteDirectory = listOf("Sync", device) + relativeRemoteSegments.dropLast(1)
            val remoteName = relativeRemoteSegments.last()
            val signature = quickFingerprint(
                item.modified,
                item.size,
                item.mimeType,
                "generation=${item.generation}\u0000original=$originalAccess",
            )
            val sourceUri = if (originalAccess) MediaStore.setRequireOriginal(item.uri) else item.uri
            add(
                PreparedUpload(
                    localKey = localKey,
                    category = item.category,
                    migrationName = sanitizeCloudSegment(item.name),
                    relativeRemoteSegments = relativeRemoteSegments,
                    alternativeRemoteSegments = alternativeRemoteSegments,
                    fallbackRemoteSegments = if (indexedSegments == null && !categoryMigrated) {
                        listOf(item.category.cloudFolder) + relativeDirectories + stableRemoteName
                    } else {
                        null
                    },
                    remoteDirectory = remoteDirectory,
                    remoteName = remoteName,
                    mimeType = item.mimeType,
                    size = item.size,
                    sourceVolume = item.volume,
                    stateKey = "item_${sha256("$stateScope|$localKey")}",
                    signature = signature,
                    remoteKey = remotePathKey(remoteDirectory + remoteName),
                    openInput = {
                        applicationContext.contentResolver.openInputStream(sourceUri)
                            ?: error("Cannot read ${item.name}")
                    },
                ),
            )
        }
        downloads.forEach { item ->
            val category = SyncCategory.Downloads
            val localKey = stableLocalKey("download", (item.relativeDirectories + item.file.name).joinToString("/"))
            val downloadId = if (category in priorIndex.migratedCategories) {
                localKey.substringAfter(':').take(12)
            } else {
                sha256(item.file.absolutePath).take(12)
            }
            val defaultRemoteName = sanitizeCloudSegment(appendStableId(item.file.name, downloadId))
            val relativeRemoteSegments = priorIndex.entries[localKey]
                ?.takeIf { it.category == category }
                ?.relativeRemoteSegments
                ?: (listOf(category.cloudFolder) + item.relativeDirectories + defaultRemoteName)
            val remoteDirectory = listOf("Sync", device) + relativeRemoteSegments.dropLast(1)
            val remoteName = relativeRemoteSegments.last()
            add(
                PreparedUpload(
                    localKey = localKey,
                    category = category,
                    migrationName = sanitizeCloudSegment(item.file.name),
                    relativeRemoteSegments = relativeRemoteSegments,
                    remoteDirectory = remoteDirectory,
                    remoteName = remoteName,
                    mimeType = item.mimeType,
                    size = item.size,
                    stateKey = "item_${sha256("$stateScope|$localKey")}",
                    signature = quickFingerprint(item.modified, item.size, item.mimeType, item.changeToken),
                    remoteKey = remotePathKey(remoteDirectory + remoteName),
                    openInput = item.file::inputStream,
                ),
            )
        }
        exports.forEach { item ->
            val localKey = stableLocalKey("export", item.category.name)
            val signature = "q1:${item.signature}"
            val indexed = priorIndex.entries[localKey]?.takeIf { it.category == item.category && it.signature == signature }
            val relativeRemoteSegments = indexed?.relativeRemoteSegments
                ?: listOf(item.category.cloudFolder, item.fileName)
            val remoteDirectory = listOf("Sync", device) + relativeRemoteSegments.dropLast(1)
            add(
                PreparedUpload(
                    localKey = localKey,
                    category = item.category,
                    migrationName = item.fileName,
                    relativeRemoteSegments = relativeRemoteSegments,
                    remoteDirectory = remoteDirectory,
                    remoteName = relativeRemoteSegments.last(),
                    mimeType = "application/json",
                    size = item.file.length(),
                    stateKey = "item_${sha256("$stateScope|$localKey")}",
                    signature = signature,
                    remoteKey = remotePathKey(listOf("Sync", device) + relativeRemoteSegments),
                    openInput = item.file::inputStream,
                ),
            )
        }
    }.also { uploads ->
        require(uploads.map(PreparedUpload::localKey).toSet().size == uploads.size) { "Selected data contains duplicate sync identities" }
    }

    private fun uploadPrepared(
        client: DavClient,
        upload: PreparedUpload,
        remoteFiles: Set<String>,
        beforeUpload: () -> Unit,
    ): UploadAttempt {
        if (upload.remoteKey in remoteFiles) {
            return upload.attempt(skipped = true)
        }
        var lastError: Exception? = null
        repeat(SYNC_TRANSFER_ATTEMPTS) { attempt ->
            if (isStopped) throw CancellationException("Sync cancelled")
            if (!isOnWifi()) return upload.attempt(error = "Transfer stopped because Wi-Fi disconnected")
            try {
                upload.openInput().use { input ->
                    beforeUpload()
                    client.upload(
                        upload.remoteDirectory,
                        upload.remoteName,
                        upload.mimeType,
                        upload.size,
                        input,
                        continueTransfer = { !isStopped && isOnWifi() },
                    )
                }
                return upload.attempt()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (isStopped) throw CancellationException("Sync cancelled", error)
                lastError = error
                val retryable = error is IOException || (error is DavException && (error.status == 409 || error.status >= 500))
                if (!retryable || attempt == SYNC_TRANSFER_ATTEMPTS - 1 || !isOnWifi()) {
                    return upload.attempt(error = error.message ?: "Upload failed")
                }
            }
        }
        return upload.attempt(error = lastError?.message ?: "Upload failed")
    }

    private suspend fun <T, R : Any> mapConcurrentOrdered(
        items: List<T>,
        concurrency: Int,
        transform: suspend (T) -> R,
    ): List<R> = coroutineScope {
        if (items.isEmpty()) return@coroutineScope emptyList()
        val next = AtomicInteger(0)
        val results = arrayOfNulls<Any>(items.size)
        List(minOf(concurrency, items.size)) {
            async {
                while (true) {
                    val index = next.getAndIncrement()
                    if (index >= items.size) break
                    results[index] = transform(items[index])
                }
            }
        }.awaitAll()
        @Suppress("UNCHECKED_CAST")
        results.map { it as R }
    }

    private fun remotePathKey(segments: List<String>) = segments.joinToString("\u0000")

    private suspend fun publishProgress(done: Int, total: Int, uploaded: Int) {
        val now = SystemClock.elapsedRealtime()
        while (done != total) {
            val previous = lastProgressUpdate.get()
            if (now - previous < 250) return
            if (lastProgressUpdate.compareAndSet(previous, now)) break
        }
        if (done == total) lastProgressUpdate.set(now)
        setProgress(
            Data.Builder()
                .putInt(KEY_DONE, done)
                .putInt(KEY_TOTAL, total)
                .putInt(KEY_UPLOADED, uploaded)
                .build(),
        )
        setForeground(foregroundInfo("Syncing selected data", done, total))
    }

    private fun ensureCanContinue(): Result? = when {
        isStopped -> throw CancellationException("Sync cancelled")
        !isOnWifi() -> Result.retry()
        else -> null
    }

    private inline fun <T> syncRunCatching(block: () -> T): kotlin.Result<T> = try {
        kotlin.Result.success(block())
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        kotlin.Result.failure(error)
    }

    private fun mediaVolumeToken(volume: String) = MediaVolumeToken(
        version = MediaStore.getVersion(applicationContext, volume),
        generation = if (Build.VERSION.SDK_INT >= 30) MediaStore.getGeneration(applicationContext, volume) else 0,
    )

    private fun queryMedia(categories: Set<SyncCategory>, volumes: Set<String>): MediaSnapshot {
        val items = mutableListOf<MediaItem>()
        val completeCategories = linkedSetOf<SyncCategory>()
        if (SyncCategory.Photos in categories && canReadImages()) {
            var complete = true
            volumes.forEach { volume ->
                if (!queryCollection(MediaStore.Images.Media.getContentUri(volume), SyncCategory.Photos, volume, items)) complete = false
            }
            if (complete) completeCategories += SyncCategory.Photos
        }
        if (SyncCategory.Videos in categories && canReadVideos()) {
            var complete = true
            volumes.forEach { volume ->
                if (!queryCollection(MediaStore.Video.Media.getContentUri(volume), SyncCategory.Videos, volume, items)) complete = false
            }
            if (complete) completeCategories += SyncCategory.Videos
        }
        return MediaSnapshot(items.sortedBy { it.modified }, completeCategories)
    }

    private fun queryCollection(
        collection: Uri,
        category: SyncCategory,
        expectedVolume: String,
        output: MutableList<MediaItem>,
    ): Boolean {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.VOLUME_NAME,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED) else emptyArray()
        val cursor = applicationContext.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
            arrayOf("%CloudDrive Restore/%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} ASC",
        ) ?: return false
        var complete = true
        cursor.use {
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val volumeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.VOLUME_NAME)
            val generationColumn = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED) else -1
            while (cursor.moveToNext()) {
                if (isStopped) throw CancellationException("Sync cancelled")
                val nameValue = cursor.getString(nameColumn)
                val pathValue = cursor.getString(pathColumn)
                val volumeValue = cursor.getString(volumeColumn)
                if (nameValue.isNullOrBlank() || pathValue == null || volumeValue.isNullOrBlank() || volumeValue != expectedVolume) {
                    complete = false
                    continue
                }
                output += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    name = nameValue,
                    relativePath = pathValue,
                    modified = cursor.getLong(modifiedColumn),
                    generation = if (generationColumn >= 0) cursor.getLong(generationColumn) else 0,
                    size = cursor.getLong(sizeColumn),
                    mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                    volume = volumeValue,
                    category = category,
                )
            }
        }
        return complete
    }

    private fun queryDownloads(): DownloadSnapshot {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!root.isDirectory || !root.canRead()) return DownloadSnapshot(emptyList(), complete = false)
        var complete = true
        val items = root.walkTopDown()
            .onEnter { directory ->
                directory.canRead().also { readable -> if (!readable) complete = false }
            }
            .onFail { _, _ -> complete = false }
            .onEach {
                if (isStopped) throw CancellationException("Sync cancelled")
                if (!it.canRead()) complete = false
            }
            .filter { it.isFile && it.canRead() && !it.relativeTo(root).invariantSeparatorsPath.startsWith("CloudDrive Restore/") }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath.substringBeforeLast('/', "")
                DownloadItem(
                    file = file,
                    relativeDirectories = relative.split('/').filter(String::isNotEmpty),
                    size = file.length(),
                    modified = file.lastModified(),
                    mimeType = mimeType(file),
                    changeToken = runCatching { Os.stat(file.absolutePath) }
                        .map { stat -> "inode=${stat.st_ino}\u0000ctime=${stat.st_ctime}" }
                        .getOrDefault(""),
                )
            }
            .sortedBy { it.file.absolutePath }
            .toList()
        return DownloadSnapshot(items, complete)
    }

    private fun collectFileRestoreTasks(
        client: DavClient,
        base: List<String>,
        categories: Set<SyncCategory>,
        relativePrefix: List<String> = emptyList(),
    ): List<RestoreTask> {
        val discovered = mutableListOf<RestoreTask>()
        client.forEachCloudTree(base, force = true) { entry ->
            if (entry.isDirectory) return@forEachCloudTree
            val category = if (categories.any { it in MEDIA_CATEGORIES }) {
                when {
                    entry.mimeType.startsWith("image/") -> SyncCategory.Photos
                    entry.mimeType.startsWith("video/") -> SyncCategory.Videos
                    else -> null
                }?.takeIf { it in categories }
            } else {
                categories.singleOrNull()
            } ?: return@forEachCloudTree
            discovered += RestoreTask(
                category,
                entry,
                relativePrefix + entry.cloudSegments.drop(base.size).dropLast(1),
                export = false,
            )
        }
        return discovered
    }

    private suspend fun restoreFromCloud(
        client: DavClient,
        stateScope: String,
        legacyStateScope: String,
        device: String,
        categories: Set<SyncCategory>,
        missing: Set<SyncCategory>,
    ): SyncOutcome {
        val restoreGeneration = backupGeneration(client, device)
        if (restoreGeneration == null) {
            return SyncOutcome(0, 0, 1, 0, "The selected device backup is incomplete")
        }
        val tasks = mutableListOf<RestoreTask>()
        val errors = mutableListOf<String>()
        val availableIndex = loadSyncIndex(client, device)
        val indexedRestore = if (availableIndex.present) {
            if (!restoreGeneration.startsWith(SYNC_INDEX_MARKER_PREFIX) ||
                availableIndex.revision != restoreGeneration.removePrefix(SYNC_INDEX_MARKER_PREFIX)) {
                return SyncOutcome(0, 0, 1, 0, "The selected device backup index is incomplete")
            }
            availableIndex
        } else {
            if (restoreGeneration.startsWith(SYNC_INDEX_MARKER_PREFIX)) {
                return SyncOutcome(0, 0, 1, 0, "The selected device backup index is incomplete")
            }
            null
        }
        if (indexedRestore != null) {
            indexedRestore.entries.values.filter { it.category in categories }.forEach { indexed ->
                val segments = listOf("Sync", device) + indexed.relativeRemoteSegments
                tasks += RestoreTask(
                    category = indexed.category,
                    entry = BrowserEntry(
                        source = BrowserSource.CloudDrive,
                        name = indexed.relativeRemoteSegments.last(),
                        isDirectory = false,
                        size = indexed.size,
                        modified = 0,
                        mimeType = indexed.mimeType,
                        cloudSegments = segments,
                    ),
                    relativeDirectories = if (indexed.category in EXPORT_CATEGORIES) {
                        emptyList()
                    } else {
                        indexed.relativeRemoteSegments.drop(1).dropLast(1)
                    },
                    export = indexed.category in EXPORT_CATEGORIES,
                    indexedSignature = indexed.signature,
                )
            }
        } else {
        val mediaCategories = categories.intersect(MEDIA_CATEGORIES)
        if (mediaCategories.isNotEmpty()) {
            val mediaBase = listOf("Sync", device, SyncCategory.Photos.cloudFolder)
            val mediaKeys = hashSetOf<String>()
            fun addMedia(mediaTasks: List<RestoreTask>) {
                mediaTasks.forEach { task ->
                    val key = "${task.category.name}\u0000${task.relativeDirectories.joinToString("/")}\u0000${task.entry.name}"
                    if (mediaKeys.add(key)) tasks += task
                }
            }
            val unifiedError = syncRunCatching { collectFileRestoreTasks(client, mediaBase, mediaCategories) }
                .onSuccess(::addMedia)
                .exceptionOrNull()
            mediaCategories.forEach { category ->
                val legacyBase = listOf("Sync", device, category.folder)
                val legacyError = syncRunCatching {
                    collectFileRestoreTasks(client, legacyBase, setOf(category), listOf(category.label))
                }.onSuccess(::addMedia).exceptionOrNull()
                val hasCategory = tasks.any { it.category == category }
                val seriousError = listOfNotNull(unifiedError, legacyError)
                    .firstOrNull { it !is DavException || it.status != 404 }
                when {
                    seriousError != null -> errors += "${category.label}: ${seriousError.message ?: "backup folder unavailable"}"
                    !hasCategory -> errors += "${category.label}: no backup files found"
                }
            }
        }
        categories.filterNot { it in MEDIA_CATEGORIES }.forEach { category ->
            val base = listOf("Sync", device, category.cloudFolder)
            syncRunCatching {
                if (category in EXPORT_CATEGORIES) {
                    val newest = client.listCloud(base, force = true)
                        .asSequence()
                        .filter { !it.isDirectory && it.name.endsWith(".json", true) }
                        .maxWithOrNull(compareBy<BrowserEntry> { it.modified }.thenBy { it.name })
                    val snapshot = newest ?: error("No ${category.label} backup snapshot found")
                    tasks += RestoreTask(category, snapshot, emptyList(), export = true)
                } else {
                    tasks += collectFileRestoreTasks(client, base, setOf(category))
                }
            }.onFailure { errors += "${category.label}: ${it.message ?: "backup folder unavailable"}" }
        }
        }

        val restored = AtomicInteger()
        var skipped = 0
        var failed = errors.size + missing.size
        val done = AtomicInteger()
        var lastError = errors.lastOrNull()
        tasks.chunked(RESTORE_STATE_CHECKPOINT_SIZE).forEach { checkpoint ->
            ensureCanContinue()?.let {
                return SyncOutcome(restored.get(), skipped, failed + 1, tasks.size, "Restore interrupted")
            }
            if (backupGeneration(client, device) != restoreGeneration) {
                return SyncOutcome(restored.get(), skipped, failed + 1, tasks.size, "Backup changed during restore; try again")
            }
            val attempts = mapConcurrentOrdered(checkpoint, SYNC_TRANSFER_CONCURRENCY) { task ->
                val attempt = restoreTask(client, stateScope, legacyStateScope, device, restoreGeneration, task)
                val changed = if (attempt.restored) restored.incrementAndGet() else restored.get()
                publishProgress(done.incrementAndGet(), tasks.size, changed)
                attempt
            }
            if (backupGeneration(client, device) != restoreGeneration) {
                return SyncOutcome(restored.get(), skipped, failed + 1, tasks.size, "Backup changed during restore; try again")
            }
            val editor = state.edit()
            var stateChanged = false
            attempts.forEach { attempt ->
                when {
                    attempt.skipped -> {
                        skipped++
                        attempt.stateValues.forEach { (key, value) ->
                            if (state.getString(key, null) != value) {
                                editor.putString(key, value)
                                stateChanged = true
                            }
                        }
                    }
                    attempt.error != null -> {
                        failed++
                        lastError = attempt.error
                    }
                    else -> attempt.stateValues.forEach { (key, value) ->
                        editor.putString(key, value)
                        stateChanged = true
                    }
                }
            }
            if (stateChanged) editor.apply()
        }
        val summary = buildString {
            append("Restored ${restored.get()}, current $skipped, failed $failed")
            if (missing.isNotEmpty()) append(". Permission needed: ${missing.joinToString { it.label }}")
            if (lastError != null) append(". $lastError")
        }
        return SyncOutcome(restored.get(), skipped, failed, tasks.size, summary)
    }

    private fun backupGeneration(client: DavClient, device: String): String? {
        val bytes = ByteArrayOutputStream()
        val limited = object : OutputStream() {
            override fun write(value: Int) {
                if (bytes.size() >= BACKUP_MARKER_MAX_BYTES) throw IOException("Invalid backup marker")
                bytes.write(value)
            }

            override fun write(buffer: ByteArray, offset: Int, length: Int) {
                if (bytes.size() + length > BACKUP_MARKER_MAX_BYTES) throw IOException("Invalid backup marker")
                bytes.write(buffer, offset, length)
            }
        }
        return try {
            client.download(listOf("Sync", device, BACKUP_COMPLETE_MARKER), limited)
            bytes.toString(Charsets.UTF_8.name()).trim().ifEmpty { null }
        } catch (error: DavException) {
            if (error.status == 404) null else throw error
        }
    }

    private fun restoreTask(
        client: DavClient,
        stateScope: String,
        legacyStateScope: String,
        device: String,
        restoreGeneration: String,
        task: RestoreTask,
    ): RestoreAttempt {
        return try {
        ensureCanContinue()?.let { throw IOException("Wi-Fi connection lost") }
        val signature = task.indexedSignature
            ?: "${task.entry.modified}:${task.entry.size}:${task.entry.cloudSegments.joinToString("/")}"
        val stateKey = "restore_${sha256("$stateScope|${task.entry.cloudSegments.joinToString("/")}")}"
        val legacyStateKey = "restore_${sha256("$legacyStateScope|${task.entry.cloudSegments.joinToString("/")}")}"
        val target = if (task.export) null else restoreTarget(task)
        val targetStateKey = target?.let { "restore_target_${sha256("$stateScope|${it.absolutePath}")}" }
        val legacyTargetStateKey = target?.let { "restore_target_${sha256(it.absolutePath)}" }
        val targetSignature = target?.takeIf(File::isFile)?.let {
            "$stateKey|$signature|${it.length()}|${it.lastModified()}"
        }
        val legacyTargetSignature = target?.takeIf(File::isFile)?.let {
            "$legacyStateKey|$signature|${it.length()}|${it.lastModified()}"
        }
        val scopedCurrent = !task.export && state.getString(stateKey, null) == signature &&
            targetStateKey != null && targetSignature != null &&
            state.getString(targetStateKey, null) == targetSignature
        val legacyCurrent = !task.export && state.getString(legacyStateKey, null) == signature &&
            legacyTargetStateKey != null && legacyTargetSignature != null &&
            state.getString(legacyTargetStateKey, null) == legacyTargetSignature
        if (scopedCurrent || legacyCurrent) {
            val migratedState = mutableMapOf(
                stateKey to signature,
                requireNotNull(targetStateKey) to requireNotNull(targetSignature),
            )
            return RestoreAttempt(skipped = true, stateValues = migratedState)
        }

        if (task.export) {
            val temporary = File.createTempFile("sync-restore-", ".json", applicationContext.cacheDir)
            try {
                temporary.outputStream().use {
                    client.download(
                        task.entry.cloudSegments,
                        it,
                        expectedSize = task.entry.size,
                        continueTransfer = { !isStopped && isOnWifi() },
                    )
                }
                if (backupGeneration(client, device) != restoreGeneration) {
                    error("Backup changed during restore; try again")
                }
                when (task.category) {
                    SyncCategory.Contacts -> restoreContacts(temporary, device)
                    SyncCategory.SmsMessages -> restoreSms(temporary, device == syncDeviceFolder(applicationContext))
                    SyncCategory.CallHistory -> restoreCalls(temporary)
                    else -> Unit
                }
            } finally {
                temporary.delete()
            }
            RestoreAttempt(restored = true, stateValues = mapOf(stateKey to signature))
        } else {
            val outputFile = requireNotNull(target)
            outputFile.parentFile?.mkdirs()
            val temporary = File(outputFile.parentFile, ".${outputFile.name}.restore-${UUID.randomUUID()}")
            try {
                temporary.outputStream().use {
                    client.download(
                        task.entry.cloudSegments,
                        it,
                        expectedSize = task.entry.size,
                        continueTransfer = { !isStopped && isOnWifi() },
                    )
                }
                if (backupGeneration(client, device) != restoreGeneration) {
                    error("Backup changed during restore; try again")
                }
                syncRunCatching {
                    Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                }.getOrElse {
                    Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                }
            } finally {
                temporary.delete()
            }
            if (task.category == SyncCategory.Photos || task.category == SyncCategory.Videos) {
                MediaScannerConnection.scanFile(applicationContext, arrayOf(outputFile.absolutePath), null, null)
            }
            val restoredTargetSignature = "$stateKey|$signature|${outputFile.length()}|${outputFile.lastModified()}"
            RestoreAttempt(
                restored = true,
                stateValues = mapOf(stateKey to signature, requireNotNull(targetStateKey) to restoredTargetSignature),
            )
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        if (isStopped) throw CancellationException("Sync cancelled", error)
        RestoreAttempt(error = "${task.category.label}: ${error.message ?: "restore failed"}")
        }
    }

    private fun restoreTarget(task: RestoreTask): File {
        val safeName = requireRestoreSegment(task.entry.name)
        val requestedDirectories = task.relativeDirectories.map(::requireRestoreSegment)
            .dropWhile { it.equals(RESTORE_DIRECTORY, true) }
        val (root, directories) = when (task.category) {
            SyncCategory.Downloads -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to
                (listOf(RESTORE_DIRECTORY) + requestedDirectories.dropWhile {
                    it.equals("Download", true) || it.equals("Downloads", true)
                })
            SyncCategory.Photos -> Environment.getExternalStorageDirectory() to
                (listOf(RESTORE_DIRECTORY) + requestedDirectories.ifEmpty { listOf("Photos") })
            SyncCategory.Videos -> Environment.getExternalStorageDirectory() to
                (listOf(RESTORE_DIRECTORY) + requestedDirectories.ifEmpty { listOf("Videos") })
            else -> error("${task.category.label} does not restore to a file")
        }
        val rootPath = root.canonicalFile
        val target = File(rootPath, (directories + safeName).joinToString(File.separator)).canonicalFile
        require(target.toPath().startsWith(rootPath.toPath()) && target != rootPath) { "Invalid restore path" }
        return target
    }

    private fun requireRestoreSegment(value: String): String {
        require(value.isNotBlank() && value != "." && value != ".." && !value.contains('/') && !value.contains('\\')) {
            "Invalid restore path"
        }
        return value
    }

    private fun restoreContacts(file: File, sourceDevice: String) {
        val sourcePrefix = "clouddrive:$sourceDevice:"
        val rawContacts = existingRawContacts(sourcePrefix).toMutableMap()
        var schemaVersion: Int? = null
        var rawContactsFound = false
        var dataFound = false
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "schema_version" -> schemaVersion = reader.nextInt()
                    "raw_contacts" -> {
                        require(schemaVersion == 1) { "Unsupported backup schema" }
                        rawContactsFound = true
                        val pendingIds = mutableListOf<String>()
                        val operations = arrayListOf<ContentProviderOperation>()
                        fun flush() {
                            if (operations.isEmpty()) return
                            val results = applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
                            require(results.size == pendingIds.size) { "Cannot create contacts" }
                            results.forEachIndexed { index, result ->
                                rawContacts[pendingIds[index]] = ContentUris.parseId(
                                    result.uri ?: error("Cannot create contact"),
                                )
                            }
                            pendingIds.clear()
                            operations.clear()
                        }
                        forEachRow(reader) { row ->
                            val oldId = row[ContactsContract.RawContacts._ID]?.toString() ?: return@forEachRow
                            if (oldId in rawContacts) return@forEachRow
                            pendingIds += oldId
                            val values = ContentValues().apply {
                                putNull(ContactsContract.RawContacts.ACCOUNT_NAME)
                                putNull(ContactsContract.RawContacts.ACCOUNT_TYPE)
                                put(ContactsContract.RawContacts.SOURCE_ID, "$sourcePrefix$oldId")
                            }
                            operations += ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                                .withValues(values)
                                .build()
                            if (operations.size == RESTORE_CONTACT_BATCH_SIZE) flush()
                        }
                        flush()
                    }
                    "data" -> {
                        require(schemaVersion == 1) { "Unsupported backup schema" }
                        dataFound = true
                        var currentRawId: String? = null
                        val rows = mutableListOf<Map<String, Any?>>()
                        fun flush() {
                            val rawId = currentRawId ?: return
                            rawContacts[rawId]?.let { mapped -> replaceContactData(mapped, rows) }
                            rows.clear()
                        }
                        forEachRow(reader) { row ->
                            val rawId = row[ContactsContract.Data.RAW_CONTACT_ID]?.toString() ?: return@forEachRow
                            if (currentRawId != null && currentRawId != rawId) flush()
                            currentRawId = rawId
                            rows += row
                        }
                        flush()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        require(schemaVersion == 1 && rawContactsFound && dataFound) { "Invalid contacts backup" }
    }

    private fun replaceContactData(rawContactId: Long, rows: List<Map<String, Any?>>) {
        if (isStopped) throw CancellationException("Sync cancelled")
        val operations = arrayListOf(
            ContentProviderOperation.newDelete(ContactsContract.Data.CONTENT_URI)
                .withSelection("${ContactsContract.Data.RAW_CONTACT_ID}=?", arrayOf(rawContactId.toString()))
                .build(),
        )
        rows.forEach { row ->
            val mime = row[ContactsContract.Data.MIMETYPE]?.toString() ?: return@forEach
            if (mime == ContactsContract.CommonDataKinds.GroupMembership.CONTENT_ITEM_TYPE) return@forEach
            val values = ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, mime)
                putValue(this, ContactsContract.Data.IS_PRIMARY, row[ContactsContract.Data.IS_PRIMARY])
                putValue(this, ContactsContract.Data.IS_SUPER_PRIMARY, row[ContactsContract.Data.IS_SUPER_PRIMARY])
                for (index in 1..15) {
                    val column = "data$index"
                    val value = row[column]
                    if (column == ContactsContract.Data.DATA15 && mime == ContactsContract.CommonDataKinds.Photo.CONTENT_ITEM_TYPE && value is String) {
                        put(column, Base64.decode(value, Base64.DEFAULT))
                    } else {
                        putValue(this, column, value)
                    }
                }
            }
            operations += ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI).withValues(values).build()
        }
        applicationContext.contentResolver.applyBatch(ContactsContract.AUTHORITY, operations)
    }

    private fun restoreSms(file: File, preserveSubscriptionIds: Boolean) {
        val occurrences = mutableMapOf<String, Int>()
        val found = forEachNamedRowBatch(file, "messages", RESTORE_PROVIDER_BATCH_SIZE) { rows ->
            if (isStopped) throw CancellationException("Sync cancelled")
            val prepared = rows.mapNotNull { row ->
                val date = row[Telephony.Sms.DATE]?.toString()?.toLongOrNull() ?: return@mapNotNull null
                val type = row[Telephony.Sms.TYPE]?.toString()?.toIntOrNull() ?: 0
                val address = row[Telephony.Sms.ADDRESS]?.toString()
                val body = row[Telephony.Sms.BODY]?.toString()
                val subscriptionId = row[Telephony.Sms.SUBSCRIPTION_ID]?.toString()?.toIntOrNull()
                val hasSubscription = preserveSubscriptionIds &&
                    row.containsKey(Telephony.Sms.SUBSCRIPTION_ID) &&
                    subscriptionId != null &&
                    SubscriptionManager.getSlotIndex(subscriptionId) != SubscriptionManager.INVALID_SIM_SLOT_INDEX
                val identity = smsIdentity(date, type, address, body, hasSubscription, subscriptionId)
                PreparedProviderRow(
                    identity = identity,
                    occurrence = occurrences.merge(identity, 1, Int::plus) ?: 1,
                    date = date,
                    values = ContentValues().apply {
                        putNullable(Telephony.Sms.ADDRESS, address)
                        put(Telephony.Sms.DATE, date)
                        putValue(this, Telephony.Sms.DATE_SENT, row[Telephony.Sms.DATE_SENT])
                        put(Telephony.Sms.TYPE, type)
                        putNullable(Telephony.Sms.BODY, body)
                        putValue(this, Telephony.Sms.READ, row[Telephony.Sms.READ])
                        putValue(this, Telephony.Sms.SEEN, row[Telephony.Sms.SEEN])
                        if (hasSubscription) putValue(this, Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                    },
                )
            }
            val requested = prepared.mapTo(hashSetOf(), PreparedProviderRow::identity)
            val existing = mutableMapOf<String, Int>()
            queryProviderDates(
                uri = Telephony.Sms.CONTENT_URI,
                projection = arrayOf(Telephony.Sms.DATE, Telephony.Sms.TYPE, Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.SUBSCRIPTION_ID),
                dateColumn = Telephony.Sms.DATE,
                dates = prepared.map(PreparedProviderRow::date),
            ) { cursor ->
                val date = cursor.getLong(0)
                val type = cursor.getInt(1)
                val address = if (cursor.isNull(2)) null else cursor.getString(2)
                val body = if (cursor.isNull(3)) null else cursor.getString(3)
                val subscriptionId = if (cursor.isNull(4)) null else cursor.getInt(4)
                listOf(
                    smsIdentity(date, type, address, body, true, subscriptionId),
                    smsIdentity(date, type, address, body, false, null),
                ).forEach { identity -> if (identity in requested) existing.merge(identity, 1, Int::plus) }
            }
            val inserts = prepared.mapNotNull { item ->
                val available = existing[item.identity] ?: 0
                if (available >= item.occurrence) null else item.values.also { existing[item.identity] = available + 1 }
            }
            bulkInsertBatches(Telephony.Sms.CONTENT_URI, inserts, "SMS")
        }
        require(found) { "Invalid messages backup" }
    }

    private fun restoreCalls(file: File) {
        val occurrences = mutableMapOf<String, Int>()
        val found = forEachNamedRowBatch(file, "calls", RESTORE_PROVIDER_BATCH_SIZE) { rows ->
            if (isStopped) throw CancellationException("Sync cancelled")
            val prepared = rows.mapNotNull { row ->
                val date = row[CallLog.Calls.DATE]?.toString()?.toLongOrNull() ?: return@mapNotNull null
                val number = row[CallLog.Calls.NUMBER]?.toString()
                val duration = row[CallLog.Calls.DURATION]?.toString()?.toLongOrNull() ?: 0
                val type = row[CallLog.Calls.TYPE]?.toString()?.toIntOrNull() ?: 0
                val identity = callIdentity(date, duration, type, number)
                PreparedProviderRow(
                    identity = identity,
                    occurrence = occurrences.merge(identity, 1, Int::plus) ?: 1,
                    date = date,
                    values = ContentValues().apply {
                        putNullable(CallLog.Calls.NUMBER, number)
                        put(CallLog.Calls.DATE, date)
                        put(CallLog.Calls.DURATION, duration)
                        put(CallLog.Calls.TYPE, type)
                        putNullable(CallLog.Calls.CACHED_NAME, row[CallLog.Calls.CACHED_NAME]?.toString())
                        putNullable(CallLog.Calls.COUNTRY_ISO, row[CallLog.Calls.COUNTRY_ISO]?.toString())
                        putNullable(CallLog.Calls.GEOCODED_LOCATION, row[CallLog.Calls.GEOCODED_LOCATION]?.toString())
                        putValue(this, CallLog.Calls.NEW, row[CallLog.Calls.NEW])
                        putValue(this, CallLog.Calls.IS_READ, row[CallLog.Calls.IS_READ])
                    },
                )
            }
            val requested = prepared.mapTo(hashSetOf(), PreparedProviderRow::identity)
            val existing = mutableMapOf<String, Int>()
            queryProviderDates(
                uri = CallLog.Calls.CONTENT_URI,
                projection = arrayOf(CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.NUMBER),
                dateColumn = CallLog.Calls.DATE,
                dates = prepared.map(PreparedProviderRow::date),
            ) { cursor ->
                val identity = callIdentity(
                    date = cursor.getLong(0),
                    duration = cursor.getLong(1),
                    type = cursor.getInt(2),
                    number = if (cursor.isNull(3)) null else cursor.getString(3),
                )
                if (identity in requested) existing.merge(identity, 1, Int::plus)
            }
            val inserts = prepared.mapNotNull { item ->
                val available = existing[item.identity] ?: 0
                if (available >= item.occurrence) null else item.values.also { existing[item.identity] = available + 1 }
            }
            bulkInsertBatches(CallLog.Calls.CONTENT_URI, inserts, "call history")
        }
        require(found) { "Invalid call history backup" }
    }

    private fun bulkInsertBatches(uri: Uri, values: List<ContentValues>, label: String) {
        var offset = 0
        while (offset < values.size) {
            val batch = ArrayList<ContentValues>(RESTORE_PROVIDER_INSERT_COUNT)
            var bytes = 0
            while (offset < values.size && batch.size < RESTORE_PROVIDER_INSERT_COUNT) {
                val value = values[offset]
                val valueBytes = contentValuesBytes(value)
                if (batch.isNotEmpty() && bytes + valueBytes > RESTORE_PROVIDER_INSERT_BYTES) break
                batch += value
                bytes += valueBytes
                offset++
            }
            if (applicationContext.contentResolver.bulkInsert(uri, batch.toTypedArray()) != batch.size) {
                error("Cannot restore $label batch")
            }
        }
    }

    private fun contentValuesBytes(values: ContentValues): Int = values.valueSet().sumOf { (key, value) ->
        key.length * 2 + when (value) {
            is ByteArray -> value.size
            is String -> value.length * 2
            else -> 16
        }
    }

    private fun forEachNamedRowBatch(
        file: File,
        arrayName: String,
        batchSize: Int,
        action: (List<Map<String, Any?>>) -> Unit,
    ): Boolean {
        var schemaVersion: Int? = null
        var found = false
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                val name = reader.nextName()
                if (name == "schema_version") {
                    schemaVersion = reader.nextInt()
                    continue
                }
                if (name != arrayName) {
                    reader.skipValue()
                    continue
                }
                require(schemaVersion == 1) { "Unsupported backup schema" }
                found = true
                val batch = ArrayList<Map<String, Any?>>(batchSize)
                reader.beginArray()
                while (reader.hasNext()) {
                    batch += readRow(reader)
                    if (batch.size == batchSize) {
                        action(batch)
                        batch.clear()
                    }
                }
                reader.endArray()
                if (batch.isNotEmpty()) action(batch)
            }
            reader.endObject()
        }
        require(schemaVersion == 1) { "Unsupported backup schema" }
        return found
    }

    private fun forEachRow(reader: JsonReader, action: (Map<String, Any?>) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
            if (isStopped) throw CancellationException("Sync cancelled")
            action(readRow(reader))
        }
        reader.endArray()
    }

    private fun readRow(reader: JsonReader): Map<String, Any?> {
        val row = linkedMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val name = reader.nextName()
            row[name] = when (reader.peek()) {
                android.util.JsonToken.NULL -> { reader.nextNull(); null }
                android.util.JsonToken.BOOLEAN -> reader.nextBoolean()
                android.util.JsonToken.NUMBER, android.util.JsonToken.STRING -> reader.nextString()
                else -> { reader.skipValue(); null }
            }
        }
        reader.endObject()
        return row
    }

    private fun queryProviderDates(
        uri: Uri,
        projection: Array<String>,
        dateColumn: String,
        dates: List<Long>,
        action: (Cursor) -> Unit,
    ) {
        val uniqueDates = dates.distinct()
        if (uniqueDates.isEmpty()) return
        val selection = "$dateColumn IN (${List(uniqueDates.size) { "?" }.joinToString()})"
        val cursor = applicationContext.contentResolver.query(
            uri,
            projection,
            selection,
            uniqueDates.map(Long::toString).toTypedArray(),
            null,
        ) ?: error("Cannot query existing device data")
        cursor.use { while (it.moveToNext()) action(it) }
    }

    private fun smsIdentity(
        date: Long,
        type: Int,
        address: String?,
        body: String?,
        hasSubscription: Boolean,
        subscriptionId: Int?,
    ) = "$date\u001f$type\u001f${identityValue(address)}\u001f${identityValue(body)}\u001f" +
        if (hasSubscription) "sim:${subscriptionId ?: "null"}" else "legacy"

    private fun callIdentity(date: Long, duration: Long, type: Int, number: String?) =
        "$date\u001f$duration\u001f$type\u001f${identityValue(number)}"

    private fun identityValue(value: String?) = value?.let { "${it.length}:$it" } ?: "null"

    private fun existingRawContacts(sourcePrefix: String): Map<String, Long> {
        val contacts = mutableMapOf<String, Long>()
        val cursor = applicationContext.contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.SOURCE_ID),
            "${ContactsContract.RawContacts.SOURCE_ID} IS NOT NULL",
            null,
            null,
        ) ?: error("Cannot query existing contacts")
        cursor.use {
            while (it.moveToNext()) {
                val sourceId = it.getString(1)
                if (sourceId.startsWith(sourcePrefix)) contacts[sourceId.removePrefix(sourcePrefix)] = it.getLong(0)
            }
        }
        return contacts
    }

    private fun putValue(values: ContentValues, key: String, value: Any?) {
        when (value) {
            null -> values.putNull(key)
            is Boolean -> values.put(key, value)
            is Number -> values.put(key, value.toString().toLongOrNull())
            else -> values.put(key, value.toString())
        }
    }

    private fun ContentValues.putNullable(key: String, value: String?) {
        if (value == null) putNull(key) else put(key, value)
    }

    private fun createExport(category: SyncCategory): ExportItem {
        val baseName = when (category) {
            SyncCategory.Contacts -> "contacts"
            SyncCategory.SmsMessages -> "messages"
            SyncCategory.CallHistory -> "calls"
            else -> error("${category.label} is not an export category")
        }
        val file = File.createTempFile("sync-export-", ".json", applicationContext.cacheDir)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            DigestOutputStream(file.outputStream(), digest).use { output ->
                JsonWriter(OutputStreamWriter(output, Charsets.UTF_8)).use { writer ->
                    writer.beginObject()
                    writer.name("schema_version").value(1)
                    when (category) {
                        SyncCategory.Contacts -> writeContacts(writer)
                        SyncCategory.SmsMessages -> writeRows(
                            writer,
                            "messages",
                            Telephony.Sms.CONTENT_URI,
                            arrayOf(Telephony.Sms._ID, Telephony.Sms.ADDRESS, Telephony.Sms.DATE, Telephony.Sms.DATE_SENT, Telephony.Sms.TYPE, Telephony.Sms.BODY, Telephony.Sms.READ, Telephony.Sms.SEEN, Telephony.Sms.THREAD_ID, Telephony.Sms.SUBSCRIPTION_ID),
                            "${Telephony.Sms._ID} ASC",
                        )
                        SyncCategory.CallHistory -> writeRows(
                            writer,
                            "calls",
                            CallLog.Calls.CONTENT_URI,
                            arrayOf(CallLog.Calls._ID, CallLog.Calls.NUMBER, CallLog.Calls.DATE, CallLog.Calls.DURATION, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.COUNTRY_ISO, CallLog.Calls.GEOCODED_LOCATION, CallLog.Calls.NEW, CallLog.Calls.IS_READ),
                            "${CallLog.Calls._ID} ASC",
                        )
                        else -> Unit
                    }
                    writer.endObject()
                }
            }
            val signature = digest.digest().toHex()
            return ExportItem(category, "$baseName-${signature.take(16)}.json", file, signature)
        } catch (error: Exception) {
            file.delete()
            throw error
        }
    }

    private fun writeContacts(writer: JsonWriter) {
        writeRows(
            writer,
            "contacts",
            ContactsContract.Contacts.CONTENT_URI,
            arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.STARRED, ContactsContract.Contacts.PHOTO_URI),
            "${ContactsContract.Contacts._ID} ASC",
        )
        writeRows(
            writer,
            "raw_contacts",
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID, ContactsContract.RawContacts.CONTACT_ID, ContactsContract.RawContacts.ACCOUNT_NAME, ContactsContract.RawContacts.ACCOUNT_TYPE, ContactsContract.RawContacts.SOURCE_ID, ContactsContract.RawContacts.DELETED),
            "${ContactsContract.RawContacts._ID} ASC",
        )
        writeRows(
            writer,
            "data",
            ContactsContract.Data.CONTENT_URI,
            arrayOf(
                ContactsContract.Data._ID,
                ContactsContract.Data.CONTACT_ID,
                ContactsContract.Data.RAW_CONTACT_ID,
                ContactsContract.Data.MIMETYPE,
                ContactsContract.Data.IS_PRIMARY,
                ContactsContract.Data.IS_SUPER_PRIMARY,
                ContactsContract.Data.DATA1,
                ContactsContract.Data.DATA2,
                ContactsContract.Data.DATA3,
                ContactsContract.Data.DATA4,
                ContactsContract.Data.DATA5,
                ContactsContract.Data.DATA6,
                ContactsContract.Data.DATA7,
                ContactsContract.Data.DATA8,
                ContactsContract.Data.DATA9,
                ContactsContract.Data.DATA10,
                ContactsContract.Data.DATA11,
                ContactsContract.Data.DATA12,
                ContactsContract.Data.DATA13,
                ContactsContract.Data.DATA14,
                ContactsContract.Data.DATA15,
            ),
            "${ContactsContract.Data.RAW_CONTACT_ID} ASC, ${ContactsContract.Data._ID} ASC",
        )
    }

    private fun writeRows(writer: JsonWriter, name: String, uri: Uri, columns: Array<String>, sort: String) {
        writer.name(name).beginArray()
        val rows = applicationContext.contentResolver.query(uri, columns, null, null, sort)
            ?: error("Cannot read ${name.replace('_', ' ')}")
        rows.use { cursor ->
            while (cursor.moveToNext()) {
                if (isStopped) throw CancellationException("Sync cancelled")
                writer.beginObject()
                columns.forEachIndexed { index, column ->
                    writer.name(column)
                    writeCursorValue(writer, cursor, index)
                }
                writer.endObject()
            }
        }
        writer.endArray()
    }

    private fun writeCursorValue(writer: JsonWriter, cursor: Cursor, index: Int) {
        when (cursor.getType(index)) {
            Cursor.FIELD_TYPE_NULL -> writer.nullValue()
            Cursor.FIELD_TYPE_INTEGER -> writer.value(cursor.getLong(index))
            Cursor.FIELD_TYPE_FLOAT -> writer.value(cursor.getDouble(index))
            Cursor.FIELD_TYPE_BLOB -> writer.value(Base64.encodeToString(cursor.getBlob(index), Base64.NO_WRAP))
            else -> writer.value(cursor.getString(index))
        }
    }

    private fun isOnWifi(): Boolean {
        val manager = applicationContext.getSystemService(ConnectivityManager::class.java)
        val network = manager.activeNetwork ?: return false
        return manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    private fun canReadImages() = hasPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) || (Build.VERSION.SDK_INT >= 34 && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun canReadVideos() = hasPermission(
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
    ) || (Build.VERSION.SDK_INT >= 34 && hasPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED))

    private fun hasCompleteMediaAccess(category: SyncCategory): Boolean = when (category) {
        SyncCategory.Photos -> hasPermission(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        SyncCategory.Videos -> hasPermission(
            if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_VIDEO else Manifest.permission.READ_EXTERNAL_STORAGE,
        )
        else -> true
    }

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun foregroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        if (!notificationChannelReady) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Data sync", NotificationManager.IMPORTANCE_LOW))
            notificationChannelReady = true
        }
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud_drive)
            .setContentTitle("CloudDrive Sync")
            .setContentText(message)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(total, done, total == 0)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun failure(message: String, automatic: Boolean = false): Result {
        val direction = inputData.getString(KEY_DIRECTION)
            ?.let { name -> SyncDirection.entries.firstOrNull { it.name == name } }
            ?: AppSettings.syncDirection(applicationContext)
        AppSettings.saveLastSyncStatus(applicationContext, message, direction)
        val output = Data.Builder()
            .putString(KEY_MESSAGE, message)
            .putString(KEY_DIRECTION, direction.name)
            .build()
        return if (automatic) Result.retry() else Result.failure(output)
    }

    private fun cloudRelativeSegments(path: String): List<String> = path
        .replace('\\', '/')
        .split('/')
        .filter { it.isNotBlank() && it != "." && it != ".." }
        .map(::sanitizeCloudSegment)

    private fun normalizedLocalPath(path: String): String = path.replace('\\', '/').trim('/')

    private fun stableLocalKey(vararg parts: String): String {
        val encoded = buildString {
            parts.forEach { part ->
                val bytes = part.toByteArray(Charsets.UTF_8)
                append(bytes.size).append(':').append(part)
            }
        }
        return "v1:${sha256(encoded)}"
    }

    private fun quickFingerprint(modified: Long, size: Long, mimeType: String, extra: String = ""): String =
        "q1:${sha256("$modified\u0000$size\u0000$mimeType\u0000$extra")}"

    private fun mimeType(file: File): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun sha256(value: String): String = sha256(value.toByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(value).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun appendStableId(name: String, id: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) + "__" + id + name.substring(dot) else name + "__" + id
    }

    private data class MediaItem(
        val uri: Uri,
        val name: String,
        val relativePath: String,
        val modified: Long,
        val generation: Long,
        val size: Long,
        val mimeType: String,
        val volume: String,
        val category: SyncCategory,
    )
    private data class MediaSnapshot(
        val items: List<MediaItem>,
        val completeCategories: Set<SyncCategory>,
    )
    private data class MediaVolumeToken(val version: String, val generation: Long)

    private data class DownloadItem(
        val file: File,
        val relativeDirectories: List<String>,
        val size: Long,
        val modified: Long,
        val mimeType: String,
        val changeToken: String,
    )
    private data class DownloadSnapshot(val items: List<DownloadItem>, val complete: Boolean)
    private data class ExportItem(val category: SyncCategory, val fileName: String, val file: File, val signature: String)
    private data class PreparedUpload(
        val localKey: String,
        val category: SyncCategory,
        val migrationName: String,
        val relativeRemoteSegments: List<String>,
        val alternativeRemoteSegments: List<List<String>> = emptyList(),
        val fallbackRemoteSegments: List<String>? = null,
        val remoteDirectory: List<String>,
        val remoteName: String,
        val mimeType: String,
        val size: Long,
        val sourceVolume: String? = null,
        val stateKey: String,
        val signature: String,
        val remoteKey: String,
        val openInput: () -> InputStream,
    ) {
        fun attempt(skipped: Boolean = false, error: String? = null) = UploadAttempt(
            category = category,
            remoteKey = remoteKey,
            stateKey = stateKey,
            signature = signature,
            skipped = skipped,
            error = error,
        )

    }
    private data class UploadAttempt(
        val category: SyncCategory,
        val remoteKey: String,
        val stateKey: String,
        val signature: String,
        val skipped: Boolean = false,
        val error: String? = null,
    )
    private data class RemoteUploadProbe(
        val upload: PreparedUpload,
        val migration: Boolean,
        val paths: List<List<String>>,
    )
    private data class RemoteUploadPlan(
        val uploads: List<PreparedUpload>,
        val remoteFiles: Map<SyncCategory, MutableSet<String>>,
    )
    private data class MigrationRemoteCandidate(
        val entry: BrowserEntry,
        val nameDistance: Int,
        val directoryDistance: Int = Int.MAX_VALUE,
        val preferredRoot: Boolean,
    )
    private data class MigrationNameKey(val size: Long, val name: String)
    private data class RestoreAttempt(
        val restored: Boolean = false,
        val skipped: Boolean = false,
        val stateValues: Map<String, String> = emptyMap(),
        val error: String? = null,
    )
    private data class PreparedProviderRow(
        val identity: String,
        val occurrence: Int,
        val date: Long,
        val values: ContentValues,
    )
    private data class SyncIndex(
        val present: Boolean = false,
        val revision: String = "",
        val migratedCategories: Set<SyncCategory> = emptySet(),
        val migratedMediaVolumes: Map<SyncCategory, Set<String>> = emptyMap(),
        val entries: Map<String, SyncIndexEntry> = emptyMap(),
    )
    private data class SyncIndexEntry(
        val localKey: String,
        val category: SyncCategory,
        val relativeRemoteSegments: List<String>,
        val signature: String,
        val size: Long,
        val mimeType: String,
        val sourceVolume: String? = null,
    )
    private data class RestoreTask(
        val category: SyncCategory,
        val entry: BrowserEntry,
        val relativeDirectories: List<String>,
        val export: Boolean,
        val indexedSignature: String? = null,
    )

    private data class SyncOutcome(
        val restored: Int,
        val skipped: Int,
        val failed: Int,
        val total: Int,
        val summary: String,
    ) {
        fun toData(direction: SyncDirection): Data = Data.Builder()
            .putInt(KEY_UPLOADED, restored)
            .putInt(KEY_SKIPPED, skipped)
            .putInt(KEY_FAILED, failed)
            .putInt(KEY_TOTAL, total)
            .putString(KEY_MESSAGE, summary)
            .putString(KEY_DIRECTION, direction.name)
            .build()
    }

    companion object {
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_UPLOADED = "uploaded"
        const val KEY_DELETED = "deleted"
        const val KEY_SKIPPED = "skipped"
        const val KEY_FAILED = "failed"
        const val KEY_MESSAGE = "message"
        const val KEY_AUTOMATIC = "automatic"
        const val KEY_GENERATION = "generation"
        const val KEY_DIRECTION = "direction"
        const val KEY_CATEGORIES = "categories"
        const val KEY_DRIVE_ID = "drive_id"
        const val KEY_RESTORE_DEVICE = "restore_device"
        private const val CHANNEL_ID = "media_sync"
        private const val NOTIFICATION_ID = 1101
        private const val SYNC_TRANSFER_CONCURRENCY = 3
        private const val SYNC_TRANSFER_ATTEMPTS = 2
        private const val SYNC_STATE_CHECKPOINT_SIZE = 500
        private const val RESTORE_STATE_CHECKPOINT_SIZE = 100
        private const val BACKUP_MARKER_MAX_BYTES = 256
        private const val SYNC_INDEX_FILE = ".sync-index.json"
        private const val SYNC_INDEX_SCHEMA = 1
        private const val SYNC_INDEX_MARKER_PREFIX = "sync-index-v1:"
        private const val SYNC_INDEX_MAX_BYTES = 32L * 1024 * 1024
        private const val SYNC_INDEX_MAX_ENTRIES = 250_000
        private const val RESTORE_PROVIDER_BATCH_SIZE = 200
        private const val RESTORE_PROVIDER_INSERT_COUNT = 100
        private const val RESTORE_PROVIDER_INSERT_BYTES = 512 * 1024
        private const val RESTORE_CONTACT_BATCH_SIZE = 100
        private const val REMOTE_STATUS_BATCH_SIZE = 500
        private const val RESTORE_DIRECTORY = "CloudDrive Restore"
        const val BACKUP_COMPLETE_MARKER = ".backup-complete"
        private val MEDIA_CATEGORIES = setOf(SyncCategory.Photos, SyncCategory.Videos)
        private val EXPORT_CATEGORIES = setOf(SyncCategory.Contacts, SyncCategory.SmsMessages, SyncCategory.CallHistory)
        private val GENERATED_UPLOAD_SUFFIX = Regex("__(?:[a-fA-F0-9]{12}|\\d+)$")
    }
}
