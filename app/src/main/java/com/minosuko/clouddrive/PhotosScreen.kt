package com.minosuko.clouddrive

import android.app.Application
import android.content.ContentUris
import android.content.Intent
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.outlined.VideoFile
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Headers
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

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
    val requestHeaders: Map<String, String> = emptyMap(),
    val source: BrowserSource,
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
) {
    fun photos(source: BrowserSource): List<GalleryPhoto> = if (source == BrowserSource.Device) devicePhotos else cloudPhotos
    fun loading(source: BrowserSource): Boolean = if (source == BrowserSource.Device) deviceLoading else cloudLoading
    fun error(source: BrowserSource): String? = if (source == BrowserSource.Device) deviceError else cloudError
}

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

private data class CloudPhotoResult(val photos: List<GalleryPhoto>, val error: String?)

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
    private var deviceRefresh: Job? = null
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
        refresh()
    }

    fun refresh(forceCloud: Boolean = false) {
        refreshDevicePhotos()
        val driveSignature = currentDriveSignature()
        val stale = android.os.SystemClock.elapsedRealtime() - lastCloudRefresh >= CLOUD_MEDIA_REFRESH_MILLIS
        if (forceCloud || !cloudLoaded || stale || driveSignature != loadedDriveSignature) {
            refreshCloudPhotos(forceCloud, driveSignature)
        }
    }

    private fun refreshDevicePhotos() {
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
            runCatching { withContext(Dispatchers.IO) { queryDevicePhotos() } }
                .onSuccess { photos -> devicePhotos = photos }
                .onFailure { error -> deviceError = error.message ?: "Could not open media library" }
            deviceLoading = false
            publish()
        }
    }

    private fun refreshCloudPhotos(force: Boolean, driveSignature: String) {
        if (cloudRefresh?.isActive == true) return
        cloudLoading = true
        cloudError = null
        publish()
        cloudRefresh = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { queryCloudPhotos(force) }
            cloudPhotos = result.photos
            cloudError = result.error
            cloudLoading = false
            cloudLoaded = true
            lastCloudRefresh = android.os.SystemClock.elapsedRealtime()
            loadedDriveSignature = driveSignature
            publish()
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
        )
    }

    private fun queryDevicePhotos(): List<GalleryPhoto> {
        val output = mutableListOf<GalleryPhoto>()
        if (hasPhotoPermission(context)) queryDeviceCollection(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, output)
        if (hasVideoPermission(context)) queryDeviceCollection(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, output)
        return output.sortedWith(compareByDescending<GalleryPhoto> { it.takenAt }.thenBy { it.key })
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
                    source = BrowserSource.Device,
                )
            }
        }
    }

    private fun queryCloudPhotos(force: Boolean): CloudPhotoResult {
        val photos = mutableListOf<GalleryPhoto>()
        val errors = mutableListOf<String>()
        val ownBackup = syncDeviceFolder(context)
        AppSettings.drives(context)
            .filter { drive -> AccountStore.hasSession(context, drive.id) }
            .forEach { drive ->
                runCatching {
                    val client = davClient(context, drive)
                    val headers = client.requestHeaders()
                    client.forEachCloudTree(emptyList(), force = force) { entry ->
                        if (entry.isDirectory || isCurrentDeviceBackup(entry.cloudSegments, ownBackup) || !entry.isGalleryMedia()) {
                            return@forEachCloudTree
                        }
                        val parent = entry.cloudSegments.dropLast(1)
                        val displayPath = (listOf("CloudDrive", drive.name) + parent).joinToString("/")
                        photos += GalleryPhoto(
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
                            requestHeaders = headers,
                            source = BrowserSource.CloudDrive,
                        )
                    }
                }.onFailure { error -> errors += "${drive.name}: ${error.message ?: "could not load media"}" }
            }
        return CloudPhotoResult(photos, errors.takeIf { it.isNotEmpty() }?.joinToString(". "))
    }

    override fun onCleared() {
        observerHandler.removeCallbacks(deviceRefreshRunnable)
        context.contentResolver.unregisterContentObserver(observer)
    }
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
    val currentNavigationCallback by rememberUpdatedState(onNavigationVisibilityChanged)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permissionVersion++
        model.refresh()
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
    val folders = remember(photos, showFolders, selectedFolderKey) {
        if (showFolders || selectedFolderKey != null) buildPhotoFolders(photos) else emptyList()
    }
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
    LaunchedEffect(source) {
        selectedFolderKey = null
        viewerRequest = null
        videoViewer = null
        chromeVisible = true
    }
    LaunchedEffect(showFolders, selectedFolderKey, layout) { chromeVisible = true }
    BackHandler(enabled = selectedFolder != null && viewerRequest == null) {
        selectedFolderKey = null
        chromeVisible = true
    }

    val updateLayout: (PhotoLayoutMode) -> Unit = { selected ->
        layout = selected
        AppSettings.savePhotoLayout(context, selected)
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

    Column(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = chromeVisible,
            enter = slideInVertically { -it } + fadeIn(),
            exit = slideOutVertically { -it } + fadeOut(),
        ) {
            if (selectedFolder != null) {
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
                        IconButton(onClick = { model.refresh(forceCloud = true) }) { Icon(Icons.Outlined.Refresh, "Refresh media") }
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
                            text = { Text("Folders") },
                            icon = { Icon(Icons.Outlined.Folder, null, Modifier.size(19.dp)) },
                        )
                    }
                }
            }
        }

        when {
            loading && photos.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            source == BrowserSource.Device && !hasDeviceAccess && photos.isEmpty() -> GalleryEmptyState(
                title = "Your media, beautifully organized",
                detail = "Allow image and video access to browse this device.",
                action = "Allow media",
                onAction = { permissionLauncher.launch(visualMediaPermissions()) },
            )
            error != null -> GalleryEmptyState("Media library unavailable", error, "Try again") { model.refresh(forceCloud = true) }
            photos.isEmpty() && source == BrowserSource.CloudDrive -> GalleryEmptyState(
                "No CloudDrive media",
                "Sign in to a CloudDrive or add images and videos outside this device's Sync backup.",
                "Refresh",
                { model.refresh(forceCloud = true) },
            )
            photos.isEmpty() -> GalleryEmptyState("No media here yet", "New images and videos will appear automatically.", "Refresh") {
                model.refresh(forceCloud = true)
            }
            selectedFolder != null -> PhotoFolderPhotos(
                photos = folderPhotos,
                layout = layout,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { media -> openMedia(media, folderPhotos, selectedFolder.name) }
            showFolders -> PhotoFolders(
                folders = folders,
                layout = layout,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { folder ->
                pageOrder = false
                selectedFolderKey = folder.key
                chromeVisible = true
            }
            else -> PhotoTimeline(
                photos = photos,
                layout = layout,
                onChromeVisibilityChanged = { chromeVisible = it },
            ) { media -> openMedia(media, photos, "All media") }
        }
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
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to folders") }
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
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
) {
    val grouped = remember(photos) { photos.groupBy { photoDay(it.takenAt) } }
    if (layout == PhotoLayoutMode.List) {
        val state = rememberLazyListState()
        ObservePhotoScroll(state, onChromeVisibilityChanged)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp),
        ) {
            grouped.forEach { (day, dayPhotos) ->
                item(key = "day:$day") { PhotoDayHeading(day) }
                listItems(dayPhotos, key = GalleryPhoto::key) { photo -> PhotoListRow(photo, onPhoto) }
            }
        }
        return
    }
    val state = rememberLazyGridState()
    ObservePhotoScroll(state, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = photoGridCells(layout),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, top = 5.dp, end = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
        verticalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
    ) {
        grouped.forEach { (day, dayPhotos) ->
            item(key = "day:$day", span = { GridItemSpan(maxLineSpan) }) {
                PhotoDayHeading(day)
            }
            items(dayPhotos, key = GalleryPhoto::key) { photo ->
                GalleryPhotoImage(
                    photo = photo,
                    modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onPhoto(photo) },
                )
            }
        }
    }
}

