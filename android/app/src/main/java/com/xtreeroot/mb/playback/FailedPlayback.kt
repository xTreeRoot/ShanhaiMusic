package com.xtreeroot.mb.playback

import androidx.media3.common.C
import androidx.media3.common.Player

/** 保留完整队列，按播放器实际随机顺序寻找后继；错误恢复不受单曲循环限制。 */
internal fun Player.nextPlayable(skipped: Set<String>, wrap: Boolean, from: Int = currentMediaItemIndex): Int {
    val timeline = currentTimeline
    if (from !in 0 until timeline.windowCount) return C.INDEX_UNSET
    var index = from
    repeat(timeline.windowCount) {
        index = timeline.getNextWindowIndex(index, if (wrap) Player.REPEAT_MODE_ALL else Player.REPEAT_MODE_OFF, shuffleModeEnabled)
        if (index == C.INDEX_UNSET) return C.INDEX_UNSET
        val item = getMediaItemAt(index)
        if (item.mediaId !in skipped && item.track()?.isUnavailable != true) return index
    }
    return C.INDEX_UNSET
}
