package com.minosuko.clouddrive

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

data class FileBrowserState(
    val source: BrowserSource = BrowserSource.CloudDrive,
    val drives: List<DriveProfile> = emptyList(),
    val activeDriveId: String? = null,
    val cloudPath: List<String> = emptyList(),
    val deviceStack: List<String> = emptyList(),
    val items: List<BrowserEntry> = emptyList(),
    val loading: Boolean = false,
    val operationRunning: Boolean = false,
    val transferProgress: FileTransferProgress? = null,
    val error: String? = null,
    val message: String? = null,
    val query: String = "",
    val sort: FileSort = FileSort.Name,
    val layout: FileLayout = FileLayout.List,
    val clipboard: FileClipboard? = null,
    val viewer: ViewerContent? = null,
) {
    val visibleItems: List<BrowserEntry>
        get() {
            val filtered = if (query.isBlank()) items else items.filter { it.name.contains(query, true) }
            val comparator = when (sort) {
                FileSort.Name -> Comparator<BrowserEntry> { left, right -> left.name.compareTo(right.name, ignoreCase = true) }
                FileSort.Modified -> compareByDescending { it.modified }
                FileSort.Size -> compareByDescending { it.size }
            }
            return filtered.sortedWith(compareByDescending<BrowserEntry> { it.isDirectory }.then(comparator))
        }
}

data class FileTransferProgress(
    val label: String,
    val completedBytes: Long = 0,
    val totalBytes: Long? = null,
)

class FileBrowserViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val deviceStore = DeviceFileStore(context)
    private val mutableState = MutableStateFlow(
        FileBrowserState(deviceStack = listOf(deviceRootPath())),
    )
    val state: StateFlow<FileBrowserState> = mutableState.asStateFlow()
    private var loadJob: Job? = null
    private var prefetchJob: Job? = null
    private var lastProgressUpdate = 0L

    fun reloadDrives() {
        val drives = AppSettings.drives(context)
        mutableState.update { state ->
            val active = state.activeDriveId?.takeIf { id -> drives.any { it.id == id } }
            state.copy(drives = drives, activeDriveId = active)
        }
        refresh()
    }

    fun addDrive(name: String, address: String, username: String, password: String, createAccount: Boolean) {
        if (mutableState.value.operationRunning) return
        viewModelScope.launch {
            mutableState.update { it.copy(operationRunning = true, error = null, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val normalized = AppSettings.normalizeAddress(address)
                    val (origin, session) = MobileApiClient.connect(context, normalized, username, password, createAccount)
                    val profile = AppSettings.addDrive(context, name, normalized)
                    try {
                        AccountStore.save(context, profile.id, origin, session)
                        profile
                    } catch (error: Exception) {
                        AppSettings.removeDrive(context, profile.id)
                        throw error
                    }
                }
            }.onSuccess { profile ->
                mutableState.update {
                    it.copy(
                        drives = AppSettings.drives(context),
                        activeDriveId = profile.id,
                        cloudPath = emptyList(),
                        operationRunning = false,
                        message = "${profile.name} connected",
                    )
                }
                AppSettings.schedule(context, replace = true)
                refresh()
            }.onFailure { error ->
                mutableState.update { it.copy(operationRunning = false, error = error.message ?: "Could not connect account") }
            }
        }
    }

    fun signInToDrive(profileId: String, username: String, password: String, createAccount: Boolean) {
        if (mutableState.value.operationRunning) return
        val drive = mutableState.value.drives.firstOrNull { it.id == profileId } ?: return
        viewModelScope.launch {
            mutableState.update { it.copy(operationRunning = true, error = null, message = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val (origin, session) = MobileApiClient.connect(context, drive.address, username, password, createAccount)
                    DavClient.clearCache(drive.address)
                    AccountStore.save(context, drive.id, origin, session)
                    session
                }
            }.onSuccess { session ->
                mutableState.update { state ->
                    val active = state.activeDriveId == drive.id
                    state.copy(
                        cloudPath = if (active) emptyList() else state.cloudPath,
                        items = if (active) emptyList() else state.items,
                        clipboard = state.clipboard?.takeUnless { it.entry.driveProfileId == drive.id },
                        viewer = null,
                        operationRunning = false,
                        error = null,
                        message = "${session.displayName} signed in to ${drive.name}",
                    )
                }
                refresh()
            }.onFailure { error ->
                mutableState.update { it.copy(operationRunning = false, error = error.message ?: "Could not sign in") }
            }
        }
    }

    fun disconnectDrive(id: String) {
        val profile = mutableState.value.drives.firstOrNull { it.id == id } ?: return
        AppSettings.removeDrive(context, id)
        AccountStore.remove(context, id)
        DavClient.clearCache(profile.address)
        AppSettings.schedule(context, replace = true)
        val drives = AppSettings.drives(context)
        mutableState.update { state ->
            val disconnectedActive = state.activeDriveId == id
            state.copy(
                drives = drives,
                activeDriveId = if (disconnectedActive) null else state.activeDriveId,
                cloudPath = if (disconnectedActive) emptyList() else state.cloudPath,
                items = if (disconnectedActive) emptyList() else state.items,
                clipboard = state.clipboard?.takeUnless { it.entry.driveProfileId == id },
                message = "${profile.name} disconnected",
            )
        }
        refresh()
    }

    fun setSource(source: BrowserSource) {
        if (mutableState.value.source == source) return
        prefetchJob?.cancel()
        mutableState.update { it.copy(source = source, items = emptyList(), error = null) }
        refresh()
    }

    fun enableDeviceRoot() {
        mutableState.update { it.copy(source = BrowserSource.Device, deviceStack = listOf(deviceRootPath())) }
        refresh()
    }

    fun open(entry: BrowserEntry) {
        prefetchJob?.cancel()
        if (entry.isDirectory) {
            mutableState.update {
                if (entry.driveRoot) it.copy(activeDriveId = entry.driveProfileId, cloudPath = emptyList(), items = emptyList(), query = "")
                else if (entry.source == BrowserSource.CloudDrive) it.copy(cloudPath = entry.cloudSegments, items = emptyList(), query = "")
                else it.copy(deviceStack = it.deviceStack + requireNotNull(entry.devicePath), items = emptyList(), query = "")
            }
            refresh()
        } else {
            val viewer = createViewer(entry)
            mutableState.update {
                it.copy(viewer = viewer, message = if (viewer == null) "No viewer for this file type" else null)
            }
        }
    }

    fun up() {
        prefetchJob?.cancel()
        mutableState.update {
            if (it.source == BrowserSource.CloudDrive && it.cloudPath.isEmpty()) it.copy(activeDriveId = null, items = emptyList(), query = "")
            else if (it.source == BrowserSource.CloudDrive) it.copy(cloudPath = it.cloudPath.dropLast(1), items = emptyList(), query = "")
            else it.copy(deviceStack = it.deviceStack.dropLast(1), items = emptyList(), query = "")
        }
        refresh()
    }

    fun refresh() = load(force = false)

    fun forceRefresh() = load(force = true)

    private fun load(force: Boolean) {
        loadJob?.cancel()
        val requestedLocation = mutableState.value.locationKey()
        loadJob = viewModelScope.launch {
            mutableState.update { it.copy(loading = it.items.isEmpty(), error = null) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val current = mutableState.value
                    if (current.source == BrowserSource.CloudDrive) {
                        val drive = current.drives.firstOrNull { it.id == current.activeDriveId }
                        if (drive == null) {
                            current.drives.map { profile ->
                                BrowserEntry(
                                    source = BrowserSource.CloudDrive,
                                    name = profile.name,
                                    isDirectory = true,
                                    size = 0,
                                    mimeType = "httpd/unix-directory",
                                    driveProfileId = profile.id,
                                    driveRoot = true,
                                )
                            }
                        } else {
                            val client = davClient(context, drive)
                            val cached = if (force) null else client.cachedCloud(current.cloudPath)
                            val decoratedCached = cached?.let { decorateCloud(it, drive, client) }
                            if (decoratedCached != null) {
                                mutableState.update { state ->
                                    if (state.locationKey() == requestedLocation) state.copy(items = decoratedCached, loading = false) else state
                                }
                            }
                            val listed = client.listCloud(current.cloudPath, force)
                            if (listed === cached) decoratedCached.orEmpty() else decorateCloud(listed, drive, client)
                        }
                    } else {
                        val uri = current.deviceStack.lastOrNull() ?: return@withContext emptyList()
                        deviceStore.list(uri)
                    }
                }
            }.onSuccess { items ->
                mutableState.update {
                    if (it.locationKey() == requestedLocation) it.copy(items = items, loading = false) else it
                }
                prefetchCloudDirectories(requestedLocation, items)
            }.onFailure { error ->
                if (error !is CancellationException) mutableState.update {
                    if (it.locationKey() == requestedLocation) it.copy(loading = false, error = error.message) else it
                }
            }
        }
    }

    fun upload(uris: List<Uri>) = operation("Upload complete") {
        val client = davClient(context, activeDrive())
        val path = mutableState.value.cloudPath
        val results = supervisorScope {
            val permits = Semaphore(3)
            uris.map { uri ->
                async {
                    permits.withPermit {
                        runCatching { uploadDocument(client, path, uri) }
                    }
                }
            }.awaitAll()
        }
        val errors = results.mapNotNull { it.exceptionOrNull()?.message }
        if (errors.isNotEmpty()) error("${results.size - errors.size} uploaded, ${errors.size} failed. ${errors.first()}")
    }

    fun create(name: String, directory: Boolean) = operation(if (directory) "Folder created" else "File created") {
        val current = mutableState.value
        if (current.source == BrowserSource.CloudDrive) {
            val client = davClient(context, activeDrive())
            if (directory) client.createDirectory(current.cloudPath, name) else client.createFile(current.cloudPath, name)
        } else {
            val parent = current.deviceStack.last()
            if (directory) deviceStore.createDirectory(parent, name) else deviceStore.createFile(parent, name)
        }
    }

    fun delete(entry: BrowserEntry) = operation(
        if (entry.source == BrowserSource.CloudDrive) "${entry.name} moved to Trash" else "${entry.name} deleted",
    ) {
        require(!entry.driveRoot) { "A connected CloudDrive cannot be deleted here" }
        if (entry.source == BrowserSource.CloudDrive) {
            davClient(context, driveFor(entry)).moveToTrash(entry.cloudSegments)
        } else {
            deviceStore.delete(requireNotNull(entry.devicePath))
        }
        mutableState.update { state ->
            state.copy(clipboard = state.clipboard?.takeUnless { it.entry.id == entry.id })
        }
    }

    fun putClipboard(entry: BrowserEntry, action: ClipboardAction) {
        mutableState.update { it.copy(clipboard = FileClipboard(action, entry), message = "${entry.name} ready to paste") }
    }

    fun clearClipboard() = mutableState.update { it.copy(clipboard = null) }

    fun paste() {
        val current = mutableState.value
        if (current.operationRunning) return
        val clipboard = current.clipboard ?: return
        val destination = PasteDestination(
            source = current.source,
            cloudDrive = current.drives.firstOrNull { it.id == current.activeDriveId },
            cloudPath = current.cloudPath.toList(),
            devicePath = current.deviceStack.lastOrNull(),
            sourceDrive = current.drives.firstOrNull { it.id == clipboard.entry.driveProfileId },
        )
        val verb = if (clipboard.action == ClipboardAction.Cut) "Moving" else "Copying"
        viewModelScope.launch {
            lastProgressUpdate = 0L
            mutableState.update {
                it.copy(operationRunning = true, transferProgress = FileTransferProgress("$verb ${clipboard.entry.name}"), error = null)
            }
            runCatching { withContext(Dispatchers.IO) { performPaste(clipboard, destination) } }
                .onSuccess {
                    mutableState.update {
                        it.copy(
                            operationRunning = false,
                            transferProgress = null,
                            clipboard = if (it.clipboard == clipboard) null else it.clipboard,
                            message = "Paste complete",
                        )
                    }
                    forceRefresh()
                }
                .onFailure { error ->
                    mutableState.update { it.copy(operationRunning = false, transferProgress = null, message = error.message) }
                }
        }
    }

    fun setQuery(query: String) = mutableState.update { it.copy(query = query) }
    fun setSort(sort: FileSort) = mutableState.update { it.copy(sort = sort) }
    fun toggleLayout() = mutableState.update {
        it.copy(layout = if (it.layout == FileLayout.List) FileLayout.Grid else FileLayout.List)
    }
    fun closeViewer() = mutableState.update { it.copy(viewer = null) }
    fun consumeMessage() = mutableState.update { it.copy(message = null) }

    private fun operation(successMessage: String, block: suspend () -> Unit) {
        if (mutableState.value.operationRunning) return
        viewModelScope.launch {
            mutableState.update { it.copy(operationRunning = true, transferProgress = null, error = null) }
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    mutableState.update { it.copy(operationRunning = false, transferProgress = null, message = successMessage) }
                    forceRefresh()
                }
                .onFailure { error ->
                    mutableState.update { it.copy(operationRunning = false, transferProgress = null, message = error.message) }
                    forceRefresh()
                }
        }
    }

    private fun uploadDocument(client: DavClient, path: List<String>, uri: Uri) {
        val metadata = documentMetadata(uri)
        if (metadata.third != null) {
            context.contentResolver.openInputStream(uri)?.use { input ->
                client.upload(path, metadata.first, metadata.second, metadata.third, input, overwrite = false)
            } ?: error("Cannot read ${metadata.first}")
            return
        }
        val temporary = File.createTempFile("clouddrive-upload-", ".tmp", context.cacheDir)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().use { output -> input.copyTo(output, 256 * 1024) }
            } ?: error("Cannot read ${metadata.first}")
            temporary.inputStream().use { input ->
                client.upload(path, metadata.first, metadata.second, temporary.length(), input, overwrite = false)
            }
        } finally {
            temporary.delete()
        }
    }

    private fun documentMetadata(uri: Uri): Triple<String, String, Long?> {
        var name = "Uploaded file"
        var size: Long? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameColumn >= 0) name = cursor.getString(nameColumn) ?: name
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn).takeIf { it >= 0 }
            }
        }
        return Triple(name, context.contentResolver.getType(uri) ?: "application/octet-stream", size)
    }

    private fun createViewer(item: BrowserEntry): ViewerContent? {
        val mimeType = item.mimeType.lowercase()
        val image = mimeType.startsWith("image/") || item.extension in IMAGE_EXTENSIONS
        val video = mimeType.startsWith("video/") || item.extension in VIDEO_EXTENSIONS
        val audio = mimeType.startsWith("audio/") || item.extension in AUDIO_EXTENSIONS
        val proprietary = item.extension in PROPRIETARY_EXTENSIONS
        val uri = when {
            item.source == BrowserSource.Device && (image || video || audio) -> item.deviceUri
            item.source == BrowserSource.CloudDrive && proprietary -> davClient(context, driveFor(item))
                .previewUrl(item.cloudSegments, item.modified, item.size)
            item.source == BrowserSource.CloudDrive && (image || video || audio) -> davClient(context, driveFor(item))
                .downloadUrl(item.cloudSegments, item.modified, item.size)
            else -> null
        } ?: return null
        val kind = when {
            video -> ViewerKind.Video
            audio -> ViewerKind.Audio
            else -> ViewerKind.Image
        }
        return ViewerContent(item.name, uri, kind, item.thumbnailUri, item.requestHeaders)
    }

    companion object {
        private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif", "svg")
        private val VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
        private val AUDIO_EXTENSIONS = setOf("mp3", "m4a", "aac", "wav", "flac", "ogg", "oga", "opus", "amr")
        private val PROPRIETARY_EXTENSIONS = setOf("psd", "psb", "sai", "sai2")
    }

    private fun activeDrive(): DriveProfile = mutableState.value.drives
        .firstOrNull { it.id == mutableState.value.activeDriveId }
        ?: error("Choose a CloudDrive first")

    private fun driveFor(entry: BrowserEntry): DriveProfile = mutableState.value.drives
        .firstOrNull { it.id == entry.driveProfileId }
        ?: activeDrive()

    private fun decorateCloud(items: List<BrowserEntry>, drive: DriveProfile, client: DavClient): List<BrowserEntry> {
        val requestHeaders = client.requestHeaders()
        return items.map { item ->
            val previewable = item.mimeType.startsWith("image/") || item.extension in IMAGE_EXTENSIONS ||
                item.extension in PROPRIETARY_EXTENSIONS
            item.copy(
                driveProfileId = drive.id,
                thumbnailUri = if (previewable) client.thumbnailUrl(item.cloudSegments, modified = item.modified, size = item.size) else null,
                requestHeaders = requestHeaders,
            )
        }
    }

    private data class PasteDestination(
        val source: BrowserSource,
        val cloudDrive: DriveProfile?,
        val cloudPath: List<String>,
        val devicePath: String?,
        val sourceDrive: DriveProfile?,
    )

    private fun performPaste(clipboard: FileClipboard, destination: PasteDestination) {
        when {
            clipboard.entry.source == BrowserSource.Device && destination.source == BrowserSource.CloudDrive -> {
                val source = File(requireNotNull(clipboard.entry.devicePath))
                val snapshot = deviceStore.snapshot(source.absolutePath)
                val total = snapshot.bytes
                reportTransfer(0, total)
                val client = davClient(context, requireNotNull(destination.cloudDrive) { "Choose a CloudDrive destination" })
                if (source.isDirectory) uploadDeviceDirectoryStaged(client, source, destination.cloudPath, total)
                else uploadDeviceEntry(client, source, destination.cloudPath, 0, total)
                if (clipboard.action == ClipboardAction.Cut) {
                    require(deviceStore.snapshot(source.absolutePath) == snapshot) { "Uploaded, but the source changed and was not removed" }
                    check(if (source.isDirectory) source.deleteRecursively() else source.delete()) { "Uploaded, but could not remove the local source" }
                }
            }
            clipboard.entry.source == BrowserSource.CloudDrive && destination.source == BrowserSource.Device -> {
                val client = davClient(context, requireNotNull(destination.sourceDrive) { "CloudDrive is no longer connected" })
                val tree = buildCloudTree(client, freshCloudEntry(client, clipboard.entry), force = true)
                val total = tree.totalBytes
                reportTransfer(0, total)
                val deviceDirectory = File(requireNotNull(destination.devicePath) { "Choose a device destination" })
                val target = File(deviceDirectory, clipboard.entry.name)
                require(!target.exists()) { "A file with this name already exists" }
                try {
                    downloadCloudEntry(client, tree, deviceDirectory, total)
                } catch (error: Exception) {
                    if (target.isDirectory) target.deleteRecursively() else target.delete()
                    throw error
                }
                if (clipboard.action == ClipboardAction.Cut) {
                    val currentTree = buildCloudTree(client, freshCloudEntry(client, clipboard.entry), force = true)
                    require(currentTree.fingerprint == tree.fingerprint) { "Downloaded, but the cloud source changed and was not removed" }
                    client.delete(clipboard.entry.cloudSegments)
                }
            }
            clipboard.entry.source == BrowserSource.CloudDrive && destination.source == BrowserSource.CloudDrive -> {
                val drive = requireNotNull(destination.cloudDrive) { "Choose a CloudDrive destination" }
                require(clipboard.entry.driveProfileId == drive.id) { "Paste within the same CloudDrive" }
                val total = clipboard.entry.size.takeIf { !clipboard.entry.isDirectory && it > 0 }
                reportTransfer(0, total)
                davClient(context, drive).paste(clipboard, destination.cloudPath)
                reportTransfer(total ?: 0, total)
            }
            clipboard.entry.source == BrowserSource.Device && destination.source == BrowserSource.Device -> {
                deviceStore.paste(clipboard, requireNotNull(destination.devicePath) { "Choose a device destination" }, ::reportTransfer)
            }
            else -> error("Choose a valid destination")
        }
    }

    private fun uploadDeviceEntry(client: DavClient, source: File, destination: List<String>, completedBefore: Long, total: Long): Long {
        require(source.exists() && source.canRead()) { "Cannot read ${source.name}" }
        if (source.isDirectory) {
            val target = destination + source.name
            client.createDirectory(destination, source.name)
            try {
                val children = source.listFiles() ?: error("Cannot read folder ${source.name}")
                var completed = completedBefore
                children.forEach { completed = uploadDeviceEntry(client, it, target, completed, total) }
                return completed
            } catch (error: Exception) {
                runCatching { client.delete(target) }
                throw error
            }
        } else {
            source.inputStream().use { input ->
                client.upload(destination, source.name, android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(source.extension.lowercase()) ?: "application/octet-stream", source.length(), input,
                    overwrite = false,
                    onProgress = { reportTransfer(completedBefore + it, total) },
                )
            }
            return completedBefore + source.length()
        }
    }

    private fun uploadDeviceDirectoryStaged(client: DavClient, source: File, destination: List<String>, total: Long) {
        val stagingName = ".clouddrive-stage-app-${java.util.UUID.randomUUID().toString().replace("-", "")}"
        val stagingPath = destination + stagingName
        client.createStagingDirectory(destination, stagingName)
        try {
            val children = source.listFiles() ?: error("Cannot read folder ${source.name}")
            var completed = 0L
            children.forEach { completed = uploadDeviceEntry(client, it, stagingPath, completed, total) }
            client.move(stagingPath, destination, source.name)
        } catch (error: Exception) {
            runCatching { client.deleteStaging(stagingPath) }
            throw error
        }
    }

    private data class CloudTransferPlan(val entry: BrowserEntry, val descendants: List<BrowserEntry>) {
        val totalBytes: Long = if (entry.isDirectory) descendants.filterNot(BrowserEntry::isDirectory).sumOf { it.size } else entry.size
        val fingerprint: String = buildString {
            append(entry.name).append('|').append(entry.isDirectory).append('|').append(entry.size).append('|').append(entry.modified)
            descendants.forEach {
                append('\n').append(it.cloudSegments.joinToString("/"))
                    .append('|').append(it.isDirectory).append('|').append(it.size).append('|').append(it.modified)
            }
        }
    }

    private fun freshCloudEntry(client: DavClient, entry: BrowserEntry): BrowserEntry = client
        .listCloud(entry.cloudSegments.dropLast(1), force = true)
        .firstOrNull { it.name == entry.name }
        ?: error("Cloud source no longer exists")

    private fun buildCloudTree(client: DavClient, entry: BrowserEntry, force: Boolean): CloudTransferPlan =
        CloudTransferPlan(entry, if (entry.isDirectory) client.listCloudTree(entry.cloudSegments, force) else emptyList())

    private fun downloadCloudEntry(
        client: DavClient,
        plan: CloudTransferPlan,
        destination: File,
        total: Long,
    ): Long {
        val target = File(destination, plan.entry.name)
        if (!plan.entry.isDirectory) {
            var downloaded = 0L
            target.outputStream().use { output ->
                client.download(plan.entry.cloudSegments, output) {
                    downloaded = it
                    reportTransfer(it, total)
                }
            }
            return downloaded
        }

        check(target.mkdir()) { "Cannot create folder ${target.name}" }
        plan.descendants.filter(BrowserEntry::isDirectory)
            .sortedBy { it.cloudSegments.size }
            .forEach { entry ->
                val relative = entry.cloudSegments.drop(plan.entry.cloudSegments.size)
                check(File(target, relative.joinToString(File.separator)).mkdirs()) { "Cannot create folder ${entry.name}" }
            }
        var completed = 0L
        plan.descendants.filterNot(BrowserEntry::isDirectory).forEach { entry ->
            val relative = entry.cloudSegments.drop(plan.entry.cloudSegments.size)
            val outputFile = File(target, relative.joinToString(File.separator))
            outputFile.parentFile?.mkdirs()
            var downloaded = 0L
            outputFile.outputStream().use { output ->
                client.download(entry.cloudSegments, output) {
                    downloaded = it
                    reportTransfer(completed + it, total)
                }
            }
            completed += downloaded
        }
        return completed
    }

    private fun reportTransfer(completed: Long, total: Long?) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (completed > 0 && completed != total && now - lastProgressUpdate < 100) return
        lastProgressUpdate = now
        mutableState.update { state ->
            val progress = state.transferProgress ?: return@update state
            state.copy(transferProgress = progress.copy(completedBytes = completed, totalBytes = total?.takeIf { it > 0 }))
        }
    }

    private fun prefetchCloudDirectories(requestedLocation: String, items: List<BrowserEntry>) {
        val current = mutableState.value
        if (current.source != BrowserSource.CloudDrive || current.locationKey() != requestedLocation) return
        val drive = current.drives.firstOrNull { it.id == current.activeDriveId } ?: return
        if (current.operationRunning || current.viewer != null) return
        val directories = items.asSequence().filter { it.isDirectory && !it.driveRoot }.take(4).toList()
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            val client = davClient(context, drive)
            directories.forEach {
                currentCoroutineContext().ensureActive()
                runCatching { client.listCloud(it.cloudSegments) }
            }
        }
    }


    private fun FileBrowserState.locationKey(): String = when (source) {
        BrowserSource.CloudDrive -> "cloud:${activeDriveId.orEmpty()}:${cloudPath.joinToString("/")}"
        BrowserSource.Device -> "device:${deviceStack.lastOrNull().orEmpty()}"
    }
}
