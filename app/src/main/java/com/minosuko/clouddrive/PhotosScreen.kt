package com.minosuko.clouddrive

import android.app.Application
import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items as listItems
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import okhttp3.Headers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs
import java.util.UUID

data class GalleryPhoto(
    val key: String,
    val uri: Uri,
    val thumbnailUri: Uri?,
    val name: String,
    val mimeType: String,
    val takenAt: Long,
    val album: String,
    val relativePath: String,
    val width: Int,
    val height: Int,
    val durationMillis: Long = 0,
    val size: Long = 0,
    val requestHeaders: Map<String, String> = emptyMap(),
    val source: BrowserSource,
    val driveProfileId: String? = null,
    val cloudSegments: List<String> = emptyList(),
) {
    val isVideo: Boolean = mimeType.startsWith("video/", ignoreCase = true) ||
        name.substringAfterLast('.', "").lowercase() in GALLERY_VIDEO_EXTENSIONS
}

data class PhotosState(
    val devicePhotos: List<GalleryPhoto> = emptyList(),
    val cloudPhotos: List<GalleryPhoto> = emptyList(),
    val deviceLoading: Boolean = true,
    val cloudLoading: Boolean = true,
    val deviceError: String? = null,
    val cloudError: String? = null,
    val operationRunning: Boolean = false,
    val operationMessage: String? = null,
    val shareRequest: PhotoShareRequest? = null,
) {
    fun photos(source: BrowserSource): List<GalleryPhoto> = if (source == BrowserSource.Device) devicePhotos else cloudPhotos
    fun loading(source: BrowserSource): Boolean = if (source == BrowserSource.Device) deviceLoading else cloudLoading
    fun error(source: BrowserSource): String? = if (source == BrowserSource.Device) deviceError else cloudError
}

data class PhotoShareRequest(
    val id: String = UUID.randomUUID().toString(),
    val uris: List<Uri>,
    val mimeType: String,
)

private data class PhotoFolder(
    val key: String,
    val name: String,
    val path: String,
    val photos: List<GalleryPhoto>,
)

private data class PhotoViewerRequest(
    val photos: List<GalleryPhoto>,
    val selectedKey: String,
    val title: String,
)

private data class PendingDeviceAlbumMove(val photos: List<GalleryPhoto>, val relativePath: String)

private sealed interface CloudPhotoEvent {
    data class Batch(val photos: List<GalleryPhoto>) : CloudPhotoEvent
    data class Error(val message: String) : CloudPhotoEvent
    data object Complete : CloudPhotoEvent
}

class PhotosViewModel(application: Application) : AndroidViewModel(application) {
    private val context = application.applicationContext
    private val mutableState = MutableStateFlow(PhotosState())
    val state: StateFlow<PhotosState> = mutableState.asStateFlow()
    private var devicePhotos = emptyList<GalleryPhoto>()
    private var cloudPhotos = emptyList<GalleryPhoto>()
    private var deviceLoading = true
    private var cloudLoading = true
    private var deviceError: String? = null
    private var cloudError: String? = null
    private var operationRunning = false
    private var operationMessage: String? = null
    private var shareRequest: PhotoShareRequest? = null
    private var deviceRefresh: Job? = null
    private var deviceRetry: Job? = null
    private var deviceRetryAttempt = 0
    private var cloudRefresh: Job? = null
    private var cloudLoaded = false
    private var lastCloudRefresh = 0L
    private var loadedDriveSignature = ""
    private val observerHandler = Handler(Looper.getMainLooper())
    private val deviceRefreshRunnable = Runnable(::refreshDevicePhotos)
    private val observer = object : ContentObserver(observerHandler) {
        override fun onChange(selfChange: Boolean) {
            observerHandler.removeCallbacks(deviceRefreshRunnable)
            observerHandler.postDelayed(deviceRefreshRunnable, 400)
        }
    }

