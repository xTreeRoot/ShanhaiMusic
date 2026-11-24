package com.xtreeroot.mb.playback

import android.content.Context
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.net.ConnectivityManager
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import com.xtreeroot.mb.music.MediaNetworkConditions
import androidx.media3.common.PlaybackParameters
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.video.PlaceholderSurface
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import com.xtreeroot.mb.music.VideoQualityOption
import com.xtreeroot.mb.music.MediaResource
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.VideoSource
import kotlin.math.abs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient

internal data class VideoFrameState(val trackId: String, val player: Player? = null, val loading: Boolean = true,
                                    val error: String? = null, val aspectRatio: Float? = null,
                                    val quality: String? = null, val qualities: List<VideoQualityOption> = emptyList())

@OptIn(UnstableApi::class)
internal class VideoPlayback(private val context: Context, private val http: OkHttpClient, private val audio: ExoPlayer,
                             private val scope: CoroutineScope, private val sourceFor: (Track) -> VideoSource?,
                             private val quality: () -> String) : Player.Listener {
    private class Stage(val track: Track, val source: VideoSource, var quality: String) {
        var resource: MediaResource? = null
        var engine: ExoPlayer? = null
        var surface: PlaceholderSurface? = null
        var firstFrame = false
        var recovery = MediaRecoveryBudget()
        var job: Job? = null
        var lastCorrection = 0L
        var prefix: MediaRangeGuard.Prefix? = null
    }

    private data class NextTarget(val track: Track, val source: VideoSource, val quality: String, val revision: Long = source.mediaRevision)
    private val nextPreloader = NextAudioPreloader<NextTarget, PreparedAudio>(scope, SystemClock::elapsedRealtime) { next ->
        val resource = withContext(Dispatchers.IO) { next.source.video(next.track.sourceTrackId, next.quality) }
        primeAudio(http, resource)
    }
    private fun nextTarget(track: Track?) = track?.takeUnless { it.isUnavailable }?.let { value ->
        sourceFor(value)?.let { NextTarget(value, it, quality()) }
    }
    /** 复用有大小、寿命和取消限制的首块预载器，不创建下一条的解码器。 */
    fun preloadNext(track: Track?) { if (!closed) { updateNetwork(); nextPreloader.update(nextTarget(track)) } }

    private var active: Stage? = null
    private var warm: Stage? = null
    private var closed = false
    private val mutable = MutableStateFlow<VideoFrameState?>(null)
    val frames = mutable.asStateFlow()
    // 与可见画面分开发布，后台预载不会把音频会话切成“视频缓冲中”。
    private val preloadState = MutableStateFlow<VideoFrameState?>(null)
    val preloaded = preloadState.asStateFlow()
    var onGateChanged: () -> Unit = {}
    var heldIntent: Boolean? = null
        private set

    private val bandwidth = DefaultBandwidthMeter.getSingletonInstance(context)
    private val bandwidthListener = androidx.media3.exoplayer.upstream.BandwidthMeter.EventListener { elapsed, bytes, bitrate ->
        MediaNetworkConditions.sample(bytes, elapsed, bitrate)
    }
    init {
        audio.addListener(this)
        bandwidth.addEventListener(Handler(Looper.getMainLooper()), bandwidthListener)
    }
    private fun updateNetwork() {
        MediaNetworkConditions.constrained = context.getSystemService(ConnectivityManager::class.java)?.isActiveNetworkMetered == true
    }

    /** 用户画质变化只重新准备静音视频，保留当前音频、进度、播放意图和画面拥有者。 */
    fun qualityChanged() {
        nextPreloader.cancel()
        listOfNotNull(active, warm).forEach { stage ->
            if (stage.quality != quality()) {
                stage.quality = quality()
                stage.resource = null
                stage.recovery = MediaRecoveryBudget()
                if (stage === active) hold()
                load(stage, audio.currentPosition)
            }
        }
    }

    /** 仅预载当前正在播放的资源，ExoPlayer 的独立加载/解码线程负责流式缓冲。 */
    fun preload(track: Track?) {
        if (closed) return
        val target = track?.takeIf { audio.currentMediaItem?.mediaId == it.id }
        val source = target?.let(sourceFor)
        if (target == null || source == null) { clearWarm(); return }
        if (active?.track?.id == target.id) return
        if (warm?.let { it.track.id == target.id && it.source === source && it.quality == quality() } == true) return
        clearWarm()
        val stage = Stage(target, source, quality())
        warm = stage
        load(stage, audio.currentPosition)
    }

    fun show(track: Track): AutoCloseable? {
        if (closed || audio.currentMediaItem?.mediaId != track.id) return null
        updateNetwork()
        val source = sourceFor(track) ?: return null
        val stage = warm?.takeIf { it.track.id == track.id && it.source === source && it.quality == quality() }
            ?: Stage(track, source, quality())
        if (warm === stage) { warm = null; preloadState.value = null } else clearWarm()
        active?.let(::retire)
        active = stage
        val engine = stage.engine
        if (engine != null) {
            // 已就绪且追上音频时不暂停音频；PlayerView 接管同一个播放器和已有缓冲。
            if (abs(engine.currentPosition - audio.currentPosition) > 800) {
                stage.firstFrame = false
                engine.seekTo(audio.currentPosition)
            }
            if (!stage.firstFrame || engine.playbackState != Player.STATE_READY) hold()
            mutable.value = VideoFrameState(track.id, engine, loading = heldIntent != null || !stage.firstFrame)
            synchronize(stage, engine)
        } else {
            stage.recovery = MediaRecoveryBudget()
            hold()
            if (audio.playbackState == Player.STATE_IDLE && audio.playerError == null) audio.prepare()
            load(stage, audio.currentPosition)
        }
        return AutoCloseable { if (active === stage) hide() }
    }

    fun retry() {
        val stage = active ?: return
        stage.recovery = MediaRecoveryBudget()
        stage.resource = null
        hold()
        load(stage, audio.currentPosition)
    }

    fun changeIntent(value: Boolean): Boolean {
        if (heldIntent == null) return false
        heldIntent = value
        onGateChanged()
        return true
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        nextPreloader.advance(nextTarget(mediaItem?.track()))
        if (mediaItem?.mediaId != active?.track?.id) hide()
        if (mediaItem?.mediaId != warm?.track?.id) clearWarm()
    }

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
        if (!playWhenReady && reason != Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST) changeIntent(false)
    }

    override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
        val stage = active ?: warm ?: return
        if (stage === warm) {
            if (newPosition.mediaItem?.mediaId == stage.track.id) stage.engine?.seekTo(newPosition.positionMs.coerceAtLeast(0))
            return
        }
        if (mutable.value?.error != null) return
        if ((reason != Player.DISCONTINUITY_REASON_SEEK && reason != Player.DISCONTINUITY_REASON_AUTO_TRANSITION) ||
            newPosition.mediaItem?.mediaId != stage.track.id) return
        val engine = stage.engine
        if (engine != null) {
            hold()
            stage.firstFrame = false
            stage.lastCorrection = SystemClock.elapsedRealtime()
            engine.seekTo(newPosition.positionMs.coerceAtLeast(0))
        } else { hold(); load(stage, newPosition.positionMs) }
    }

    override fun onEvents(player: Player, events: Player.Events) {
        (active ?: warm)?.let { stage -> stage.engine?.let { synchronize(stage, it) } }
    }

    private fun hold() {
        if (heldIntent != null) return
        heldIntent = audio.playWhenReady
        audio.pause()
        onGateChanged()
    }

    private fun releaseHold() {
        val intent = heldIntent ?: return
        heldIntent = null
        if (intent && audio.mediaItemCount > 0) audio.play()
        onGateChanged()
    }

    private fun hide() {
        val stage = active ?: return
        active = null
        mutable.value = null
        if (!closed && (audio.playWhenReady || heldIntent == true) && audio.currentMediaItem?.mediaId == stage.track.id && stage.engine != null && audio.playerError == null) {
            clearWarm()
            warm = stage
            try { attachPlaceholder(stage) } catch (_: RuntimeException) { clearWarm() }
        } else retire(stage)
        releaseHold()
        if (warm === stage) stage.engine?.let { synchronize(stage, it) }
    }

    private fun clearWarm() {
        val previous = warm
        warm = null
        preloadState.value = null
        previous?.let(::retire)
    }

    private fun attachPlaceholder(stage: Stage) {
        val surface = stage.surface ?: PlaceholderSurface.newInstance(context, false).also { stage.surface = it }
        stage.engine?.setVideoSurface(surface)
    }

    private fun retire(stage: Stage) {
        stage.job?.cancel()
        val engine = stage.engine
        stage.engine = null
        engine?.release()
        stage.surface?.release()
        stage.surface = null
    }

    private fun fail(stage: Stage, message: String) {
        retire(stage)
        if (active === stage) {
            mutable.value = VideoFrameState(stage.track.id, loading = false, error = message,
                quality = stage.resource?.videoQuality, qualities = stage.resource?.videoQualities.orEmpty())
            releaseHold()
        } else if (warm === stage) preloadState.value = VideoFrameState(stage.track.id, loading = false, error = message)
    }

    fun close() {
        closed = true
        nextPreloader.cancel()
        audio.removeListener(this)
        bandwidth.removeEventListener(bandwidthListener)
        onGateChanged = {}
        heldIntent = null
        hide()
        clearWarm()
    }

    private fun load(stage: Stage, position: Long, wait: Long = 0) {
        retire(stage)
        stage.firstFrame = false
        publish(stage, VideoFrameState(stage.track.id))
        stage.job = scope.launch {
            yield()
            delay(wait)
            if (!valid(stage)) { hideIfActive(stage); return@launch }
            val media = try {
                val prepared = if (stage.resource == null) nextPreloader.take(NextTarget(stage.track, stage.source, stage.quality)) else null
                stage.prefix = prepared?.prefix
                prepared?.resource ?: stage.resource ?: withContext(Dispatchers.IO) {
                    if (!stage.source.supportsVideo()) throw IllegalStateException("Video unavailable")
                    stage.source.video(stage.track.sourceTrackId, stage.quality)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                ensureActive()
                if (valid(stage)) fail(stage, "视频地址加载失败") else hideIfActive(stage)
                return@launch
            }
            ensureActive()
            if (!valid(stage)) { hideIfActive(stage); return@launch }
            stage.resource = media
            val guard = MediaRangeGuard()
            stage.prefix?.let(guard::offer); stage.prefix = null
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            val engine = try { ExoPlayer.Builder(context, renderers).setMediaSourceFactory(ProgressiveMediaSource.Factory {
                val upstream = guardedMediaHttp(http, guard).apply { media.headers.forEach(::setRequestProperty); addTransferListener(bandwidth) }
                MediaAddressDataSource(upstream).apply { backupUrls = media.backupUrls }
            }.recoverOutsideLoader()).setLoadControl(DefaultLoadControl.Builder()
                .setBufferDurationsMs(1_500, 8_000, 250, 500).setTargetBufferBytes(12 * 1024 * 1024).build()).build()
            } catch (_: RuntimeException) { fail(stage, "视频播放器初始化失败"); return@launch }
            stage.engine = engine
            engine.trackSelectionParameters = engine.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            engine.volume = 0f
            if (stage === warm) try { attachPlaceholder(stage) }
            catch (_: RuntimeException) { fail(stage, "视频预载初始化失败"); return@launch }
            engine.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (stage.engine === engine && valid(stage)) synchronize(stage, engine)
                }

                override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                    if (stage.engine === engine && valid(stage)) synchronize(stage, engine)
                }

                override fun onRenderedFirstFrame() {
                    if (stage.engine !== engine) return
                    stage.firstFrame = true
                    synchronize(stage, engine)
                }

                override fun onPlayerError(error: PlaybackException) {
                    if (stage.engine !== engine || !valid(stage)) return
                    val action = mediaRecovery(error)
                    val retry = action?.let(stage.recovery::delayFor)
                    if (retry != null) {
                        if (stage === active) hold()
                        if (action == MediaRecovery.RefreshAddress) stage.resource = null
                        load(stage, audio.currentPosition, retry)
                    } else {
                        fail(stage, "视频加载失败（${error.playbackDiagnostic(error.errorCode)}）")
                    }
                }
            })
            publish(stage, VideoFrameState(stage.track.id, engine))
            engine.setMediaItem(MediaItem.fromUri(media.url), position.coerceAtLeast(0))
            engine.prepare()
            while (valid(stage) && stage.engine === engine) {
                delay(150)
                synchronize(stage, engine)
            }
            if (!valid(stage)) hideIfActive(stage)
        }
    }

    private fun valid(stage: Stage): Boolean = !closed && (active === stage || warm === stage) && audio.currentMediaItem?.mediaId == stage.track.id &&
        sourceFor(stage.track) === stage.source

    private fun hideIfActive(stage: Stage) {
        if (active === stage) hide()
        if (warm === stage) clearWarm()
    }

    private fun publish(stage: Stage, frame: VideoFrameState) {
        val size = frame.player?.videoSize
        val ratio = size?.takeIf { it.width > 0 && it.height > 0 }?.let { it.width * it.pixelWidthHeightRatio / it.height }
        val measured = frame.copy(aspectRatio = ratio?.takeIf { it.isFinite() && it > 0f },
            quality = stage.resource?.videoQuality, qualities = stage.resource?.videoQualities.orEmpty())
        if (stage === active) mutable.value = measured
        else if (stage === warm) preloadState.value = measured
    }

    private fun correctClock(stage: Stage, video: ExoPlayer, playing: Boolean) {
        val drift = audio.currentPosition - video.currentPosition
        val now = SystemClock.elapsedRealtime()
        val correction = videoClockCorrection(drift, playing, now - stage.lastCorrection)
        if (correction.seek) { stage.lastCorrection = now; video.seekTo(audio.currentPosition) }
        if (video.playbackParameters.speed != correction.speed) video.playbackParameters = PlaybackParameters(correction.speed)
    }

    private fun synchronize(stage: Stage, video: ExoPlayer) {
        if (!valid(stage) || stage.engine !== video) return
        if (stage === warm) {
            // 后台视频只追随音频，不持有播放闸门，也不改变音频的暂停/恢复意图。
            if (audio.playbackState == Player.STATE_IDLE || audio.playbackState == Player.STATE_ENDED || audio.playerError != null) {
                clearWarm()
                return
            }
            if (video.playbackState == Player.STATE_READY) correctClock(stage, video, audio.isPlaying)
            if (video.playWhenReady != audio.isPlaying) video.playWhenReady = audio.isPlaying
            val loading = video.playbackState != Player.STATE_READY || !stage.firstFrame
            publish(stage, VideoFrameState(stage.track.id, video, loading))
            return
        }
        if (stage.firstFrame && video.playbackState == Player.STATE_BUFFERING) MediaNetworkConditions.stalled()
        val pictureReady = (video.playbackState == Player.STATE_READY || video.playbackState == Player.STATE_ENDED) && stage.firstFrame
        val ready = pictureReady && audio.playbackState == Player.STATE_READY
        if (heldIntent != null && ready) releaseHold()
        // 可见视频一进入缓冲就冻结音频时钟，让同一个解码器继续补缓冲。
        // 反复销毁播放器会丢弃已有数据，短视频还可能在画面出现前播完音频。
        if (heldIntent == null && audio.isPlaying && !pictureReady) hold()
        if (heldIntent == null && video.playbackState == Player.STATE_READY && pictureReady) correctClock(stage, video, audio.isPlaying)
        val playVideo = heldIntent == null && audio.isPlaying
        if (video.playWhenReady != playVideo) video.playWhenReady = playVideo
        val loading = !pictureReady || heldIntent != null
        publish(stage, VideoFrameState(stage.track.id, video, loading))
    }
}
