package com.xtreeroot.mb.preferences

import kotlin.math.ceil
import kotlin.math.floor

internal data class BackgroundCropRect(val left: Int, val top: Int, val width: Int, val height: Int)

/** 将归一化拖动和缩放转换为原图像素，裁剪区始终在图片内。 */
internal fun backgroundCropRect(width: Int, height: Int, aspect: Double, zoom: Double, horizontal: Double, vertical: Double): BackgroundCropRect {
    require(width > 0 && height > 0 && aspect.isFinite() && aspect > 0 && zoom.isFinite() && horizontal.isFinite() && vertical.isFinite())
    val cropWidth = minOf(width.toDouble(), height * aspect) / zoom.coerceIn(1.0, 4.0)
    val cropHeight = minOf(height.toDouble(), width / aspect) / zoom.coerceIn(1.0, 4.0)
    val x = (width - cropWidth) * (1 - horizontal.coerceIn(-1.0, 1.0)) / 2
    val y = (height - cropHeight) * (1 - vertical.coerceIn(-1.0, 1.0)) / 2
    val left = floor(x).toInt().coerceIn(0, width - 1)
    val top = floor(y).toInt().coerceIn(0, height - 1)
    return BackgroundCropRect(left, top, (ceil(x + cropWidth).toInt().coerceAtMost(width) - left).coerceAtLeast(1),
        (ceil(y + cropHeight).toInt().coerceAtMost(height) - top).coerceAtLeast(1))
}