    init {
        context.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer)
        context.contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, observer)
        refreshDevicePhotos()
    }

    fun refresh(forceCloud: Boolean = false) {
        refreshDevicePhotos()
        if (forceCloud || cloudLoaded) ensureCloudLoaded(forceCloud)
    }

    fun ensureCloudLoaded(force: Boolean = false) {
        val stale = android.os.SystemClock.elapsedRealtime() - lastCloudRefresh >= CLOUD_MEDIA_REFRESH_MILLIS
        if (!force && cloudLoaded && !stale) return
        refreshCloudPhotos(force)
    }

    private fun refreshDevicePhotos() {
        deviceRetry?.cancel()
        deviceRetry = null
        deviceRetryAttempt = 0
        refreshDevicePhotos(resetRetry = true)
    }

    private fun refreshDevicePhotos(resetRetry: Boolean) {
        if (resetRetry) deviceRetryAttempt = 0
        deviceRefresh?.cancel()
        if (!hasPhotoPermission(context) && !hasVideoPermission(context)) {
            devicePhotos = emptyList()
            deviceLoading = false
            deviceError = null
            publish()
            return
        }
        deviceLoading = true
        deviceError = null
        publish()
        deviceRefresh = viewModelScope.launch {
            try {
                devicePhotos = withContext(Dispatchers.IO) { queryDevicePhotos() }
                deviceError = null
                deviceRetryAttempt = 0
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                deviceError = error.message ?: "Could not open media library"
                scheduleDeviceRetry()
            }
            deviceLoading = false
            publish()
        }
    }

    private fun scheduleDeviceRetry() {
        if (deviceRetryAttempt >= DEVICE_MEDIA_RETRY_DELAYS.size) return
        val retryDelay = DEVICE_MEDIA_RETRY_DELAYS[deviceRetryAttempt++]
        deviceRetry?.cancel()
        deviceRetry = viewModelScope.launch {
            delay(retryDelay)
            refreshDevicePhotos(resetRetry = false)
        }
    }

    private fun refreshCloudPhotos(force: Boolean) {
        if (cloudRefresh?.isActive == true) return
        cloudLoading = true
        cloudError = null
        publish()
        cloudRefresh = viewModelScope.launch {
            try {
                val driveSignature = withContext(Dispatchers.IO) { currentDriveSignature() }
                val stale = android.os.SystemClock.elapsedRealtime() - lastCloudRefresh >= CLOUD_MEDIA_REFRESH_MILLIS
                if (!force && cloudLoaded && !stale && driveSignature == loadedDriveSignature) return@launch

                val previousPhotos = cloudPhotos
                var refreshedPhotos = emptyList<GalleryPhoto>()
                val error = queryCloudPhotos(force) { batch ->
                    refreshedPhotos = mergeNewestPhotos(refreshedPhotos, batch)
                    if (previousPhotos.isEmpty()) {
                        cloudPhotos = refreshedPhotos
                        publish()
                    }
                }
                cloudPhotos = if (error != null && refreshedPhotos.isEmpty()) previousPhotos else refreshedPhotos
                cloudError = error
                cloudLoaded = true
                lastCloudRefresh = android.os.SystemClock.elapsedRealtime()
                loadedDriveSignature = driveSignature
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                cloudError = error.message ?: "Could not load CloudDrive media"
            } finally {
                cloudLoading = false
                publish()
            }
        }
    }

    private fun currentDriveSignature(): String = AppSettings.drives(context).joinToString("|") { drive ->
        "${drive.id}:${drive.address}:${AccountStore.hasSession(context, drive.id)}"
    }

    private fun publish() {
        mutableState.value = PhotosState(
            devicePhotos = devicePhotos,
            cloudPhotos = cloudPhotos,
            deviceLoading = deviceLoading,
            cloudLoading = cloudLoading,
            deviceError = deviceError,
            cloudError = cloudError,
            operationRunning = operationRunning,
            operationMessage = operationMessage,
            shareRequest = shareRequest,
        )
    }

    private fun queryDevicePhotos(): List<GalleryPhoto> {
        val output = mutableListOf<GalleryPhoto>()
        if (hasPhotoPermission(context)) queryDeviceCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, output)
        if (hasVideoPermission(context)) queryDeviceCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, output)
        return output.sortedWith(photoNewestComparator)
    }

    private fun queryDeviceCollection(collection: Uri, video: Boolean, output: MutableList<GalleryPhoto>) {
        val projection = buildList {
            add(MediaStore.MediaColumns._ID)
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.MIME_TYPE)
            add(MediaStore.Images.Media.DATE_TAKEN)
            add(MediaStore.MediaColumns.DATE_ADDED)
            add(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            add(MediaStore.MediaColumns.RELATIVE_PATH)
            add(MediaStore.MediaColumns.WIDTH)
            add(MediaStore.MediaColumns.HEIGHT)
            add(MediaStore.MediaColumns.SIZE)
            if (video) add(MediaStore.Video.VideoColumns.DURATION)
        }.toTypedArray()
        context.contentResolver.query(
            collection,
            projection,
            "${MediaStore.MediaColumns.IS_PENDING} = 0 AND ${MediaStore.MediaColumns.SIZE} > 0",
            null,
            "${MediaStore.Images.Media.DATE_TAKEN} DESC, ${MediaStore.MediaColumns.DATE_ADDED} DESC",
        )?.use { cursor ->
            val id = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
            val name = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
            val mime = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
            val taken = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val added = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
            val album = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val path = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)
            val width = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.WIDTH)
            val height = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.HEIGHT)
            val size = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
            val duration = if (video) cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION) else -1
            while (cursor.moveToNext()) {
                val mediaId = cursor.getLong(id)
                val takenAt = cursor.getLong(taken).takeIf { it > 0 } ?: cursor.getLong(added) * 1_000
                val uri = ContentUris.withAppendedId(collection, mediaId)
                output += GalleryPhoto(
                    key = "device:${if (video) "video" else "image"}:$mediaId",
                    uri = uri,
                    thumbnailUri = uri,
                    name = cursor.getString(name) ?: if (video) "Video" else "Photo",
                    mimeType = cursor.getString(mime) ?: if (video) "video/*" else "image/*",
                    takenAt = takenAt,
                    album = cursor.getString(album)?.takeIf(String::isNotBlank) ?: "Other",
                    relativePath = cursor.getString(path).orEmpty(),
                    width = cursor.getInt(width),
                    height = cursor.getInt(height),
                    durationMillis = if (duration >= 0) cursor.getLong(duration) else 0,
                    size = cursor.getLong(size).coerceAtLeast(0),
                    source = BrowserSource.Device,
                )
            }
        }
    }

    private suspend fun queryCloudPhotos(force: Boolean, onBatch: (List<GalleryPhoto>) -> Unit): String? = coroutineScope {
        val errors = mutableListOf<String>()
        val ownBackup = syncDeviceFolder(context)
        val drives = AppSettings.drives(context)
            .filter { drive -> AccountStore.hasSession(context, drive.id) }
        if (drives.isEmpty()) return@coroutineScope null
        val events = Channel<CloudPhotoEvent>(Channel.UNLIMITED)
        drives.forEach { drive ->
            launch(Dispatchers.IO) {
                try {
                    val client = davClient(context, drive)
                    val headers = client.requestHeaders()
                    val batch = ArrayList<GalleryPhoto>(CLOUD_MEDIA_BATCH_SIZE)
                    val scanContext = coroutineContext
                    client.forEachCloudTree(
                        segments = emptyList(),
                        force = force,
                        mediaOnly = true,
                        excludedPrefix = listOf("Sync", ownBackup),
                    ) { entry ->
                        scanContext.ensureActive()
                        if (entry.isDirectory || isCurrentDeviceBackup(entry.cloudSegments, ownBackup) || !entry.isGalleryMedia()) {
                            return@forEachCloudTree
                        }
                        val parent = entry.cloudSegments.dropLast(1)
                        val displayPath = (listOf("CloudDrive", drive.name) + parent).joinToString("/")
                        batch += GalleryPhoto(
                            key = "cloud:${drive.id}:${entry.cloudSegments.joinToString("/")}",
                            uri = client.downloadUrl(entry.cloudSegments, entry.modified, entry.size),
                            thumbnailUri = if (entry.isGalleryVideo()) null else client.thumbnailUrl(
                                entry.cloudSegments,
                                modified = entry.modified,
                                size = entry.size,
                            ),
                            name = entry.name,
                            mimeType = entry.mimeType,
                            takenAt = entry.modified.coerceAtLeast(0) * 1_000,
                            album = parent.lastOrNull() ?: drive.name,
                            relativePath = displayPath,
                            width = 0,
                            height = 0,
                            size = entry.size,
                            requestHeaders = headers,
                            source = BrowserSource.CloudDrive,
                            driveProfileId = drive.id,
                            cloudSegments = entry.cloudSegments,
                        )
                        if (batch.size == CLOUD_MEDIA_BATCH_SIZE) {
                            events.trySend(CloudPhotoEvent.Batch(batch.toList()))
                            batch.clear()
                        }
                    }
                    if (batch.isNotEmpty()) events.trySend(CloudPhotoEvent.Batch(batch.toList()))
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    events.trySend(CloudPhotoEvent.Error("${drive.name}: ${error.message ?: "could not load media"}"))
                } finally {
                    events.trySend(CloudPhotoEvent.Complete)
                }
            }
        }
        var remaining = drives.size
        var deliveredFirstBatch = false
        val pendingPhotos = ArrayList<GalleryPhoto>(CLOUD_MEDIA_UI_BATCH_SIZE)
        while (remaining > 0) {
            when (val event = events.receive()) {
                is CloudPhotoEvent.Batch -> {
                    pendingPhotos += event.photos
                    if (!deliveredFirstBatch || pendingPhotos.size >= CLOUD_MEDIA_UI_BATCH_SIZE) {
                        onBatch(pendingPhotos.toList())
                        pendingPhotos.clear()
                        deliveredFirstBatch = true
                        yield()
                    }
                }
                is CloudPhotoEvent.Error -> errors += event.message
                CloudPhotoEvent.Complete -> remaining--
            }
        }
        if (pendingPhotos.isNotEmpty()) onBatch(pendingPhotos)
        events.close()
        errors.takeIf { it.isNotEmpty() }?.joinToString(". ")
    }

    fun prepareShare(photos: List<GalleryPhoto>) {
        if (photos.isEmpty() || operationRunning) return
        viewModelScope.launch {
            operationRunning = true
            operationMessage = null
            shareRequest = null
            publish()
            runCatching {
                withContext(Dispatchers.IO) {
                    photos.map { photo ->
                        if (photo.source == BrowserSource.Device) return@map photo.uri
                        val drive = AppSettings.drives(context).firstOrNull { it.id == photo.driveProfileId }
                            ?: error("CloudDrive is no longer connected")
                        val entry = BrowserEntry(
                            source = BrowserSource.CloudDrive,
                            name = photo.name,
                            isDirectory = false,
                            size = photo.size,
                            mimeType = photo.mimeType,
                            modified = photo.takenAt / 1_000,
                            cloudSegments = photo.cloudSegments,
                            driveProfileId = drive.id,
                        )
                        stageCloudExternalFile(context, davClient(context, drive), entry, installPackage = false) {}.uri
                    }
                }
            }.onSuccess { uris ->
                operationRunning = false
                shareRequest = PhotoShareRequest(uris = uris, mimeType = commonPhotoMimeType(photos))
                publish()
            }.onFailure { error ->
                operationRunning = false
                operationMessage = error.message ?: "Could not prepare media for sharing"
                publish()
            }
        }
    }

    fun deleteCloudPhotos(photos: List<GalleryPhoto>) = photoOperation(
        successMessage = if (photos.size == 1) "Media moved to Trash" else "${photos.size} items moved to Trash",
        refreshCloud = true,
    ) {
        photos.groupBy { requireNotNull(it.driveProfileId) }.forEach { (driveId, grouped) ->
            val drive = AppSettings.drives(context).firstOrNull { it.id == driveId }
                ?: error("CloudDrive is no longer connected")
            davClient(context, drive).moveManyToTrash(grouped.map(GalleryPhoto::cloudSegments))
        }
    }

    fun deleteDevicePhotos(photos: List<GalleryPhoto>) = photoOperation(
        successMessage = if (photos.size == 1) "Media deleted" else "${photos.size} items deleted",
        refreshDevice = true,
    ) {
        photos.forEach { photo ->
            check(context.contentResolver.delete(photo.uri, null, null) > 0) { "Could not delete ${photo.name}" }
        }
    }

    fun moveDevicePhotos(photos: List<GalleryPhoto>, relativePath: String) = photoOperation(
        successMessage = if (photos.size == 1) "Moved to Album" else "${photos.size} items moved to Album",
        refreshDevice = true,
    ) {
        val normalized = relativePath.replace('\\', '/').trim('/')
        require(normalized.isNotBlank() && normalized.split('/').none { it.isBlank() || it == "." || it == ".." }) {
            "Invalid Album path"
        }
        val values = ContentValues().apply { put(MediaStore.MediaColumns.RELATIVE_PATH, "$normalized/") }
        photos.forEach { photo ->
            check(context.contentResolver.update(photo.uri, values, null, null) > 0) { "Could not move ${photo.name}" }
        }
    }

    fun moveCloudPhotos(photos: List<GalleryPhoto>, destination: List<String>) = photoOperation(
        successMessage = if (photos.size == 1) "Moved to Album" else "${photos.size} items moved to Album",
        refreshCloud = true,
    ) {
        require(destination.size <= 64 && destination.none {
            it.isBlank() || it == "." || it == ".." || '/' in it || '\\' in it || it.startsWith(".clouddrive-stage-", true)
        }) { "Invalid Album path" }
        val driveId = photos.mapNotNull(GalleryPhoto::driveProfileId).distinct().singleOrNull()
            ?: error("Choose media from one CloudDrive")
        val drive = AppSettings.drives(context).firstOrNull { it.id == driveId }
            ?: error("CloudDrive is no longer connected")
        val client = davClient(context, drive)
        client.ensureDirectories(destination)
        photos.forEach { photo ->
            if (photo.cloudSegments.dropLast(1) != destination) {
                client.moveFile(photo.cloudSegments, destination, photo.name, FileConflictPolicy.KeepNewer)
            }
        }
    }

    fun consumeOperationMessage() {
        operationMessage = null
        publish()
    }

    fun consumeShareRequest() {
        shareRequest = null
        publish()
    }

    private fun photoOperation(
        successMessage: String,
        refreshDevice: Boolean = false,
        refreshCloud: Boolean = false,
        block: () -> Unit,
    ) {
        if (operationRunning) return
        viewModelScope.launch {
            operationRunning = true
            operationMessage = null
            publish()
            runCatching { withContext(Dispatchers.IO) { block() } }
                .onSuccess {
                    operationRunning = false
                    operationMessage = successMessage
                    publish()
                    if (refreshDevice) refreshDevicePhotos()
                    if (refreshCloud) ensureCloudLoaded(force = true)
                }
                .onFailure { error ->
                    operationRunning = false
                    operationMessage = error.message ?: "Media operation failed"
                    publish()
                }
        }
    }

    override fun onCleared() {
        observerHandler.removeCallbacks(deviceRefreshRunnable)
        context.contentResolver.unregisterContentObserver(observer)
    }
}

