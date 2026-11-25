package com.xtreeroot.mb.playback

import android.app.Application
import android.content.ComponentName
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.VideoSource
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.PlaybackIssue
import com.xtreeroot.mb.preferences.PlaybackMode
import com.xtreeroot.mb.ui.LoadState
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

internal const val playbackLogTag = "MBPlayback"

internal enum class PlaybackConnectionIssue { Unavailable, Disconnected }

internal data class PlaybackState(
    val tracks: List<Track> = emptyList(),
    val index: Int = -1,
    val playWhenReady: Boolean = false,
    val loading: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val shuffle: Boolean = false,
    val error: String? = null,
    val connected: Boolean = false,
    val playing: Boolean = false,
    val videoAvailable: Boolean = false,
    val presentation: PlaybackPresentation = PlaybackPresentation(),
    val station: PlaybackStation? = null,
    val seekable: Boolean = false,
    val connectionIssue: PlaybackConnectionIssue? = null,
    val audioFocusBlocked: Boolean = false,
    val selectionRevision: Long = 0,
    val queuePages: QueuePageStatus = QueuePageStatus(),
) {
    val current: Track? get() = tracks.getOrNull(index)
}

internal class PlaybackModel(application: Application) : AndroidViewModel(application) {
    private sealed interface PlaybackRequest
    private data class Selection(val tracks: List<Track>, val track: Track, val station: PlaybackStation?,
                                 val shuffle: Boolean, val mode: PlaybackMode, val pages: PlaybackPages?,
                                 val playlist: String?, val startPositionMs: Long) : PlaybackRequest
    private data class QueueQuery(val query: String) : PlaybackRequest

    private val app = application as MBApplication
    private val state = MutableStateFlow(PlaybackState())
    private val captionState = MutableStateFlow(LoadState(emptyList<CaptionLine>()))
    private var captionJob: Job? = null
    private var captionTrackId: String? = null
    private var contentSelection: Pair<String, PlaybackMode>? = null
    private var videoSource: VideoSource? = null
    private var videoCapability: Boolean? = false
    private var videoJob: Job? = null
    private var controller: MediaController? = null
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var pendingRequest: PlaybackRequest? = null
    private var selectionRevision = 0L
    val playback = state.asStateFlow()
    val captions = captionState.asStateFlow()

    init {
        connect()
        viewModelScope.launch {
            app.installedPlugins.collect { publish() }
        }
        viewModelScope.launch { app.stationStatus.collect { publish() } }
        viewModelScope.launch { app.queuePageStatus.collect { publish() } }
        viewModelScope.launch { app.trackPlaybackIssues.values.collect { publish() } }
        viewModelScope.launch { app.audioFocusBlockedTrack.collect { publish() } }
        viewModelScope.launch {
            while (true) {
                delay(if (captionTrackId == state.value.current?.id && captionTrackId != null) 70 else 250)
                val player = controller ?: continue
                if (player.isPlaying) state.value = state.value.copy(positionMs = player.currentPosition)
            }
        }
    }