@Composable
private fun PhotoFolders(
    folders: List<PhotoFolder>,
    layout: PhotoLayoutMode,
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onFolder: (PhotoFolder) -> Unit,
) {
    if (layout == PhotoLayoutMode.List) {
        val state = rememberLazyListState()
        ObservePhotoScroll(state, onChromeVisibilityChanged)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            listItems(folders, key = PhotoFolder::key) { folder -> PhotoFolderListRow(folder, onFolder) }
        }
        return
    }
    val state = rememberLazyGridState()
    ObservePhotoScroll(state, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = folderGridCells(layout),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(folders, key = PhotoFolder::key) { folder ->
            PhotoFolderCard(folder, onFolder)
        }
    }
}

@Composable
private fun PhotoFolderPhotos(
    photos: List<GalleryPhoto>,
    layout: PhotoLayoutMode,
    onChromeVisibilityChanged: (Boolean) -> Unit,
    onPhoto: (GalleryPhoto) -> Unit,
) {
    if (layout == PhotoLayoutMode.List) {
        val state = rememberLazyListState()
        ObservePhotoScroll(state, onChromeVisibilityChanged)
        LazyColumn(
            state = state,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
        ) {
            listItems(photos, key = GalleryPhoto::key) { photo -> PhotoListRow(photo, onPhoto) }
        }
        return
    }
    val state = rememberLazyGridState()
    ObservePhotoScroll(state, onChromeVisibilityChanged)
    LazyVerticalGrid(
        columns = photoGridCells(layout),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
        verticalArrangement = Arrangement.spacedBy(photoGridSpacing(layout)),
    ) {
        items(photos, key = GalleryPhoto::key) { photo ->
            GalleryPhotoImage(
                photo = photo,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f).clickable { onPhoto(photo) },
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

@Composable
private fun PhotoListRow(photo: GalleryPhoto, onPhoto: (GalleryPhoto) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onPhoto(photo) }.padding(horizontal = 4.dp, vertical = 6.dp),
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
private fun ObservePhotoScroll(state: LazyGridState, onChromeVisibilityChanged: (Boolean) -> Unit) {
    ObservePhotoScrollPosition(
        key = state,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        onChromeVisibilityChanged = onChromeVisibilityChanged,
    )
}

@Composable
private fun ObservePhotoScroll(state: LazyListState, onChromeVisibilityChanged: (Boolean) -> Unit) {
    ObservePhotoScrollPosition(
        key = state,
        position = { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset },
        onChromeVisibilityChanged = onChromeVisibilityChanged,
    )
}

@Composable
private fun ObservePhotoScrollPosition(
    key: Any,
    position: () -> Pair<Int, Int>,
    onChromeVisibilityChanged: (Boolean) -> Unit,
) {
    val currentCallback by rememberUpdatedState(onChromeVisibilityChanged)
    LaunchedEffect(key) {
        var previous = position()
        var direction = 0
        var travel = 0
        snapshotFlow { position() }.collect { current ->
            if (current.first == 0 && current.second <= 4) {
                currentCallback(true)
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
                    travel += if (current.first == previous.first) abs(current.second - previous.second) else 32
                    if (travel >= 20) {
                        currentCallback(direction < 0)
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
            }.crossfade(false).build()
        }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
        if (request == null) {
            PhotoPlaceholder(photo.isVideo)
        } else {
            SubcomposeAsyncImage(
                model = request,
                imageLoader = CloudDriveImageLoader.get(context),
                contentDescription = photo.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                loading = { PhotoPlaceholder(photo.isVideo) },
                error = { PhotoPlaceholder(photo.isVideo) },
                success = { SubcomposeAsyncImageContent() },
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
