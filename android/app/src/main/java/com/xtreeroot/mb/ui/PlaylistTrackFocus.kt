package com.xtreeroot.mb.ui

import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogPage

/** 页面进入时固定目标；曲目 ID 已包含音源，暂停与播放走同一条定位逻辑。 */
internal class PlaylistTrackFocus(val target: String?) {
    var finished = target == null
        private set

    fun locate(tracks: List<Track>, hasMore: Boolean): Int? {
        if (finished) return null
        val index = tracks.indexOfFirst { it.id == target }
        if (index >= 0) { finished = true; return index }
        if (!hasMore) finished = true
        return null
    }

    fun stop() { finished = true }
}

/** Grid 的分区标题和续播栏也占条目，不能直接把曲目下标当作 Grid 下标。 */
internal fun playlistGridTrackIndex(page: PluginCatalogPage, target: String, resumeHeader: Boolean): Int? {
    var row = if (resumeHeader) 1 else 0
    page.sections.forEach { section ->
        if (section.title.isNotBlank()) row++
        section.tracks.forEach { track ->
            if (track.id == target) return row
            row++
        }
        row += section.entities.size + section.links.size
        if (section.more != null) row++
        if (section.empty.isNotBlank() && section.tracks.isEmpty() && section.entities.isEmpty() && section.links.isEmpty()) row++
    }
    return null
}
