package com.xtreeroot.mb.music

data class Track(
    val sourceId: String,
    val sourceTrackId: String,
    val title: String,
    val artist: String,
    val artworkUrl: String? = null,
    val durationMs: Long = 0,
    val pageUrl: String? = null,
    val artistId: String? = null,
    val artworkHeaders: Map<String, String> = emptyMap(),
    val unavailable: Boolean = false,
    val unavailableReason: TrackUnavailableReason? = null,
    val resourceType: String? = null,
    val resourceUrl: String? = null,
    val videoWidth: Int = 0,
    val videoHeight: Int = 0,
    val shortVideo: Boolean = false,
) {
    val id: String get() = "$sourceId:$sourceTrackId"
    val isUnavailable: Boolean get() = unavailable || unavailableReason != null
}

data class TrackPage(val tracks: List<Track>, val hasMore: Boolean, val warnings: List<String> = emptyList())

enum class DiscoveryCategory {
    Recommended, Chart, Trending, Fresh,
}

data class DiscoverySection(
    val category: DiscoveryCategory,
    val tracks: List<Track> = emptyList(),
    val week: Long? = null,
    val error: String? = null,
)

data class MediaResource(val url: String, val headers: Map<String, String>, val fileExtension: String = "m4a",
                         val backupUrls: List<String> = emptyList(), val videoQuality: String? = null,
                         val videoQualities: List<VideoQualityOption> = emptyList())

enum class AudioQuality { Standard, High, Lossless }

data class CaptionWord(val startMs: Long, val endMs: Long, val text: String) {
    fun progress(positionMs: Long): Float = when {
        positionMs < startMs -> 0f
        positionMs >= endMs -> 1f
        else -> (positionMs - startMs).toFloat() / (endMs - startMs)
    }
}

data class CaptionLine(val startMs: Long, val endMs: Long, val text: String, val translation: String? = null,
                       val words: List<CaptionWord> = emptyList())

interface MusicSource {
    val id: String
    suspend fun search(query: String, page: Int): TrackPage
}

internal interface SuggestionSource : MusicSource {
    suspend fun suggestions(query: String): List<String>
}

interface DiscoverySource : MusicSource {
    suspend fun home(): List<DiscoverySection>
    suspend fun discovery(category: DiscoveryCategory): DiscoverySection
}

interface PlaybackSource : MusicSource {
    suspend fun audio(trackId: String): MediaResource
    suspend fun audio(trackId: String, quality: AudioQuality): MediaResource = audio(trackId)
    suspend fun captions(trackId: String): List<CaptionLine>
}

internal interface PlaybackRecorder : MusicSource {
    suspend fun played(trackId: String)
}

internal interface StationPlaybackSource : MusicSource {
    suspend fun stationNext(id: String): List<Track>
}

interface VideoSource : MusicSource {
    suspend fun video(trackId: String, qualityID: String): MediaResource = video(trackId)
    suspend fun supportsVideo(): Boolean = true
    suspend fun video(trackId: String): MediaResource
    suspend fun video(trackId: String, quality: AudioQuality): MediaResource = video(trackId)
}
