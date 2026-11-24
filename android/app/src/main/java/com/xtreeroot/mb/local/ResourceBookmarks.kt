package com.xtreeroot.mb.local

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.music.toMetadataJson
import com.xtreeroot.mb.music.toTrackMetadata
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray

/** 在线资源只保存元数据，不改变现有音频、歌单与下载数据。 */
internal class ResourceBookmarks(private val file: File) {
    private val mutable = MutableStateFlow(emptyList<Track>())
    val tracks = mutable.asStateFlow()
    @Synchronized fun refresh() {
        val entries = if (file.isFile) JSONArray(file.readText()) else JSONArray()
        mutable.value = (0 until entries.length()).map { entries.getJSONObject(it).toTrackMetadata() }.distinctBy(Track::id)
    }
    @Synchronized fun save(track: Track) {
        refresh()
        write(listOf(track) + mutable.value.filterNot { it.id == track.id })
    }
    @Synchronized fun remove(id: String) { refresh(); write(mutable.value.filterNot { it.id == id }) }
    private fun write(tracks: List<Track>) {
        writeAtomic(file, JSONArray(tracks.map { it.toMetadataJson() }).toString())
        mutable.value = tracks
    }
}