private fun commonPhotoMimeType(photos: List<GalleryPhoto>): String {
    val types = photos.map { it.mimeType }.distinct()
    if (types.size == 1) return types.first()
    if (photos.all { !it.isVideo }) return "image/*"
    if (photos.all(GalleryPhoto::isVideo)) return "video/*"
    return "*/*"
}

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PhotosScreen(onNavigationVisibilityChanged: (Boolean) -> Unit = {}) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val model: PhotosViewModel = viewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var permissionVersion by remember { mutableStateOf(0) }
    var source by rememberSaveable { mutableStateOf(BrowserSource.Device) }
    var showFolders by rememberSaveable { mutableStateOf(false) }
    var selectedFolderKey by rememberSaveable { mutableStateOf<String?>(null) }
    var pageOrder by rememberSaveable { mutableStateOf(false) }
    var layout by rememberSaveable { mutableStateOf(AppSettings.photoLayout(context)) }
    var chromeVisible by rememberSaveable { mutableStateOf(true) }
    var sourceMenuExpanded by remember { mutableStateOf(false) }
    var viewerRequest by remember { mutableStateOf<PhotoViewerRequest?>(null) }
    var videoViewer by remember { mutableStateOf<ViewerContent?>(null) }
    var selectedKeys by rememberSaveable { mutableStateOf(emptyList<String>()) }
    var deleteSelection by remember { mutableStateOf(false) }
    var moveSelection by remember { mutableStateOf(false) }
    var newAlbumName by remember { mutableStateOf("") }
    var pendingDeviceMove by remember { mutableStateOf<PendingDeviceAlbumMove?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val timelineListState = rememberLazyListState()
    val timelineGridState = rememberLazyGridState()
    val albumsListState = rememberLazyListState()
    val albumsGridState = rememberLazyGridState()
    val albumPhotosListState = rememberLazyListState()
    val albumPhotosGridState = rememberLazyGridState()
    val currentNavigationCallback by rememberUpdatedState(onNavigationVisibilityChanged)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
        model.refresh()
    }
    val trashLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            selectedKeys = emptyList()
            model.refresh()
            scope.launch { snackbar.showSnackbar("Media moved to Trash") }
        }
    }
    val albumWriteLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val pending = pendingDeviceMove
        pendingDeviceMove = null
        if (result.resultCode == Activity.RESULT_OK && pending != null) {
            selectedKeys = emptyList()
            model.moveDevicePhotos(pending.photos, pending.relativePath)
        }
    }
    val hasImageAccess = remember(permissionVersion, state.deviceLoading, state.devicePhotos.size) { hasPhotoPermission(context) }
    val hasVideoAccess = remember(permissionVersion, state.deviceLoading, state.devicePhotos.size) { hasVideoPermission(context) }
    val hasDeviceAccess = hasImageAccess || hasVideoAccess

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) model.refresh() }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose { currentNavigationCallback(true) }
    }
    LaunchedEffect(chromeVisible, viewerRequest, videoViewer) {
        currentNavigationCallback(chromeVisible && viewerRequest == null && videoViewer == null)
    }

    val photos = state.photos(source)
    val loading = state.loading(source)
    val error = state.error(source)
    val allAlbums = remember(photos) { buildPhotoFolders(photos) }
    val folders = if (showFolders || selectedFolderKey != null) allAlbums else emptyList()
    val selectedKeySet = selectedKeys.toSet()
    val selectedPhotos = remember(photos, selectedKeys) { photos.filter { it.key in selectedKeySet } }
    val selectedFolder = remember(folders, selectedFolderKey) {
        folders.firstOrNull { it.key == selectedFolderKey }
    }
    val folderPhotos = remember(selectedFolder, pageOrder) {
        selectedFolder?.photos?.let { photos ->
            if (pageOrder) photos.sortedWith(photoPageComparator) else photos
        }.orEmpty()
    }

    LaunchedEffect(folders, selectedFolderKey) {
        if (selectedFolderKey != null && folders.none { it.key == selectedFolderKey }) selectedFolderKey = null
    }
    LaunchedEffect(photos) {
        val available = photos.mapTo(hashSetOf(), GalleryPhoto::key)
        selectedKeys = selectedKeys.filter { it in available }
    }
    LaunchedEffect(selectedPhotos.isEmpty()) {
        if (selectedPhotos.isEmpty()) {
            deleteSelection = false
            moveSelection = false
        }
    }
    LaunchedEffect(selectedFolderKey) {
        if (selectedFolderKey != null) {
            albumPhotosListState.scrollToItem(0)
            albumPhotosGridState.scrollToItem(0)
        }
    }
    LaunchedEffect(state.operationMessage) {
        state.operationMessage?.let {
            snackbar.showSnackbar(it)
            model.consumeOperationMessage()
        }
    }
    LaunchedEffect(state.shareRequest?.id) {
        val request = state.shareRequest ?: return@LaunchedEffect
        try {
            val share = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                type = request.mimeType
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(request.uris))
                clipData = ClipData.newRawUri("Shared media", request.uris.first()).apply {
                    request.uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            ContextCompat.startActivity(context, Intent.createChooser(share, "Share media"), null)
            selectedKeys = emptyList()
        } catch (error: Exception) {
            snackbar.showSnackbar(error.message ?: "No app can share this media")
        } finally {
            model.consumeShareRequest()
        }
    }
    LaunchedEffect(source) {
        selectedFolderKey = null
        viewerRequest = null
        videoViewer = null
        selectedKeys = emptyList()
        chromeVisible = true
        if (source == BrowserSource.CloudDrive) model.ensureCloudLoaded()
    }
    LaunchedEffect(showFolders, selectedFolderKey, layout) {
        selectedKeys = emptyList()
        chromeVisible = true
    }
    BackHandler(enabled = (selectedPhotos.isNotEmpty() || selectedFolder != null) && viewerRequest == null && videoViewer == null) {
        if (selectedPhotos.isNotEmpty()) selectedKeys = emptyList() else selectedFolderKey = null
        chromeVisible = true
    }

    val updateLayout: (PhotoLayoutMode) -> Unit = { selected ->
        layout = selected
        AppSettings.savePhotoLayout(context, selected)
        chromeVisible = true
    }
    val togglePhotoSelection: (GalleryPhoto) -> Unit = { photo ->
        selectedKeys = if (photo.key in selectedKeySet) selectedKeys - photo.key else selectedKeys + photo.key
        chromeVisible = true
    }
    val openMedia: (GalleryPhoto, List<GalleryPhoto>, String) -> Unit = { media, mediaList, title ->
        if (media.isVideo) {
            videoViewer = ViewerContent(
                title = media.name,
                uri = media.uri,
                kind = ViewerKind.Video,
                placeholderUri = media.thumbnailUri,
                requestHeaders = media.requestHeaders,
            )
        } else {
            val images = mediaList.filterNot(GalleryPhoto::isVideo)
            viewerRequest = PhotoViewerRequest(images, media.key, title)
        }
    }
    val moveDeviceSelection: (String) -> Unit = { relativePath ->
        if (Build.VERSION.SDK_INT >= 30) {
            runCatching {
                val request = MediaStore.createWriteRequest(context.contentResolver, selectedPhotos.map(GalleryPhoto::uri))
                pendingDeviceMove = PendingDeviceAlbumMove(selectedPhotos, relativePath)
                albumWriteLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
            }.onFailure { error -> scope.launch { snackbar.showSnackbar(error.message ?: "Could not request media access") } }
        } else {
            selectedKeys = emptyList()
            model.moveDevicePhotos(selectedPhotos, relativePath)
        }
    }
    val albumCandidates = remember(allAlbums, selectedPhotos) {
        val driveId = selectedPhotos.firstOrNull()?.driveProfileId
        allAlbums.filter { album ->
            album.photos.firstOrNull()?.let { photo ->
                photo.source == source && (source == BrowserSource.Device || photo.driveProfileId == driveId)
            } == true
        }
    }

    Box(Modifier.fillMaxSize()) {
      Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = chromeVisible,
            enter = expandVertically(animationSpec = tween(220), expandFrom = Alignment.Top) +
                slideInVertically(animationSpec = tween(220)) { -it } + fadeIn(tween(160)),
            exit = shrinkVertically(animationSpec = tween(200), shrinkTowards = Alignment.Top) +
                slideOutVertically(animationSpec = tween(200)) { -it } + fadeOut(tween(140)),
        ) {
            if (selectedPhotos.isNotEmpty()) {
                PhotoSelectionHeader(
                    count = selectedPhotos.size,
                    moveEnabled = source == BrowserSource.Device || selectedPhotos.mapNotNull(GalleryPhoto::driveProfileId).distinct().size == 1,
                    busy = state.operationRunning,
                    onClose = { selectedKeys = emptyList() },
                    onShare = { model.prepareShare(selectedPhotos) },
                    onMove = { moveSelection = true },
                    onDelete = { deleteSelection = true },
                )
            } else if (selectedFolder != null) {
                PhotoFolderHeader(
                    folder = selectedFolder,
                    pageOrder = pageOrder,
                    layout = layout,
                    onBack = {
                        selectedFolderKey = null
                        chromeVisible = true
                    },
                    onPageOrderChanged = { pageOrder = it },
                    onLayoutChanged = updateLayout,
                )
            } else {
                Column {
                    Row(
                        Modifier.fillMaxWidth().padding(start = 18.dp, top = 12.dp, end = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Medias",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        if (source == BrowserSource.Device && (!hasImageAccess || !hasVideoAccess)) {
                            IconButton(onClick = { permissionLauncher.launch(visualMediaPermissions()) }) {
                                Icon(Icons.Outlined.VideoFile, "Allow image and video access")
                            }
                        }
                        PhotoLayoutMenu(layout, updateLayout)
                        IconButton(onClick = {
                            if (source == BrowserSource.CloudDrive) model.ensureCloudLoaded(force = true) else model.refresh()
                        }) { Icon(Icons.Outlined.Refresh, "Refresh media") }
                        Box {
                            IconButton(onClick = { sourceMenuExpanded = true }) {
                                Icon(Icons.Outlined.Menu, "Media source: ${if (source == BrowserSource.Device) "Device" else "CloudDrive"}")
                            }
                            DropdownMenu(expanded = sourceMenuExpanded, onDismissRequest = { sourceMenuExpanded = false }) {
                                listOf(BrowserSource.Device to "Device", BrowserSource.CloudDrive to "CloudDrive").forEach { (option, label) ->
                                    DropdownMenuItem(
                                        text = { Text(label) },
                                        trailingIcon = { if (source == option) Icon(Icons.Outlined.Check, null) },
                                        onClick = {
                                            sourceMenuExpanded = false
                                            source = option
                                        },
                                    )
                                }
                            }
                        }
                    }
                    TabRow(selectedTabIndex = if (showFolders) 1 else 0) {
                        Tab(
                            selected = !showFolders,
                            onClick = { showFolders = false },
                            text = { Text("Medias") },
                            icon = { Icon(Icons.Outlined.Collections, null, Modifier.size(19.dp)) },
                        )
                        Tab(
                            selected = showFolders,
                            onClick = { showFolders = true },
                            text = { Text("Albums") },
                            icon = { Icon(Icons.Outlined.Folder, null, Modifier.size(19.dp)) },
                        )
                    }
                }
            }
        }

        if (state.operationRunning) LinearProgressIndicator(Modifier.fillMaxWidth())

        when {
            loading && photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            source == BrowserSource.Device && !hasDeviceAccess && photos.isEmpty() -> GalleryEmptyState(
                title = "Your media, beautifully organized",
                detail = "Allow image and video access to browse this device.",
                action = "Allow media",
                onAction = { permissionLauncher.launch(visualMediaPermissions()) },
            )
            error != null && photos.isEmpty() -> GalleryEmptyState("Media library unavailable", error, "Try again") {
                if (source == BrowserSource.CloudDrive) model.ensureCloudLoaded(force = true) else model.refresh()
            }
            photos.isEmpty() && source == BrowserSource.CloudDrive -> GalleryEmptyState(
                "No CloudDrive media",
                "Sign in to a CloudDrive or add images and videos outside this device's Sync backup.",
                "Refresh",
                { model.ensureCloudLoaded(force = true) },
            )
            photos.isEmpty() -> GalleryEmptyState("No media here yet", "New images and videos will appear automatically.", "Refresh") {
                model.refresh()
            }
            selectedFolder != null -> PhotoFolderPhotos(
                photos = folderPhotos,
                layout = layout,
                listState = albumPhotosListState,
                gridState = albumPhotosGridState,
                selectedKeys = selectedKeySet,
                selectionActive = selectedPhotos.isNotEmpty(),
                onSelect = togglePhotoSelection,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { media -> openMedia(media, folderPhotos, selectedFolder.name) }
            showFolders -> PhotoFolders(
                folders = folders,
                layout = layout,
                listState = albumsListState,
                gridState = albumsGridState,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { folder ->
                pageOrder = false
                selectedFolderKey = folder.key
                chromeVisible = true
            }
            else -> PhotoTimeline(
                photos = photos,
                layout = layout,
                listState = timelineListState,
                gridState = timelineGridState,
                selectedKeys = selectedKeySet,
                selectionActive = selectedPhotos.isNotEmpty(),
                onSelect = togglePhotoSelection,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { media -> openMedia(media, photos, "All media") }
        }
      }
      SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (deleteSelection && selectedPhotos.isNotEmpty()) {
        val cloud = source == BrowserSource.CloudDrive
        AlertDialog(
            onDismissRequest = { deleteSelection = false },
            title = { Text(if (cloud || Build.VERSION.SDK_INT >= 30) "Move to Trash?" else "Delete permanently?") },
            text = {
                Text(
                    if (cloud) "${selectedPhotos.size} selected ${if (selectedPhotos.size == 1) "item" else "items"} will move to CloudDrive Trash."
                    else if (Build.VERSION.SDK_INT >= 30) "${selectedPhotos.size} selected ${if (selectedPhotos.size == 1) "item" else "items"} will move to the device Trash."
                    else "Android 10 has no media Trash. The selected media will be permanently deleted.",
                )
            },
            confirmButton = {
                Button(onClick = {
                    deleteSelection = false
                    if (cloud) {
                        selectedKeys = emptyList()
                        model.deleteCloudPhotos(selectedPhotos)
                    } else if (Build.VERSION.SDK_INT >= 30) {
                        runCatching {
                            val request = MediaStore.createTrashRequest(context.contentResolver, selectedPhotos.map(GalleryPhoto::uri), true)
                            trashLauncher.launch(IntentSenderRequest.Builder(request.intentSender).build())
                        }.onFailure { error -> scope.launch { snackbar.showSnackbar(error.message ?: "Could not request Trash access") } }
                    } else {
                        selectedKeys = emptyList()
                        model.deleteDevicePhotos(selectedPhotos)
                    }
                }) { Text(if (cloud || Build.VERSION.SDK_INT >= 30) "Move to Trash" else "Delete") }
            },
            dismissButton = { TextButton(onClick = { deleteSelection = false }) { Text("Cancel") } },
        )
    }

    if (moveSelection && selectedPhotos.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { moveSelection = false; newAlbumName = "" },
            title = { Text("Move to Album") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = newAlbumName,
                        onValueChange = { newAlbumName = it },
                        label = { Text("New Album") },
                        singleLine = true,
                    )
                    Text("Existing Albums", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (albumCandidates.isEmpty()) {
                        Text("No other Albums", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                            listItems(albumCandidates, key = PhotoFolder::key) { album ->
                                Column(
                                    Modifier.fillMaxWidth().clickable {
                                        moveSelection = false
                                        newAlbumName = ""
                                        val destination = album.photos.first()
                                        if (source == BrowserSource.Device) {
                                            moveDeviceSelection(destination.relativePath.ifBlank { "Pictures/${album.name}" })
                                        } else {
                                            selectedKeys = emptyList()
                                            model.moveCloudPhotos(selectedPhotos, destination.cloudSegments.dropLast(1))
                                        }
                                    }.padding(vertical = 9.dp),
                                ) {
                                    Text(album.name, fontWeight = FontWeight.SemiBold)
                                    Text(album.path, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val album = newAlbumName.trim()
                        moveSelection = false
                        newAlbumName = ""
                        if (source == BrowserSource.Device) {
                            moveDeviceSelection("Pictures/$album")
                        } else {
                            selectedKeys = emptyList()
                            model.moveCloudPhotos(selectedPhotos, listOf(album))
                        }
                    },
                    enabled = newAlbumName.trim().let { it.isNotBlank() && it != "." && it != ".." && '/' !in it && '\\' !in it },
                ) { Text("Create and move") }
            },
            dismissButton = { OutlinedButton(onClick = { moveSelection = false; newAlbumName = "" }) { Text("Cancel") } },
        )
    }

    viewerRequest?.let { request ->
        PhotoGalleryViewer(
            photos = request.photos,
            initialIndex = request.photos.indexOfFirst { it.key == request.selectedKey }.coerceAtLeast(0),
            title = request.title,
            onClose = {
                viewerRequest = null
            },
        )
    }
    videoViewer?.let { content ->
        MediaViewer(content, CloudDriveImageLoader.get(context)) {
            videoViewer = null
        }
    }
}

@Composable
private fun PhotoSelectionHeader(
    count: Int,
    moveEnabled: Boolean,
    busy: Boolean,
    onClose: () -> Unit,
    onShare: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onClose, enabled = !busy) { Icon(Icons.Outlined.Close, "Clear selection") }
            Text(
                "$count selected",
                modifier = Modifier.weight(1f),
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )
            IconButton(onClick = onShare, enabled = !busy) { Icon(Icons.Outlined.Share, "Share selected") }
            IconButton(onClick = onMove, enabled = moveEnabled && !busy) { Icon(Icons.Outlined.DriveFileMove, "Move to Album") }
            IconButton(onClick = onDelete, enabled = !busy) { Icon(Icons.Outlined.Delete, "Delete selected") }
        }
    }
}

