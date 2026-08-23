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
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStreamWriter
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.security.MessageDigest
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class MediaSyncWorker(
    context: Context,
    parameters: WorkerParameters,
) : CoroutineWorker(context, parameters) {
    private val state = context.getSharedPreferences("media_sync_state", Context.MODE_PRIVATE)
    private var lastProgressUpdate = 0L

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
            var folderGenerations = emptyMap<SyncCategory, String>()
            var restoreDevice = device
            var folderError = "Server unavailable"
            for (drive in candidates) {
                val candidate = davClient(applicationContext, drive)
                try {
                    val candidateGenerations = linkedMapOf<SyncCategory, String>()
                    if (direction == SyncDirection.DeviceToCloud) {
                        val markersByFolder = mutableMapOf<String, String>()
                        categories.forEach { category ->
                            val marker = markersByFolder.getOrPut(category.cloudFolder) {
                                val created = candidate.ensureDirectories(listOf("Sync", device, category.cloudFolder))
                                val markerKey = "folder_${sha256("${drive.address}|$device|${category.cloudFolder}")}"
                                val value = if (created) UUID.randomUUID().toString()
                                else state.getString(markerKey, null) ?: UUID.randomUUID().toString()
                                state.edit().putString(markerKey, value).apply()
                                value
                            }
                            candidateGenerations[category] = marker
                        }
                    } else {
                        val configuredDevice = inputData.getString(KEY_RESTORE_DEVICE)?.trim()
                            ?: AppSettings.restoreDevice(applicationContext).trim()
                        require(!configuredDevice.contains('/') && !configuredDevice.contains('\\')) { "Invalid backup device folder" }
                        val backups = candidate.listCloud(listOf("Sync"), force = true).filter { it.isDirectory }
                        val source = if (configuredDevice.isNotEmpty()) {
                            backups.firstOrNull { it.name == configuredDevice }
                        } else {
                            backups.firstOrNull { it.name == device } ?: backups.maxByOrNull { backup ->
                                runCatching {
                                    candidate.listCloud(backup.cloudSegments, force = true)
                                        .firstOrNull { it.name == BACKUP_COMPLETE_MARKER }
                                        ?.modified
                                }.getOrNull() ?: backup.modified
                            }
                        } ?: error("No matching device backup")
                        restoreDevice = source.name
                    }
                    selectedDrive = drive
                    syncClient = candidate
                    folderGenerations = candidateGenerations
                    break
                } catch (error: Exception) {
                    folderError = "${drive.name}: ${error.message ?: "server error"}"
                }
            }
            val drive = selectedDrive ?: return@withContext failure("Cannot create sync folders. $folderError", automatic)
            val client = requireNotNull(syncClient)
            val address = drive.address

            setForeground(foregroundInfo("Scanning selected data", 0, 0))
            val allowed = categories.filterTo(linkedSetOf()) { hasSyncCategoryPermission(applicationContext, it, direction) }
            val missing = categories - allowed
            if (direction == SyncDirection.CloudToDevice) {
                val outcome = restoreFromCloud(client, address, restoreDevice, allowed, missing)
                AppSettings.saveLastSyncStatus(applicationContext, outcome.summary, direction)
                val output = outcome.toData(direction)
                if (outcome.failed > 0) {
                    scheduleFollowingRun = false
                    return@withContext if (automatic) Result.retry() else Result.failure(output)
                }
                scheduleFollowingRun = true
                return@withContext Result.success(output)
            }
            val remoteFilesDeferred = async { remoteFileIndex(client, device, allowed) }
            val media = queryMedia(allowed)
            val downloads = if (SyncCategory.Downloads in allowed) queryDownloads() else emptyList()
            val preparationErrors = mutableListOf<String>()
            val exports = allowed.filter { it in EXPORT_CATEGORIES }.mapNotNull { category ->
                runCatching { createExport(category).also { temporaryExports += it.file } }
                    .onFailure { preparationErrors += "${category.label}: ${it.message ?: "could not read data"}" }
                    .getOrNull()
            }
            val remoteFiles = remoteFilesDeferred.await()
            val totalItems = media.size + downloads.size + exports.size
            var done = 0
            var uploaded = 0
            var skipped = 0
            var failed = preparationErrors.size + missing.size
            var lastError: String? = preparationErrors.lastOrNull()
            suspend fun recordAttempts(attempts: List<UploadAttempt>) {
                attempts.forEach { attempt ->
                    when {
                        attempt.skipped -> skipped++
                        attempt.error != null -> {
                            failed++
                            lastError = attempt.error
                        }
                        else -> {
                            state.edit().putString(attempt.stateKey, attempt.signature).apply()
                            remoteFiles.getValue(attempt.category) += attempt.remoteKey
                            uploaded++
                        }
                    }
                }
                done += attempts.size
                publishProgress(done, totalItems, uploaded)
            }

            media.chunked(SYNC_TRANSFER_CONCURRENCY).forEach { batch ->
                ensureCanContinue()?.let {
                    scheduleFollowingRun = false
                    return@withContext it
                }
                val attempts = coroutineScope {
                    batch.map { item ->
                        async {
                            val remoteName = sanitizeCloudSegment(appendStableId(item.name, item.uri.lastPathSegment.orEmpty()))
                            val category = item.category
                            val relativeDirectories = cloudRelativeSegments(item.relativePath)
                            val remoteDirectory = listOf("Sync", device, category.cloudFolder) + relativeDirectories
                            val remotePath = remoteDirectory + remoteName
                            val originalAccess = Build.VERSION.SDK_INT >= 29 && hasPermission(Manifest.permission.ACCESS_MEDIA_LOCATION)
                            val signature = "${folderGenerations[category]}:${item.generation}:${item.modified}:${item.size}:${relativeDirectories.joinToString("/")}:$remoteName:${item.mimeType}:original=$originalAccess"
                            val stateKey = "item_${sha256("$address|${item.uri}")}"
                            val remoteKey = remotePathKey(remotePath)
                            if (state.getString(stateKey, null) == signature && remoteKey in remoteFiles.getValue(category)) {
                                return@async UploadAttempt(category, remoteKey, stateKey, signature, skipped = true)
                            }
                            try {
                                val sourceUri = if (originalAccess) {
                                    MediaStore.setRequireOriginal(item.uri)
                                } else {
                                    item.uri
                                }
                                applicationContext.contentResolver.openInputStream(sourceUri)?.use { input ->
                                    client.upload(
                                        remoteDirectory,
                                        remoteName,
                                        item.mimeType,
                                        item.size,
                                        input,
                                        continueTransfer = { isOnWifi() },
                                    )
                                } ?: error("Cannot read ${item.name}")
                                UploadAttempt(category, remoteKey, stateKey, signature)
                            } catch (error: Exception) {
                                UploadAttempt(category, remoteKey, stateKey, signature, error = error.message ?: "Upload failed")
                            }
                        }
                    }.awaitAll()
                }
                recordAttempts(attempts)
            }

            downloads.chunked(SYNC_TRANSFER_CONCURRENCY).forEach { batch ->
                ensureCanContinue()?.let {
                    scheduleFollowingRun = false
                    return@withContext it
                }
                val attempts = coroutineScope {
                    batch.map { item ->
                        async {
                            val category = SyncCategory.Downloads
                            val downloadId = sha256((item.relativeDirectories + item.file.name).joinToString("/")).take(12)
                            val remoteName = sanitizeCloudSegment(appendStableId(item.file.name, downloadId))
                            val signature = "${folderGenerations[category]}:${item.file.lastModified()}:${item.file.length()}:${item.relativeDirectories.joinToString("/")}:$remoteName"
                            val stateKey = "item_${sha256("$address|download|${item.file.absolutePath}")}"
                            val remotePath = listOf("Sync", device, category.cloudFolder) + item.relativeDirectories + remoteName
                            val remoteKey = remotePathKey(remotePath)
                            if (state.getString(stateKey, null) == signature && remoteKey in remoteFiles.getValue(category)) {
                                return@async UploadAttempt(category, remoteKey, stateKey, signature, skipped = true)
                            }
                            try {
                                item.file.inputStream().use { input ->
                                    client.upload(
                                        listOf("Sync", device, category.cloudFolder) + item.relativeDirectories,
                                        remoteName,
                                        mimeType(item.file),
                                        item.file.length(),
                                        input,
                                        continueTransfer = { isOnWifi() },
                                    )
                                }
                                UploadAttempt(category, remoteKey, stateKey, signature)
                            } catch (error: Exception) {
                                UploadAttempt(category, remoteKey, stateKey, signature, error = error.message ?: "Upload failed")
                            }
                        }
                    }.awaitAll()
                }
                recordAttempts(attempts)
            }

            exports.chunked(SYNC_TRANSFER_CONCURRENCY).forEach { batch ->
                ensureCanContinue()?.let {
                    scheduleFollowingRun = false
                    return@withContext it
                }
                val attempts = coroutineScope {
                    batch.map { item ->
                        async {
                            val signature = "${folderGenerations[item.category]}:${item.signature}"
                            val stateKey = "item_${sha256("$address|export|${item.category.name}")}"
                            val remotePath = listOf("Sync", device, item.category.cloudFolder, item.fileName)
                            val remoteKey = remotePathKey(remotePath)
                            if (state.getString(stateKey, null) == signature && remoteKey in remoteFiles.getValue(item.category)) {
                                return@async UploadAttempt(item.category, remoteKey, stateKey, signature, skipped = true)
                            }
                            try {
                                item.file.inputStream().use { input ->
                                    client.upload(
                                        listOf("Sync", device, item.category.cloudFolder),
                                        item.fileName,
                                        "application/json",
                                        item.file.length(),
                                        input,
                                        continueTransfer = { isOnWifi() },
                                    )
                                }
                                UploadAttempt(item.category, remoteKey, stateKey, signature)
                            } catch (error: Exception) {
                                UploadAttempt(item.category, remoteKey, stateKey, signature, error = error.message ?: "Upload failed")
                            }
                        }
                    }.awaitAll()
                }
                recordAttempts(attempts)
            }

            if (failed == 0) {
                runCatching {
                    val marker = System.currentTimeMillis().toString().toByteArray()
                    marker.inputStream().use { input ->
                        client.upload(listOf("Sync", device), BACKUP_COMPLETE_MARKER, "text/plain", marker.size.toLong(), input)
                    }
                }.onFailure {
                    failed++
                    lastError = "Could not update backup completion marker: ${it.message ?: "server error"}"
                }
            }

            val summary = buildString {
                append("Uploaded $uploaded, current $skipped, failed $failed")
                if (missing.isNotEmpty()) append(". Permission needed: ${missing.joinToString { it.label }}")
                if (lastError != null) append(". $lastError")
            }
            AppSettings.saveLastSyncStatus(applicationContext, summary, direction)
            val output = Data.Builder()
                .putInt(KEY_UPLOADED, uploaded)
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
            } catch (error: Exception) {
                failure("Sync failed: ${error.message ?: "unexpected error"}", automatic)
            }
        } finally {
            temporaryExports.forEach { it.delete() }
            syncLock.release()
            lockChannel.close()
            if (automatic && scheduleFollowingRun) AppSettings.scheduleNext(applicationContext, generation)
        }
    }

    private suspend fun remoteFileIndex(
        client: DavClient,
        device: String,
        categories: Set<SyncCategory>,
    ): Map<SyncCategory, MutableSet<String>> = coroutineScope {
        val result = linkedMapOf<SyncCategory, MutableSet<String>>()
        categories.groupBy(SyncCategory::cloudFolder).entries.chunked(SYNC_TRANSFER_CONCURRENCY).forEach { batch ->
            batch.map { (folder, groupedCategories) ->
                async {
                    val base = listOf("Sync", device, folder)
                    val keys = linkedSetOf<String>()
                    client.forEachCloudTree(base, force = true) { entry ->
                        if (!entry.isDirectory) keys += remotePathKey(entry.cloudSegments)
                    }
                    groupedCategories to keys
                }
            }.awaitAll().forEach { (groupedCategories, keys) ->
                groupedCategories.forEach { category -> result[category] = keys }
            }
        }
        result
    }

    private fun remotePathKey(segments: List<String>) = segments.joinToString("\u0000")

    private suspend fun publishProgress(done: Int, total: Int, uploaded: Int) {
        val now = SystemClock.elapsedRealtime()
        if (done != total && now - lastProgressUpdate < 250) return
        lastProgressUpdate = now
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
        isStopped -> Result.failure()
        !isOnWifi() -> Result.retry()
        else -> null
    }

    private fun queryMedia(categories: Set<SyncCategory>): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        if (SyncCategory.Photos in categories && canReadImages()) {
            queryCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, SyncCategory.Photos, items)
        }
        if (SyncCategory.Videos in categories && canReadVideos()) {
            queryCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, SyncCategory.Videos, items)
        }
        return items.sortedBy { it.modified }
    }

    private fun queryCollection(collection: Uri, category: SyncCategory, output: MutableList<MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
        ) + if (Build.VERSION.SDK_INT >= 30) arrayOf(MediaStore.MediaColumns.GENERATION_MODIFIED) else emptyArray()
        applicationContext.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.SIZE} > 0 AND ${MediaStore.MediaColumns.RELATIVE_PATH} NOT LIKE ?",
            arrayOf("%CloudDrive Restore/%"),
            "${MediaStore.MediaColumns.DATE_MODIFIED} ASC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val pathColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
            val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val mimeColumn = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val generationColumn = if (Build.VERSION.SDK_INT >= 30) cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.GENERATION_MODIFIED) else -1
            while (cursor.moveToNext()) {
                output += MediaItem(
                    uri = ContentUris.withAppendedId(collection, cursor.getLong(idColumn)),
                    name = cursor.getString(nameColumn) ?: "media_${cursor.getLong(idColumn)}",
                    relativePath = cursor.getString(pathColumn) ?: category.label,
                    modified = cursor.getLong(modifiedColumn),
                    generation = if (generationColumn >= 0) cursor.getLong(generationColumn) else 0,
                    size = cursor.getLong(sizeColumn),
                    mimeType = cursor.getString(mimeColumn) ?: "application/octet-stream",
                    category = category,
                )
            }
        }
    }

    private fun queryDownloads(): List<DownloadItem> {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!root.isDirectory || !root.canRead()) return emptyList()
        return root.walkTopDown()
            .onEnter { it.canRead() }
            .filter { it.isFile && it.canRead() && !it.relativeTo(root).invariantSeparatorsPath.startsWith("CloudDrive Restore/") }
            .map { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath.substringBeforeLast('/', "")
                DownloadItem(file, relative.split('/').filter(String::isNotEmpty))
            }
            .sortedBy { it.file.absolutePath }
            .toList()
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
        address: String,
        device: String,
        categories: Set<SyncCategory>,
        missing: Set<SyncCategory>,
    ): SyncOutcome {
        val tasks = mutableListOf<RestoreTask>()
        val errors = mutableListOf<String>()
        val mediaCategories = categories.intersect(MEDIA_CATEGORIES)
        if (mediaCategories.isNotEmpty()) {
            val mediaBase = listOf("Sync", device, SyncCategory.Photos.cloudFolder)
            runCatching {
                collectFileRestoreTasks(client, mediaBase, mediaCategories)
            }.onSuccess { mediaTasks ->
                tasks += mediaTasks
                val presentCategories = mediaTasks.mapTo(mutableSetOf(), RestoreTask::category)
                mediaCategories.filterNot { it in presentCategories }.forEach { category ->
                    val legacyBase = listOf("Sync", device, category.folder)
                    runCatching { collectFileRestoreTasks(client, legacyBase, setOf(category), listOf(category.label)) }
                        .onSuccess(tasks::addAll)
                        .onFailure {
                            if (it !is DavException || it.status != 404) {
                                errors += "${category.label}: ${it.message ?: "backup folder unavailable"}"
                            }
                        }
                }
            }.onFailure { error ->
                if (error is DavException && error.status == 404) {
                    mediaCategories.forEach { category ->
                        val legacyBase = listOf("Sync", device, category.folder)
                        runCatching { collectFileRestoreTasks(client, legacyBase, setOf(category), listOf(category.label)) }
                            .onSuccess(tasks::addAll)
                            .onFailure { errors += "${category.label}: ${it.message ?: "backup folder unavailable"}" }
                    }
                } else {
                    mediaCategories.forEach { category ->
                        errors += "${category.label}: ${error.message ?: "backup folder unavailable"}"
                    }
                }
            }
        }
        categories.filterNot { it in MEDIA_CATEGORIES }.forEach { category ->
            val base = listOf("Sync", device, category.cloudFolder)
            runCatching {
                if (category in EXPORT_CATEGORIES) {
                    var newest: BrowserEntry? = null
                    client.forEachCloudTree(base, force = true) { entry ->
                        if (!entry.isDirectory && entry.cloudSegments.size == base.size + 1 && entry.name.endsWith(".json", true)) {
                            val current = newest
                            if (current == null || entry.modified > current.modified ||
                                (entry.modified == current.modified && entry.name > current.name)) newest = entry
                        }
                    }
                    val snapshot = newest ?: error("No ${category.label} backup snapshot found")
                    tasks += RestoreTask(category, snapshot, emptyList(), export = true)
                } else {
                    tasks += collectFileRestoreTasks(client, base, setOf(category))
                }
            }.onFailure { errors += "${category.label}: ${it.message ?: "backup folder unavailable"}" }
        }

        var restored = 0
        var skipped = 0
        var failed = errors.size + missing.size
        var done = 0
        var lastError = errors.lastOrNull()
        tasks.forEach { task ->
            ensureCanContinue()?.let { return SyncOutcome(restored, skipped, failed + 1, tasks.size, "Restore interrupted") }
            try {
                val signature = "${task.entry.modified}:${task.entry.size}:${task.entry.cloudSegments.joinToString("/")}"
                val stateKey = "restore_${sha256("$address|${task.entry.cloudSegments.joinToString("/")}")}"
                val target = if (task.export) null else restoreTarget(task)
                val targetStateKey = target?.let { "restore_target_${sha256(it.absolutePath)}" }
                val targetSignature = target?.takeIf(File::isFile)?.let {
                    "$stateKey|$signature|${it.length()}|${it.lastModified()}"
                }
                if (!task.export &&
                    targetSignature != null &&
                    state.getString(stateKey, null) == signature &&
                    state.getString(targetStateKey, null) == targetSignature
                ) {
                    skipped++
                } else if (task.export) {
                    val temporary = File.createTempFile("sync-restore-", ".json", applicationContext.cacheDir)
                    try {
                        temporary.outputStream().use { client.download(task.entry.cloudSegments, it) }
                        when (task.category) {
                            SyncCategory.Contacts -> restoreContacts(temporary, device)
                            SyncCategory.SmsMessages -> restoreSms(temporary)
                            SyncCategory.CallHistory -> restoreCalls(temporary)
                            else -> Unit
                        }
                    } finally {
                        temporary.delete()
                    }
                    state.edit().putString(stateKey, signature).apply()
                    restored++
                } else {
                    val outputFile = requireNotNull(target)
                    outputFile.parentFile?.mkdirs()
                    val temporary = File(outputFile.parentFile, ".${outputFile.name}.restore-${UUID.randomUUID()}")
                    try {
                        temporary.outputStream().use { client.download(task.entry.cloudSegments, it) }
                        runCatching {
                            Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                        }.getOrElse {
                            Files.move(temporary.toPath(), outputFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temporary.delete()
                    }
                    if (task.category in setOf(SyncCategory.Photos, SyncCategory.Videos)) {
                        MediaScannerConnection.scanFile(applicationContext, arrayOf(outputFile.absolutePath), null, null)
                    }
                    val restoredTargetSignature = "$stateKey|$signature|${outputFile.length()}|${outputFile.lastModified()}"
                    state.edit()
                        .putString(stateKey, signature)
                        .putString(requireNotNull(targetStateKey), restoredTargetSignature)
                        .apply()
                    restored++
                }
            } catch (error: Exception) {
                failed++
                lastError = "${task.category.label}: ${error.message ?: "restore failed"}"
            }
            done++
            publishProgress(done, tasks.size, restored)
        }
        val summary = buildString {
            append("Restored $restored, current $skipped, failed $failed")
            if (missing.isNotEmpty()) append(". Permission needed: ${missing.joinToString { it.label }}")
            if (lastError != null) append(". $lastError")
        }
        return SyncOutcome(restored, skipped, failed, tasks.size, summary)
    }

    private fun restoreTarget(task: RestoreTask): File {
        val safeName = requireRestoreSegment(task.entry.name)
        val requestedDirectories = task.relativeDirectories.map(::requireRestoreSegment)
        val (root, directories) = when (task.category) {
            SyncCategory.Downloads -> Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS) to
                requestedDirectories.dropWhile { it.equals("Download", true) || it.equals("Downloads", true) }
            SyncCategory.Photos -> Environment.getExternalStorageDirectory() to
                requestedDirectories.ifEmpty { listOf("Photos") }
            SyncCategory.Videos -> Environment.getExternalStorageDirectory() to
                requestedDirectories.ifEmpty { listOf("Videos") }
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
        val rawContacts = mutableMapOf<String, Pair<Long, Boolean>>()
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "raw_contacts" -> forEachRow(reader) { row ->
                        val oldId = row[ContactsContract.RawContacts._ID]?.toString() ?: return@forEachRow
                        val sourceId = "clouddrive:$sourceDevice:$oldId"
                        val existing = findRawContact(sourceId)
                        if (existing != null) {
                            rawContacts[oldId] = existing to true
                        } else {
                            val values = ContentValues().apply { put(ContactsContract.RawContacts.SOURCE_ID, sourceId) }
                            val uri = applicationContext.contentResolver.insert(ContactsContract.RawContacts.CONTENT_URI, values)
                                ?: error("Cannot create contact")
                            rawContacts[oldId] = ContentUris.parseId(uri) to true
                        }
                    }
                    "data" -> {
                        var currentRawId: String? = null
                        val rows = mutableListOf<Map<String, Any?>>()
                        fun flush() {
                            val rawId = currentRawId ?: return
                            rawContacts[rawId]?.let { mapped -> replaceContactData(mapped.first, rows) }
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
    }

    private fun replaceContactData(rawContactId: Long, rows: List<Map<String, Any?>>) {
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

    private fun restoreSms(file: File) {
        val occurrences = mutableMapOf<String, Int>()
        val existingCounts = mutableMapOf<String, Int>()
        forEachNamedRow(file, "messages") { row ->
            val date = row[Telephony.Sms.DATE]?.toString()?.toLongOrNull() ?: return@forEachNamedRow
            val type = row[Telephony.Sms.TYPE]?.toString()?.toIntOrNull() ?: 0
            val address = row[Telephony.Sms.ADDRESS]?.toString()
            val body = row[Telephony.Sms.BODY]?.toString()
            val hasSubscription = row.containsKey(Telephony.Sms.SUBSCRIPTION_ID)
            val subscriptionId = row[Telephony.Sms.SUBSCRIPTION_ID]?.toString()?.toIntOrNull()
            val smsSelection = buildString {
                append("${Telephony.Sms.DATE}=? AND ${Telephony.Sms.TYPE}=?")
                append(if (address == null) " AND ${Telephony.Sms.ADDRESS} IS NULL" else " AND ${Telephony.Sms.ADDRESS}=?")
                append(if (body == null) " AND ${Telephony.Sms.BODY} IS NULL" else " AND ${Telephony.Sms.BODY}=?")
                if (hasSubscription) append(if (subscriptionId == null) " AND ${Telephony.Sms.SUBSCRIPTION_ID} IS NULL" else " AND ${Telephony.Sms.SUBSCRIPTION_ID}=?")
            }
            val smsArgs = buildList {
                add(date.toString()); add(type.toString())
                if (address != null) add(address)
                if (body != null) add(body)
                if (hasSubscription && subscriptionId != null) add(subscriptionId.toString())
            }.toTypedArray()
            val identity = "$date|$type|${address.orEmpty()}|${body.orEmpty()}|${if (hasSubscription) subscriptionId else "legacy"}"
            val occurrence = occurrences.merge(identity, 1, Int::plus) ?: 1
            val existing = existingCounts.getOrPut(identity) { providerRowCount(Telephony.Sms.CONTENT_URI, smsSelection, smsArgs) }
            if (existing >= occurrence) return@forEachNamedRow
            val values = ContentValues().apply {
                putNullable(Telephony.Sms.ADDRESS, address)
                put(Telephony.Sms.DATE, date)
                putValue(this, Telephony.Sms.DATE_SENT, row[Telephony.Sms.DATE_SENT])
                put(Telephony.Sms.TYPE, type)
                putNullable(Telephony.Sms.BODY, body)
                putValue(this, Telephony.Sms.READ, row[Telephony.Sms.READ])
                putValue(this, Telephony.Sms.SEEN, row[Telephony.Sms.SEEN])
                if (hasSubscription) putValue(this, Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            }
            applicationContext.contentResolver.insert(Telephony.Sms.CONTENT_URI, values) ?: error("Cannot restore SMS")
        }
    }

    private fun restoreCalls(file: File) {
        val occurrences = mutableMapOf<String, Int>()
        val existingCounts = mutableMapOf<String, Int>()
        forEachNamedRow(file, "calls") { row ->
            val date = row[CallLog.Calls.DATE]?.toString()?.toLongOrNull() ?: return@forEachNamedRow
            val number = row[CallLog.Calls.NUMBER]?.toString()
            val duration = row[CallLog.Calls.DURATION]?.toString()?.toLongOrNull() ?: 0
            val type = row[CallLog.Calls.TYPE]?.toString()?.toIntOrNull() ?: 0
            val callSelection = "${CallLog.Calls.DATE}=? AND ${CallLog.Calls.DURATION}=? AND ${CallLog.Calls.TYPE}=?" +
                if (number == null) " AND ${CallLog.Calls.NUMBER} IS NULL" else " AND ${CallLog.Calls.NUMBER}=?"
            val callArgs = buildList {
                add(date.toString()); add(duration.toString()); add(type.toString())
                if (number != null) add(number)
            }.toTypedArray()
            val identity = "$date|$duration|$type|${number.orEmpty()}"
            val occurrence = occurrences.merge(identity, 1, Int::plus) ?: 1
            val existing = existingCounts.getOrPut(identity) { providerRowCount(CallLog.Calls.CONTENT_URI, callSelection, callArgs) }
            if (existing >= occurrence) return@forEachNamedRow
            val values = ContentValues().apply {
                putNullable(CallLog.Calls.NUMBER, number)
                put(CallLog.Calls.DATE, date)
                put(CallLog.Calls.DURATION, duration)
                put(CallLog.Calls.TYPE, type)
                putNullable(CallLog.Calls.CACHED_NAME, row[CallLog.Calls.CACHED_NAME]?.toString())
                putNullable(CallLog.Calls.COUNTRY_ISO, row[CallLog.Calls.COUNTRY_ISO]?.toString())
                putNullable(CallLog.Calls.GEOCODED_LOCATION, row[CallLog.Calls.GEOCODED_LOCATION]?.toString())
                putValue(this, CallLog.Calls.NEW, row[CallLog.Calls.NEW])
                putValue(this, CallLog.Calls.IS_READ, row[CallLog.Calls.IS_READ])
            }
            applicationContext.contentResolver.insert(CallLog.Calls.CONTENT_URI, values) ?: error("Cannot restore call history")
        }
    }

    private fun forEachNamedRow(file: File, arrayName: String, action: (Map<String, Any?>) -> Unit) {
        JsonReader(InputStreamReader(file.inputStream(), Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                if (reader.nextName() == arrayName) forEachRow(reader, action) else reader.skipValue()
            }
            reader.endObject()
        }
    }

    private fun forEachRow(reader: JsonReader, action: (Map<String, Any?>) -> Unit) {
        reader.beginArray()
        while (reader.hasNext()) {
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
            action(row)
        }
        reader.endArray()
    }

    private fun findRawContact(sourceId: String): Long? = applicationContext.contentResolver.query(
        ContactsContract.RawContacts.CONTENT_URI,
        arrayOf(ContactsContract.RawContacts._ID),
        "${ContactsContract.RawContacts.SOURCE_ID}=?",
        arrayOf(sourceId),
        null,
    )?.use { if (it.moveToFirst()) it.getLong(0) else null }

    private fun providerRowCount(uri: Uri, selection: String, args: Array<String>): Int =
        applicationContext.contentResolver.query(uri, arrayOf("_id"), selection, args, null)?.use { it.count } ?: 0

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
            JsonWriter(OutputStreamWriter(file.outputStream(), Charsets.UTF_8)).use { writer ->
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
            val signature = sha256(file)
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
        applicationContext.contentResolver.query(uri, columns, null, null, sort)?.use { cursor ->
            while (cursor.moveToNext()) {
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

    private fun hasPermission(permission: String) =
        ContextCompat.checkSelfPermission(applicationContext, permission) == PackageManager.PERMISSION_GRANTED

    private fun foregroundInfo(message: String, done: Int, total: Int): ForegroundInfo {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Data sync", NotificationManager.IMPORTANCE_LOW))
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

    private fun mimeType(file: File): String = MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"

    private fun sha256(value: String): String = sha256(value.toByteArray())

    private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(value)
        .joinToString("") { "%02x".format(it) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE * 16)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

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
        val category: SyncCategory,
    )

    private data class DownloadItem(val file: File, val relativeDirectories: List<String>)
    private data class ExportItem(val category: SyncCategory, val fileName: String, val file: File, val signature: String)
    private data class UploadAttempt(
        val category: SyncCategory,
        val remoteKey: String,
        val stateKey: String,
        val signature: String,
        val skipped: Boolean = false,
        val error: String? = null,
    )
    private data class RestoreTask(
        val category: SyncCategory,
        val entry: BrowserEntry,
        val relativeDirectories: List<String>,
        val export: Boolean,
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
        private const val BACKUP_COMPLETE_MARKER = ".backup-complete"
        private val MEDIA_CATEGORIES = setOf(SyncCategory.Photos, SyncCategory.Videos)
        private val EXPORT_CATEGORIES = setOf(SyncCategory.Contacts, SyncCategory.SmsMessages, SyncCategory.CallHistory)
    }
}
