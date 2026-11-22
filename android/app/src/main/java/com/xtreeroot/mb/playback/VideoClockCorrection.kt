package com.xtreeroot.mb.playback

import kotlin.math.abs

internal data class VideoClockCorrection(val seek: Boolean, val speed: Float)

/** 小幅漂移用静音视频的速度修正；较大漂移才 seek，且避免缓冲后连续 seek。 */
internal fun videoClockCorrection(driftMs: Long, playing: Boolean, sinceLastSeekMs: Long): VideoClockCorrection {
    val distance = abs(driftMs)
    val seek = distance > (if (playing) 800 else 80) && sinceLastSeekMs >= 1_500
    val speed = if (playing && !seek && distance in 81..800) {
        if (driftMs > 0) 1.03f else 0.97f
    } else 1f
    return VideoClockCorrection(seek, speed)
}