@Composable
private fun PhotoFolderHeader(
    folder: PhotoFolder,
    pageOrder: Boolean,
    layout: PhotoLayoutMode,
    onBack: () -> Unit,
    onPageOrderChanged: (Boolean) -> Unit,
    onLayoutChanged: (PhotoLayoutMode) -> Unit,
) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, end = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to Albums") }
            Column(Modifier.weight(1f)) {
                Text(folder.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${folder.photos.size} ${if (folder.photos.size == 1) "item" else "items"}  |  ${folder.path}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            PhotoLayoutMenu(layout, onLayoutChanged)
        }
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(selected = !pageOrder, onClick = { onPageOrderChanged(false) }, label = { Text("Newest") })
            FilterChip(selected = pageOrder, onClick = { onPageOrderChanged(true) }, label = { Text("Page order") })
        }
    }
}

@Composable
private fun PhotoLayoutMenu(layout: PhotoLayoutMode, onLayoutChanged: (PhotoLayoutMode) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) { Icon(Icons.Outlined.GridView, "${layout.label} view") }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            PhotoLayoutMode.entries.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    trailingIcon = { if (option == layout) Icon(Icons.Outlined.Check, null) },
                    onClick = {
                        expanded = false
                        onLayoutChanged(option)
                    },
                )
            }
        }
    }
}