    private fun connect() {
        if (controllerFuture != null) return
        state.value = state.value.copy(connectionIssue = null)
        val attempt = try {
            val token = SessionToken(app, ComponentName(app, PlaybackService::class.java))
            MediaController.Builder(app, token).setListener(object : MediaController.Listener {
                override fun onDisconnected(controller: MediaController) {
                    if (this@PlaybackModel.controller === controller) connectionFailed(PlaybackConnectionIssue.Disconnected)
                }
            }).buildAsync()
        } catch (failure: RuntimeException) {
            Log.w(playbackLogTag, "session token/controller: ${failure.javaClass.simpleName}")
            connectionFailed(PlaybackConnectionIssue.Unavailable)
            return
        }
        controllerFuture = attempt
        attempt.addListener({
            if (controllerFuture !== attempt) return@addListener
            val player = try { attempt.get() } catch (failure: Exception) {
                Log.w(playbackLogTag, "controller handshake: ${(failure.cause ?: failure).javaClass.simpleName}")
                null
            }
            if (player == null || !player.isConnected) {
                connectionFailed(PlaybackConnectionIssue.Unavailable)
                return@addListener
            }
            controller = player
            Log.i(playbackLogTag, "controller connected")
            player.addListener(object : Player.Listener {
                override fun onEvents(player: Player, events: Player.Events) {
                    if (events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION)) selectionRevision++
                    publish()
                }
            })
            val request = pendingRequest
            pendingRequest = null
            when (request) {
                is Selection -> startSelection(player, request)
                is QueueQuery -> startQueueSearch(player, request.query)
                null -> Unit
            }
            publish()
        }, ContextCompat.getMainExecutor(app))
    }

    private fun connectionFailed(issue: PlaybackConnectionIssue) {
        Log.w(playbackLogTag, "controller failed: $issue")
        selectionRevision++
        releaseController()
        contentSelection = null
        captionJob?.cancel()
        captionTrackId = null
        captionState.value = LoadState(emptyList())
        videoJob?.cancel()
        videoSource = null
        videoCapability = false
        state.value = PlaybackState(presentation = state.value.presentation.update(false), connectionIssue = issue,
            selectionRevision = selectionRevision)
    }

    private fun releaseController() {
        val attempt = controllerFuture
        controller = null
        controllerFuture = null
        if (attempt != null) MediaController.releaseFuture(attempt)
    }

    override fun onCleared() = releaseController()

    fun play(tracks: List<Track>, selected: Track, station: PlaybackStation? = null, enableShuffle: Boolean = false, pages: PlaybackPages? = null,
             playlist: String? = null, startPositionMs: Long = 0) {
        if (selected.isUnavailable) return
        val selection = Selection(tracks, selected, station, enableShuffle, app.settings.values.value.playbackMode, pages, playlist, startPositionMs)
        val player = controller
        if (player == null) {
            pendingRequest = selection.copy(tracks = tracks.toList())
            connect()
            return
        }
        startSelection(player, selection)
        publish()
    }

    fun playFromSearch(query: String) {
        val bounded = query.take(256)
        val player = controller
        if (player == null) {
            pendingRequest = QueueQuery(bounded)
            connect()
            return
        }
        startQueueSearch(player, bounded)
        publish()
    }

    private fun startQueueSearch(player: MediaController, query: String) {
        val index = player.queueSearchIndex(query, ::canPlay) ?: return
        if (index != player.currentMediaItemIndex || player.playbackState == Player.STATE_ENDED) player.seekToDefaultPosition(index)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.play()
    }

    private fun startSelection(player: MediaController, selection: Selection) {
        val selected = selection.track
        if (!app.acceptsTrack(selected)) {
            app.trackPlaybackIssues.failed(selected, PlaybackIssue.SourceUnavailable)
            return
        }
        val playable = selection.tracks.filter(::canPlay)
        val index = playable.indexOfFirst { it.id == selected.id }
        if (index < 0) return
        selectionRevision++
        contentSelection = selected.id to selection.mode
        val pages = selection.pages?.let(app.pendingPlaybackPages::offer)
        player.setMediaItems(playable.mapIndexed { position, track ->
            val item = track.mediaItem()
            if (position != index) return@mapIndexed item
            val configured = when {
                selection.station != null -> item.withStation(selection.station)
                pages != null -> item.withPages(pages)
                else -> item
            }
            selection.playlist?.let(configured::withPlaylistResume) ?: configured
        }, index, selection.startPositionMs.coerceAtLeast(0))
        if (selection.shuffle) player.shuffleModeEnabled = true
        player.prepare()
        player.play()
    }

    fun playGroup(tracks: List<Track>, shuffle: Boolean) {
        val selected = tracks.firstOrNull(::canPlay) ?: tracks.firstOrNull { !it.isUnavailable } ?: return
        play(tracks, selected, enableShuffle = shuffle)
    }

    private fun canPlay(track: Track): Boolean = !track.isUnavailable && app.acceptsTrack(track)

    fun watchCurrentVideo() {
        val previous = state.value
        if (previous.current == null || !previous.videoAvailable) return
        state.value = previous.copy(presentation = previous.presentation.update(true, PlaybackMode.Video))
    }

    fun closeVideo() {
        state.value = state.value.let { it.copy(presentation = it.presentation.copy(mode = PlaybackMode.Music)) }
    }

    private fun resolveVideoCapability(track: Track?): Boolean? {
        val source = track?.let { app.playbackSource(it.sourceId) } as? VideoSource
        if (videoSource === source) return videoCapability
        videoSource = source
        videoJob?.cancel()
        videoCapability = if (source == null) false else null
        if (source != null) videoJob = viewModelScope.launch {
            yield()
            videoCapability = try { source.supportsVideo() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { false }
            publish()
        }
        return videoCapability
    }

    fun enqueue(track: Track, next: Boolean) {
        if (track.isUnavailable) return
        val player = controller ?: return
        player.sendCustomCommand(enqueueTrack, Bundle().apply {
            putString("track", track.toMetadataJson().toString())
            putBoolean("next", next)
        })
    }

    fun toggle() {
        val player = controller ?: return
        if (player.mediaItemCount == 0) return
        if (audioFocusBlocked(player)) { start(player); return }
        if (player.playerError == null && !player.isPlaying && atStationTail(player) &&
            (player.playbackState == Player.STATE_ENDED || stationError(player) != null)) {
            requestStationNext(player)
            return
        }
        if (player.playWhenReady && player.playerError == null) player.pause() else start(player)
    }

    fun retry() {
        val player = controller ?: return connect()
        if (audioFocusBlocked(player)) start(player)
        else if (player.playerError != null) player.prepare()
        else if (stationError(player) != null) requestStationNext(player)
    }

    fun retryVideo() {
        val video = app.videoPlayback.value ?: return
        video.retry()
        controller?.takeIf { it.playerError != null }?.prepare()
    }

    private fun requestStationNext(player: MediaController) { player.sendCustomCommand(nextStationTrack, Bundle.EMPTY) }

    fun endStation() { controller?.sendCustomCommand(leaveStation, Bundle.EMPTY) }

    private fun audioFocusBlocked(player: Player): Boolean = !player.isPlaying &&
        app.audioFocusBlockedTrack.value?.let { it == player.currentMediaItem?.mediaId } == true

    private fun atStationTail(player: Player): Boolean = app.stationStatus.value.station != null &&
        player.mediaItemCount > 0 && player.currentMediaItemIndex == player.mediaItemCount - 1

    private fun stationError(player: Player): String? = if (!atStationTail(player)) null else when (app.stationStatus.value.failure) {
        StationFailure.Empty -> "电台暂无可播放曲目"
        StationFailure.Load -> "电台加载失败"
        null -> null
    }

    private fun start(player: Player) {
        when (player.playbackState) {
            Player.STATE_IDLE -> player.prepare()
            Player.STATE_ENDED -> player.seekToDefaultPosition()
        }
        player.play()
    }

    fun select(index: Int) {
        controller?.let { player ->
            if (index in 0 until player.mediaItemCount) {
                selectionRevision++
                contentSelection = null
                player.seekToDefaultPosition(index)
                start(player)
                publish()
            }
        }
    }

    fun move(from: Int, to: Int, queue: List<Track>) {
        val player = controller ?: return
        if (from == to || from !in queue.indices || to !in queue.indices || !player.matchesQueue(queue)) return
        player.moveMediaItem(from, to)
    }

    fun remove(index: Int, queue: List<Track>) {
        val player = controller ?: return
        if (index !in queue.indices || !player.matchesQueue(queue)) return
        if (index != player.currentMediaItemIndex) {
            player.removeMediaItem(index)
            return
        }
        if (queue.size == 1) {
            clear()
            return
        }
        contentSelection = null
        val replacement = if (index == queue.lastIndex) index - 1 else index + 1
        player.seekToDefaultPosition(replacement)
        player.removeMediaItem(index)
        start(player)
    }

    private fun MediaController.matchesQueue(queue: List<Track>): Boolean = mediaItemCount == queue.size &&
        queue.indices.all { index -> getMediaItemAt(index).mediaId == queue[index].id }
    fun removeSource(sourceId: String) {
        val player = controller ?: return
        for (index in player.mediaItemCount - 1 downTo 0) {
            val track = player.getMediaItemAt(index).track() ?: continue
            if (track.sourceId != sourceId) continue
            if (contentSelection?.first == track.id) contentSelection = null
            player.removeMediaItem(index)
        }
    }
    fun clear() {
        pendingRequest = null
        contentSelection = null
        controller?.clearMediaItems()
    }
    fun seekCurrent(selection: PlaybackState, positionMs: Long) {
        val player = controller ?: return
        val trackId = selection.current?.id ?: return
        if (selection.selectionRevision != selectionRevision || player.currentMediaItemIndex != selection.index ||
            player.currentMediaItem?.mediaId != trackId) return
        when {
            player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) -> player.seekTo(positionMs)
            player.canPositionUnpreparedItem -> player.seekTo(selection.index, positionMs)
        }
    }
    fun previous() = step(forward = false)
    fun next() = step(forward = true)
    fun loadNextQueuePage() {
        controller?.sendCustomCommand(loadQueuePage, Bundle.EMPTY)
    }

    /** 与手动切歌共用 Media3 的实际随机顺序；预览不会改变播放状态。 */
    fun neighboringTrack(forward: Boolean): Track? {
        val player = controller ?: return null
        if (forward && atStationTail(player)) return null
        val index = player.manualNeighbor(forward)
        return if (index in 0 until player.mediaItemCount) player.getMediaItemAt(index).track() else null
    }

    fun didSwitchSource(id: String, pauseOtherSources: Boolean) {
        if (pauseOtherSources && state.value.current?.sourceId?.let { it != id } == true) controller?.pause()
    }

    private fun step(forward: Boolean) {
        val player = controller ?: return
        if (forward && atStationTail(player)) { requestStationNext(player); return }
        if (forward && player.isCommandAvailable(Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM)) {
            contentSelection = null
            player.seekToNextMediaItem()
            return
        }
        select(player.manualNeighbor(forward))
    }

    fun shuffle(): Boolean? {
        val player = controller?.takeIf { it.isCommandAvailable(Player.COMMAND_SET_SHUFFLE_MODE) } ?: return null
        val previous = player.shuffleModeEnabled
        player.shuffleModeEnabled = !previous
        return player.shuffleModeEnabled.takeIf { it != previous }
    }

    fun repeat(): Int? {
        val player = controller?.takeIf { it.isCommandAvailable(Player.COMMAND_SET_REPEAT_MODE) } ?: return null
        val previous = player.repeatMode
        player.repeatMode = when (previous) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        return player.repeatMode.takeIf { it != previous }
    }

    fun loadCaptions(track: Track) {
        if (captionTrackId == track.id && captionJob?.isActive == true) return
        captionJob?.cancel()
        captionTrackId = track.id
        captionState.value = LoadState(emptyList(), loading = true)
        captionJob = viewModelScope.launch {
            try {
                app.observeCaptions(track).collect { lines -> captionState.value = LoadState(lines) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                captionState.value = LoadState(emptyList(), error = failure.message ?: "字幕加载失败")
            }
        }
    }

    private fun publish() {
        val player = controller ?: return
        val tracks = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).track() }
        val index = player.currentMediaItemIndex
        val current = tracks.getOrNull(index)
        val capability = resolveVideoCapability(current)
        val chosen = contentSelection?.takeIf { capability != null && it.first == current?.id }
        if (chosen != null || player.playerError != null) contentSelection = null
        val presentation = state.value.presentation.update(capability == true, chosen?.second)
        if (captionTrackId != current?.id) {
            captionJob?.cancel()
            captionTrackId = null
            captionState.value = LoadState(emptyList())
        }
        val error = player.playerError?.let { failure ->
            val detail = current?.playbackKey?.let(app.trackPlaybackIssues.values.value::get)
                ?.takeIf { it.code == failure.errorCode }?.diagnostic ?: failure.errorCodeName
            "播放失败（$detail）"
        } ?: stationError(player)
        state.value = PlaybackState(tracks, index, player.playWhenReady,
            player.playbackState == Player.STATE_BUFFERING, player.currentPosition,
            player.duration.takeUnless { it == C.TIME_UNSET } ?: current?.durationMs ?: 0,
            player.repeatMode, player.shuffleModeEnabled,
            error, connected = true, playing = player.isPlaying,
            videoAvailable = capability == true, presentation = presentation, station = app.stationStatus.value.station,
            seekable = player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM) || player.canPositionUnpreparedItem,
            audioFocusBlocked = audioFocusBlocked(player), selectionRevision = selectionRevision, queuePages = app.queuePageStatus.value)
    }
}

private val Player.canPositionUnpreparedItem: Boolean
    get() = playbackState == Player.STATE_IDLE && playerError == null && isCommandAvailable(Player.COMMAND_SEEK_TO_MEDIA_ITEM)
