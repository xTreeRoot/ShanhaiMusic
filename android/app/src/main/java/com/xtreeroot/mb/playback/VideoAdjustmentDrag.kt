package com.xtreeroot.mb.playback

internal enum class VideoAdjustmentKind { Volume, Brightness }

/** 按本次拖动开始时的系统值计算，往返移动不会产生累积漂移。 */
internal data class VideoAdjustmentDrag(val kind: VideoAdjustmentKind, val initial: Float, val height: Float) {
    fun value(translationY: Float): Float {
        val minimum = if (kind == VideoAdjustmentKind.Brightness) .01f else 0f
        val baseline = if (initial.isFinite()) initial.coerceIn(minimum, 1f) else minimum
        if (!translationY.isFinite() || !height.isFinite() || height <= 0) return baseline
        return (baseline - translationY / (height * .75f)).coerceIn(minimum, 1f)
    }
}
