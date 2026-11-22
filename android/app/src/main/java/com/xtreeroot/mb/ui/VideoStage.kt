package com.xtreeroot.mb.ui

import androidx.annotation.OptIn
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.VideoFrameState
import com.xtreeroot.mb.playback.PlaybackState
import kotlinx.coroutines.delay

@Composable
internal fun rememberVideoFrame(track: Track, systemWindow: Boolean = false): VideoFrameState? {
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    if (!systemWindow && !lifecycle.isAtLeast(Lifecycle.State.STARTED)) return null
    val application = LocalContext.current.applicationContext as MBApplication
    val coordinator by application.videoPlayback.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    val video = coordinator ?: return null
    val current by video.frames.collectAsStateWithLifecycle(minActiveState = Lifecycle.State.CREATED)
    DisposableEffect(video, track.id) {
        val ticket = video.show(track)
        onDispose { ticket?.close() }
    }
    return current?.takeIf { it.trackId == track.id }
}

@Composable
internal fun VideoStage(state: VideoFrameState?, playback: PlaybackState, toggle: () -> Unit, seek: (Long) -> Unit,
                        retry: () -> Unit, fullScreen: Boolean, setFullScreen: (Boolean) -> Unit,
                        portrait: Boolean = false, fillPicture: Boolean = false, topBar: @Composable BoxScope.() -> Unit = {}) {
    val error = state?.error
    if (error != null && !fullScreen) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Retry(error, retry)
        topBar()
    } else if (fullScreen) Dialog(onDismissRequest = { setFullScreen(false) },
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        val window = (LocalView.current.parent as? DialogWindowProvider)?.window
        LaunchedEffect(window) {
            window?.let { dialog ->
                WindowCompat.getInsetsController(dialog, dialog.decorView).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }
        }
        val modifier = Modifier.fillMaxSize().background(Color.Black).windowInsetsPadding(WindowInsets.safeDrawing)
        val exit = mb("退出全屏", "Exit Fullscreen")
        val header: @Composable BoxScope.() -> Unit = {
            Row(Modifier.align(Alignment.TopCenter).fillMaxWidth().background(Brush.verticalGradient(
                listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent))).padding(end = 16.dp),
                verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { setFullScreen(false) }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, exit, tint = Color.White)
                }
                Text(playback.current?.title ?: mb("视频播放", "Video Playback"), Modifier.weight(1f), color = Color.White,
                    style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (error != null) Box(modifier, contentAlignment = Alignment.Center) {
            Retry(error, retry)
            header()
        } else VideoFrame(state?.player, state?.loading != false, playback, toggle, seek, modifier, qualityState = state, topBar = header)
    } else VideoFrame(state?.player, state?.loading != false, playback, toggle, seek, Modifier.fillMaxSize(), qualityState = state,
        enterFullscreen = { setFullScreen(true) }, portrait = portrait, fillPicture = fillPicture, topBar = topBar)
}

@OptIn(UnstableApi::class)
@Composable
private fun VideoFrame(engine: Player?, loading: Boolean, playback: PlaybackState, toggle: () -> Unit,
                       seek: (Long) -> Unit, modifier: Modifier, qualityState: VideoFrameState? = null, enterFullscreen: (() -> Unit)? = null,
                       portrait: Boolean = false, fillPicture: Boolean = false, topBar: @Composable BoxScope.() -> Unit = {}) {
    var visible by remember(playback.current?.id, playback.index) { mutableStateOf(true) }
    var revision by remember { mutableIntStateOf(0) }
    val interactions = remember { MutableInteractionSource() }
    val dragging by interactions.collectIsDraggedAsState()
    val focused by interactions.collectIsFocusedAsState()
    val timeout = LocalAccessibilityManager.current?.calculateRecommendedTimeoutMillis(4_000, containsControls = true) ?: 4_000L
    val currentToggle by rememberUpdatedState(toggle)
    val toggleControls = { visible = !visible; revision += 1 }
    val pictureLabel = mb("视频画面", "Video Picture")
    val controlsLabel = if (visible) mb("隐藏控制条", "Hide Controls") else mb("显示控制条", "Show Controls")
    val toggleLabel = if (playback.playWhenReady) mb("暂停", "Pause") else mb("播放", "Play")
    LaunchedEffect(visible, loading, playback.playing, dragging, focused, revision, timeout) {
        if (visible && !loading && playback.playing && !dragging && !focused) {
            delay(timeout)
            visible = false
        }
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        AndroidView(factory = { context -> (if (portrait) {
            // 竖屏视频会随评论面板缩放，使用 Media3 支持的 TextureView 与 Compose 一起合成。
            android.view.LayoutInflater.from(context).inflate(com.xtreeroot.mb.R.layout.portrait_video_view, null) as PlayerView
        } else PlayerView(context)).apply {
            useController = false
        } },
            update = { view ->
                view.resizeMode = if (fillPicture) androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                    else androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                view.player = engine
            }, onRelease = { view -> view.player = null },
            modifier = Modifier.fillMaxSize())
        if (loading) Loading()
        val ratio = qualityState?.aspectRatio ?: playback.current?.let {
            if (it.videoWidth > 0 && it.videoHeight > 0) it.videoWidth.toFloat() / it.videoHeight else 16f / 9f
        } ?: 16f / 9f
        VideoAdjustmentControls(enabled = !portrait && ratio >= 1f, modifier = Modifier.matchParentSize()) {
            Box(Modifier.matchParentSize().pointerInput(playback.current?.id, playback.index) {
                detectTapGestures(onTap = { if (portrait) currentToggle() else toggleControls() }, onDoubleTap = { currentToggle(); revision += 1 })
            }.semantics {
                contentDescription = pictureLabel
                onClick(label = if (portrait) toggleLabel else controlsLabel) { if (portrait) currentToggle() else toggleControls(); true }
                customActions = listOf(CustomAccessibilityAction(toggleLabel) { currentToggle(); revision += 1; true })
            })
        }
        if (portrait && !playback.playWhenReady && !loading) {
            Icon(Icons.Default.PlayArrow, null,
                modifier = Modifier.padding(24.dp), tint = Color.White)
        }
        if (visible && !portrait) {
            topBar()
            MaterialTheme(colorScheme = darkColorScheme(primary = Color.White)) {
                CompositionLocalProvider(LocalContentColor provides Color.White) {
                    Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.82f))))
                        .padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        PlayButton(playback, { currentToggle(); revision += 1 })
                        Column(Modifier.weight(1f).padding(bottom = 6.dp)) {
                            PlayerProgress(playback, smoke = false, seek = { seek(it); revision += 1 }, interactions = interactions)
                        }
                        VideoQualityMenu(qualityState)
                        if (enterFullscreen != null) IconButton(onClick = enterFullscreen) {
                            Icon(androidx.compose.ui.res.painterResource(com.xtreeroot.mb.R.drawable.ic_fullscreen), mb("全屏播放", "Fullscreen"), tint = Color.White)
                        }
                    }
                }
            }
        }
    }
}
