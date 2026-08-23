package com.minosuko.clouddrive

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.ui.PlayerView
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.ImageLoader
import coil.request.ImageRequest
import okhttp3.Headers

enum class ViewerKind { Image, Video, Audio }

data class ViewerContent(
    val title: String,
    val uri: Uri,
    val kind: ViewerKind,
    val placeholderUri: Uri? = null,
    val requestHeaders: Map<String, String> = emptyMap(),
)

@Composable
fun MediaViewer(content: ViewerContent, imageLoader: ImageLoader, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Color.Black) {
            Box(Modifier.fillMaxSize()) {
                when (content.kind) {
                    ViewerKind.Image -> ZoomableImage(content, imageLoader)
                    ViewerKind.Video, ViewerKind.Audio -> PlayerContent(content)
                }
                Text(
                    content.title,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth(.8f).background(Color.Black.copy(alpha = .45f)).padding(horizontal = 14.dp, vertical = 14.dp),
                )
                IconButton(
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                ) {
                    Icon(Icons.Outlined.Close, "Close", tint = Color.White)
                }
            }
        }
    }
}

@Composable
private fun ZoomableImage(content: ViewerContent, imageLoader: ImageLoader) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var scale by remember(content.uri) { mutableStateOf(1f) }
    var offset by remember(content.uri) { mutableStateOf(Offset.Zero) }
    val request = remember(content.uri, content.placeholderUri, content.requestHeaders) {
        ImageRequest.Builder(context)
            .data(content.uri)
            .apply { content.placeholderUri?.let { placeholderMemoryCacheKey(it.toString()) } }
            .apply {
                if (content.requestHeaders.isNotEmpty()) {
                    headers(Headers.Builder().apply {
                        content.requestHeaders.forEach { (name, value) -> set(name, value) }
                    }.build())
                }
            }
            .crossfade(false)
            .build()
    }
    SubcomposeAsyncImage(
        model = request,
        imageLoader = imageLoader,
        contentDescription = content.title,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(content.uri) {
                detectTransformGestures { _, pan, zoom, _ ->
                    val nextScale = (scale * zoom).coerceIn(1f, 6f)
                    scale = nextScale
                    offset = if (nextScale == 1f) Offset.Zero else offset + pan
                }
            }
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                translationX = offset.x
                translationY = offset.y
            },
        contentScale = ContentScale.Fit,
        loading = { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color.White) } },
        error = { ViewerError("Image could not be opened") },
        success = { SubcomposeAsyncImageContent() },
    )
}

@Composable
@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerContent(content: ViewerContent) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val audio = content.kind == ViewerKind.Audio
    var error by remember(content.uri) { mutableStateOf<String?>(null) }
    var ready by remember(content.uri) { mutableStateOf(false) }
    var playing by remember(content.uri) { mutableStateOf(false) }
    val player = remember(content.uri, content.requestHeaders) {
        val builder = ExoPlayer.Builder(context)
        if (content.requestHeaders.isNotEmpty()) {
            val dataSource = DefaultHttpDataSource.Factory().setDefaultRequestProperties(content.requestHeaders)
            builder.setMediaSourceFactory(DefaultMediaSourceFactory(dataSource))
        }
        builder.build().apply {
            setMediaItem(MediaItem.fromUri(content.uri))
        }
    }
    DisposableEffect(player, lifecycleOwner) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                ready = playbackState == Player.STATE_READY || playbackState == Player.STATE_ENDED
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlayerError(playerError: PlaybackException) {
                error = playerError.localizedMessage ?: if (audio) "Audio could not be opened" else "Video could not be opened"
            }
        }
        val lifecycleObserver = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) player.pause() }
        player.addListener(listener)
        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)
        player.prepare()
        player.playWhenReady = true
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(lifecycleObserver)
            player.removeListener(listener)
            player.release()
        }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        if (audio) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = androidx.compose.foundation.shape.RoundedCornerShape(36.dp)) {
                    Icon(
                        Icons.Outlined.MusicNote,
                        null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(28.dp).size(54.dp),
                    )
                }
                Text("Audio", color = Color.White.copy(alpha = .72f), modifier = Modifier.padding(top = 14.dp))
            }
        }
        AndroidView(
            factory = {
                PlayerView(it).apply {
                    this.player = player
                    useController = true
                    controllerShowTimeoutMs = if (audio) 0 else 5_000
                    controllerHideOnTouch = !audio
                    setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
                    keepScreenOn = false
                    if (audio) {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                }
            },
            update = {
                it.keepScreenOn = !audio && playing
                if (audio) it.showController()
            },
            modifier = if (audio) {
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(150.dp).padding(bottom = 24.dp)
            } else {
                Modifier.fillMaxSize()
            },
        )
        if (!ready && error == null) CircularProgressIndicator(color = Color.White)
        error?.let { ViewerError(it) }
    }
}

@Composable
private fun ViewerError(message: String) {
    Box(Modifier.fillMaxSize().padding(36.dp), contentAlignment = Alignment.Center) {
        androidx.compose.material3.Text(message, color = Color.White)
    }
}