@Composable
private fun PhotoTimeline(
    photos: List<GalleryPhoto>,
    layout: PhotoLayoutMode,
    listState: LazyListState,
    gridState: LazyGridState,
    selectedKeys: Set<String>,
    selectionActive: Boolean,
    onSelect: (GalleryPhoto) -> Unit,
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
) {
    val grouped = remember(photos) { photos.groupBy { photoDay(it.takenAt) } }
    if (layout == PhotoLayoutMode.List) {
        ObservePhotoScroll(listState, layout, onChromeVisibilityChanged)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            grouped.forEach { (day, dayPhotos) ->
                item(key = "day:$day", contentType = "day-heading") { PhotoDayHeading(day) }
                listItems(dayPhotos, key = GalleryPhoto::key, contentType = { "photo-row" }) { photo ->
                    PhotoListRow(photo, photo.key in selectedKeys, selectionActive, onSelect, onPhoto)
                }
            }
        }
        return
    }
    ObservePhotoScroll(gridState, layout, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = photoGridCells(layout),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, top = 5.dp, end = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
        verticalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
    ) {
        grouped.forEach { (day, dayPhotos) ->
            item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }, contentType = "day-heading") {
                PhotoDayHeading(day)
            }
            items(dayPhotos, key = GalleryPhoto::key, contentType = { "photo-tile" }) { photo ->
                SelectablePhotoTile(
                    photo = photo,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                    selected = photo.key in selectedKeys,
                    selectionActive = selectionActive,
                    onSelect = { onSelect(photo) },
                    onOpen = { onPhoto(photo) },
                )
            }
        }
    }
}

@Composable
private fun PhotoFolders(
    folders: List<PhotoFolder>,
    layout: PhotoLayoutMode,
    listState: LazyListState,
    gridState: LazyGridState,
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onFolder: (PhotoFolder) -> Unit,
) {
    if (layout == PhotoLayoutMode.List) {
        ObservePhotoScroll(listState, layout, onChromeVisibilityChanged)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            listItems(folders, key = PhotoFolder::key, contentType = { "folder-row" }) { folder ->
                PhotoFolderListRow(folder, onFolder)
            }
        }
        return
    }
    ObservePhotoScroll(gridState, layout, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = folderGridCells(layout),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(folders, key = PhotoFolder::key, contentType = { "folder-card" }) { folder ->
            PhotoFolderCard(folder, onFolder)
        }
    }
}

@Composable
private fun PhotoFolderPhotos(
    photos: List<GalleryPhoto>,
    layout: PhotoLayoutMode,
    listState: LazyListState,
    gridState: LazyGridState,
    selectedKeys: Set<String>,
    selectionActive: Boolean,
    onSelect: (GalleryPhoto) -> Unit,
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
) {
    if (layout == PhotoLayoutMode.List) {
        ObservePhotoScroll(listState, layout, onChromeVisibilityChanged)
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            listItems(photos, key = GalleryPhoto::key, contentType = { "photo-row" }) { photo ->
                PhotoListRow(photo, photo.key in selectedKeys, selectionActive, onSelect, onPhoto)
            }
        }
        return
    }
    ObservePhotoScroll(gridState, layout, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = photoGridCells(layout),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
        verticalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
    ) {
        items(photos, key = GalleryPhoto::key, contentType = { "photo-tile" }) { photo ->
            SelectablePhotoTile(
                photo = photo,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                selected = photo.key in selectedKeys,
                selectionActive = selectionActive,
                onSelect = { onSelect(photo) },
                onOpen = { onPhoto(photo) },
            )
        }
    }
}

@Composable
private fun PhotoDayHeading(day: String) {
    Text(
        day,
        modifier = Modifier.padding(start = 8.dp, top = 15.dp, bottom = 7.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoListRow(
    photo: GalleryPhoto,
    selected: Boolean,
    selectionActive: Boolean,
    onSelect: (GalleryPhoto) -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .combinedClickable(
                onClick = { if (selectionActive) onSelect(photo) else onPhoto(photo) },
                onLongClick = { onSelect(photo) },
            )
            .padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryPhotoImage(photo, Modifier.size(76.dp).clip(RoundedCornerShape(13.dp)))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(photo.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                photo.relativePath.ifBlank { photo.album },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                buildString {
                    append(formatPhotoTime(photo.takenAt))
                    if (photo.isVideo && photo.durationMillis > 0) append("  |  ${formatDuration(photo.durationMillis)}")
                },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp,
            )
        }
        if (selected) Icon(Icons.Outlined.Check, "Selected", tint = MaterialTheme.colorScheme.primary)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SelectablePhotoTile(
    photo: GalleryPhoto,
    modifier: Modifier,
    selected: Boolean,
    selectionActive: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(5.dp))
            .combinedClickable(onClick = { if (selectionActive) onSelect() else onOpen() }, onLongClick = onSelect),
    ) {
        GalleryPhotoImage(photo, Modifier.fillMaxSize())
        if (selected) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .24f)))
            Surface(
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                shape = RoundedCornerShape(18.dp),
                color = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Outlined.Check, "Selected", Modifier.padding(5.dp).size(18.dp), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
    }
}

@Composable
private fun PhotoFolderCard(folder: PhotoFolder, onFolder: (PhotoFolder) -> Unit) {
    Card(
        onClick = { onFolder(folder) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Box {
            GalleryPhotoImage(folder.photos.first(), Modifier.fillMaxWidth().aspectRatio(1.15f))
            Surface(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                shape = RoundedCornerShape(11.dp),
                color = Color.Black.copy(alpha = .68f),
            ) {
                Text(folder.photos.size.toString(), color = Color.White, fontSize = 11.sp, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
        }
        Column(Modifier.padding(horizontal = 11.dp, vertical = 9.dp)) {
            Text(folder.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun PhotoFolderListRow(folder: PhotoFolder, onFolder: (PhotoFolder) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onFolder(folder) }.padding(horizontal = 4.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GalleryPhotoImage(folder.photos.first(), Modifier.size(82.dp).clip(RoundedCornerShape(13.dp)))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(folder.name, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folder.path, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${folder.photos.size} ${if (folder.photos.size == 1) "item" else "items"}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
    }
}

private fun photoGridCells(layout: PhotoLayoutMode): GridCells = GridCells.Adaptive(
    when (layout) {
        PhotoLayoutMode.LargeGrid -> 156.dp
        PhotoLayoutMode.SmallGrid -> 72.dp
        else -> 104.dp
    },
)

private fun folderGridCells(layout: PhotoLayoutMode): GridCells = GridCells.Adaptive(
    when (layout) {
        PhotoLayoutMode.LargeGrid -> 180.dp
        PhotoLayoutMode.SmallGrid -> 112.dp
        else -> 148.dp
    },
)

private fun photoGridSpacing(layout: PhotoLayoutMode) = if (layout == PhotoLayoutMode.LargeGrid) 6.dp else 3.dp

@Composable
private fun ObservePhotoScroll(state: LazyGridState, resetKey: Any?, onChromeVisibilityChanged: (Boolean) -> Unit) {
    ObservePhotoScrollPosition(
        key = state,
        resetKey = resetKey,
        position = { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress) },
        onChromeVisibilityChanged = onChromeVisibilityChanged,
    )
}

@Composable
private fun ObservePhotoScroll(state: LazyListState, resetKey: Any?, onChromeVisibilityChanged: (Boolean) -> Unit) {
    ObservePhotoScrollPosition(
        key = state,
        resetKey = resetKey,
        position = { Triple(state.firstVisibleItemIndex, state.firstVisibleItemScrollOffset, state.isScrollInProgress) },
        onChromeVisibilityChanged = onChromeVisibilityChanged,
    )
}

@Composable
private fun ObservePhotoScrollPosition(
    key: Any,
    resetKey: Any?,
    position: () -> Triple<Int, Int, Boolean>,
    onChromeVisibilityChanged: (Boolean) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onChromeVisibilityChanged)
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(key, resetKey, density) {
        val hideThreshold = with(density) { 52.dp.roundToPx() }
        val showThreshold = with(density) { 36.dp.roundToPx() }
        var previous = position()
        var direction = 0
        var travel = 0
        var visible = true
        snapshotFlow { position() }.collect { current ->
            if (current.first == 0 && current.second <= 4) {
                if (!visible) {
                    visible = true
                    currentCallback(true)
                }
                direction = 0
                travel = 0
            } else if (!current.third) {
                direction = 0
                travel = 0
            } else {
                val nextDirection = when {
                    current.first > previous.first -> 1
                    current.first < previous.first -> -1
                    current.second > previous.second -> 1
                    current.second < previous.second -> -1
                    else -> 0
                }
                if (nextDirection != 0) {
                    if (nextDirection != direction) {
                        direction = nextDirection
                        travel = 0
                    }
                    travel += if (current.first == previous.first) {
                        abs(current.second - previous.second)
                    } else {
                        hideThreshold
                    }
                    val threshold = if (direction > 0) hideThreshold else showThreshold
                    if (travel >= threshold) {
                        val nextVisible = direction < 0
                        if (visible != nextVisible) {
                            visible = nextVisible
                            currentCallback(nextVisible)
                        }
                        travel = 0
                    }
                }
            }
            previous = current
        }
    }
}

@Composable
private fun GalleryPhotoImage(photo: GalleryPhoto, modifier: Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val request = remember(photo.thumbnailUri, photo.requestHeaders) {
        photo.thumbnailUri?.let { thumbnail ->
            ImageRequest.Builder(context).data(thumbnail).apply {
                if (photo.isVideo && photo.source == BrowserSource.Device) videoFrameMillis(1_000)
                if (photo.requestHeaders.isNotEmpty()) {
                    headers(Headers.Builder().apply {
                        photo.requestHeaders.forEach { (name, value) -> set(name, value) }
                    }.build())
                }
            }.allowRgb565(true).crossfade(false).build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (request == null) {
            PhotoPlaceholder(photo.isVideo)
        } else {
            AsyncImage(
                model = request,
                imageLoader = CloudDriveImageLoader.get(context),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (photo.isVideo) {
            Surface(
                modifier = Modifier.align(Alignment.Center),
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = .58f),
            ) {
                Icon(Icons.Outlined.VideoFile, null, Modifier.padding(8.dp).size(21.dp), tint = Color.White)
            }
        }
    }
}

@Composable
private fun PhotoPlaceholder(video: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Icon(if (video) Icons.Outlined.VideoFile else Icons.Outlined.Image, null, tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .55f))
    }
}

@Composable
private fun GalleryEmptyState(title: String, detail: String, action: String, onAction: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.primaryContainer) {
                Icon(Icons.Outlined.Image, null, Modifier.padding(18.dp).size(34.dp), tint = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PhotoGalleryViewer(photos: List<GalleryPhoto>, initialIndex: Int, title: String, onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val pager = rememberPagerState(initialPage = initialIndex, pageCount = { photos.size })
    val current = photos.getOrNull(pager.currentPage)
    var controlsVisible by remember { mutableStateOf(true) }
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(state = pager, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
                GalleryZoomImage(photos[page]) { controlsVisible = !controlsVisible }
            }
            AnimatedVisibility(
                visible = controlsVisible,
                modifier = Modifier.align(Alignment.TopCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .52f)).padding(horizontal = 8.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onClose) { Icon(Icons.Outlined.Close, "Close", tint = Color.White) }
                    Column(Modifier.weight(1f)) {
                        Text(title, color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold)
                        Text(
                            "${current?.name.orEmpty()}  |  ${pager.currentPage + 1} of ${photos.size}",
                            color = Color.White.copy(alpha = .72f),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (current?.source == BrowserSource.Device) {
                        IconButton(onClick = {
                            current?.let { photo ->
                                val share = Intent(Intent.ACTION_SEND).apply {
                                    type = photo.mimeType
                                    putExtra(Intent.EXTRA_STREAM, photo.uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }
                                ContextCompat.startActivity(context, Intent.createChooser(share, "Share media"), null)
                            }
                        }) { Icon(Icons.Outlined.Share, "Share", tint = Color.White) }
                    }
                }
            }
            AnimatedVisibility(
                visible = controlsVisible && current != null,
                modifier = Modifier.align(Alignment.BottomCenter),
                enter = fadeIn(),
                exit = fadeOut(),
            ) {
                current?.let { photo ->
                    Text(
                        "${photo.relativePath.ifBlank { photo.album }}  |  ${formatPhotoTime(photo.takenAt)}${if (photo.width > 0) "  |  ${photo.width} x ${photo.height}" else ""}",
                        color = Color.White.copy(alpha = .84f),
                        fontSize = 12.sp,
                        modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = .52f)).padding(16.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun GalleryZoomImage(photo: GalleryPhoto, onTap: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scale by remember(photo.key) { mutableStateOf(1f) }
    var offset by remember(photo.key) { mutableStateOf(Offset.Zero) }
    val request = remember(photo.uri, photo.requestHeaders) {
        ImageRequest.Builder(context).data(photo.uri).apply {
            if (photo.requestHeaders.isNotEmpty()) {
                headers(Headers.Builder().apply {
                    photo.requestHeaders.forEach { (name, value) -> set(name, value) }
                }.build())
            }
        }.crossfade(false).build()
    }
    SubcomposeAsyncImage(
        model = request,
        imageLoader = CloudDriveImageLoader.get(context),
        contentDescription = photo.name,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(photo.key) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var totalMovement = Offset.Zero
                    var usedMultiplePointers = false
                    var changedScale = false
                    var stillPressed: Boolean
                    do {
                        val event = awaitPointerEvent()
                        val pressedPointers = event.changes.count { it.pressed }
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        event.changes.firstOrNull()?.let { change ->
                            totalMovement += change.position - change.previousPosition
                        }
                        if (pressedPointers > 1) usedMultiplePointers = true
                        if (zoom != 1f) changedScale = true

                        if (pressedPointers > 1 || scale > 1.001f || zoom != 1f) {
                            val nextScale = (scale * zoom).coerceIn(1f, 7f)
                            val maxX = size.width * (nextScale - 1f) / 2f
                            val maxY = size.height * (nextScale - 1f) / 2f
                            scale = nextScale
                            offset = if (nextScale <= 1.001f) {
                                Offset.Zero
                            } else {
                                Offset(
                                    (offset.x + pan.x).coerceIn(-maxX, maxX),
                                    (offset.y + pan.y).coerceIn(-maxY, maxY),
                                )
                            }
                            event.changes.forEach { it.consume() }
                        }
                        stillPressed = event.changes.any { it.pressed }
                    } while (stillPressed)

                    if (!usedMultiplePointers && !changedScale && totalMovement.getDistance() < viewConfiguration.touchSlop) {
                        onTap()
                    }
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } },
        error = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Media unavailable", color = Color.White) } },
        success = { SubcomposeAsyncImageContent() },
    )
}

private fun buildPhotoFolders(photos: List<GalleryPhoto>): List<PhotoFolder> = photos
    .groupBy { photo -> photo.relativePath.trim().trim('/').ifBlank { "album:${photo.album}" } }
    .map { (key, folderPhotos) ->
        val path = folderPhotos.first().relativePath.trim().trim('/').ifBlank { folderPhotos.first().album }
        PhotoFolder(
            key = key,
            name = path.substringAfterLast('/').ifBlank { folderPhotos.first().album },
            path = path,
            photos = folderPhotos.sortedByDescending(GalleryPhoto::takenAt),
        )
    }
    .sortedByDescending { folder -> folder.photos.firstOrNull()?.takenAt ?: 0L }

private val photoNewestComparator = compareByDescending<GalleryPhoto> { it.takenAt }.thenBy(GalleryPhoto::key)

private fun mergeNewestPhotos(current: List<GalleryPhoto>, added: List<GalleryPhoto>): List<GalleryPhoto> {
    if (added.isEmpty()) return current
    val incoming = added.sortedWith(photoNewestComparator)
    if (current.isEmpty()) return incoming
    val merged = ArrayList<GalleryPhoto>(current.size + incoming.size)
    var currentIndex = 0
    var incomingIndex = 0
    while (currentIndex < current.size && incomingIndex < incoming.size) {
        if (photoNewestComparator.compare(current[currentIndex], incoming[incomingIndex]) <= 0) {
            merged += current[currentIndex++]
        } else {
            merged += incoming[incomingIndex++]
        }
    }
    while (currentIndex < current.size) merged += current[currentIndex++]
    while (incomingIndex < incoming.size) merged += incoming[incomingIndex++]
    return merged
}

private val photoPageComparator = Comparator<GalleryPhoto> { left, right ->
    naturalNameCompare(left.name, right.name).takeIf { it != 0 } ?: left.key.compareTo(right.key)
}

private fun isCurrentDeviceBackup(segments: List<String>, device: String): Boolean =
    segments.size >= 2 && segments[0].equals("Sync", ignoreCase = true) && segments[1].equals(device, ignoreCase = true)

private fun BrowserEntry.isGalleryMedia(): Boolean = mimeType.startsWith("image/", ignoreCase = true) ||
    mimeType.startsWith("video/", ignoreCase = true) || name.substringAfterLast('.', "").lowercase() in GALLERY_MEDIA_EXTENSIONS

private fun BrowserEntry.isGalleryVideo(): Boolean = mimeType.startsWith("video/", ignoreCase = true) ||
    name.substringAfterLast('.', "").lowercase() in GALLERY_VIDEO_EXTENSIONS

private val GALLERY_VIDEO_EXTENSIONS = setOf("mp4", "mkv", "webm", "mov", "avi", "m4v", "3gp")
private val GALLERY_MEDIA_EXTENSIONS = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "avif") + GALLERY_VIDEO_EXTENSIONS
private const val CLOUD_MEDIA_REFRESH_MILLIS = 60_000L
private const val CLOUD_MEDIA_BATCH_SIZE = 256
private const val CLOUD_MEDIA_UI_BATCH_SIZE = 1_024
private val DEVICE_MEDIA_RETRY_DELAYS = longArrayOf(750L, 1_500L, 3_000L, 6_000L)

private val naturalNameParts = Regex("\\d+|\\D+")

private fun naturalNameCompare(left: String, right: String): Int {
    val leftParts = naturalNameParts.findAll(left).map { it.value }.toList()
    val rightParts = naturalNameParts.findAll(right).map { it.value }.toList()
    for (index in 0 until minOf(leftParts.size, rightParts.size)) {
        val leftPart = leftParts[index]
        val rightPart = rightParts[index]
        val comparison = if (leftPart.all(Char::isDigit) && rightPart.all(Char::isDigit)) {
            val leftNumber = leftPart.trimStart('0').ifEmpty { "0" }
            val rightNumber = rightPart.trimStart('0').ifEmpty { "0" }
            leftNumber.length.compareTo(rightNumber.length).takeIf { it != 0 }
                ?: leftNumber.compareTo(rightNumber)
        } else {
            leftPart.compareTo(rightPart, ignoreCase = true)
        }
        if (comparison != 0) return comparison
    }
    return leftParts.size.compareTo(rightParts.size).takeIf { it != 0 }
        ?: left.compareTo(right, ignoreCase = true)
}

private val dayFormatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy")
private val timeFormatter = DateTimeFormatter.ofPattern("MMM d, yyyy  h:mm a")

private fun photoDay(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault())
    .format(dayFormatter)

private fun formatPhotoTime(timestamp: Long): String = Instant.ofEpochMilli(timestamp)
    .atZone(ZoneId.systemDefault())
    .format(timeFormatter)

private fun formatDuration(milliseconds: Long): String {
    val totalSeconds = milliseconds.coerceAtLeast(0) / 1_000
    val hours = totalSeconds / 3_600
    val minutes = totalSeconds % 3_600 / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
