package com.xtreeroot.mb.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.xtreeroot.mb.preferences.backgroundCropRect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class CropRatio { Portrait, Square, Landscape, Original }

/** 裁剪只生成候选图，交给根页面在真实发现页预览。 */
@Composable
internal fun BackgroundImageEditor(image: Bitmap, busy: Boolean, failed: Boolean, cancel: () -> Unit, preview: (Bitmap) -> Unit) {
    var ratio by remember(image) { mutableStateOf(CropRatio.Portrait) }
    var zoom by remember(image) { mutableFloatStateOf(1f) }
    var pan by remember(image) { mutableStateOf(Offset.Zero) }
    var preparing by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val aspect = when (ratio) { CropRatio.Portrait -> 9.0 / 16; CropRatio.Square -> 1.0; CropRatio.Landscape -> 16.0 / 9; CropRatio.Original -> image.width.toDouble() / image.height }
    Dialog(onDismissRequest = { if (!busy && !preparing) { cancel() } },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = cancel, enabled = !busy && !preparing) { Text(mb("取消", "Cancel")) }
                    Text(mb("裁剪背景", "Crop Background"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = {
                        preparing = true
                        scope.launch {
                            val cropped = withContext(Dispatchers.Default) {
                                val rect = backgroundCropRect(image.width, image.height, aspect, zoom.toDouble(), pan.x.toDouble(), pan.y.toDouble())
                                Bitmap.createBitmap(image, rect.left, rect.top, rect.width, rect.height)
                            }
                            preparing = false
                            preview(cropped)
                        }
                    }, enabled = !busy && !preparing) { Text(mb("预览", "Preview")) }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    CropRatio.entries.forEach { option ->
                        val title = when (option) { CropRatio.Portrait -> mb("竖屏", "Portrait"); CropRatio.Square -> mb("方形", "Square"); CropRatio.Landscape -> mb("横屏", "Landscape"); CropRatio.Original -> mb("原图", "Original") }
                        FilterChip(selected = ratio == option, onClick = { ratio = option; zoom = 1f; pan = Offset.Zero }, label = { Text(title) })
                    }
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    val width = minOf(maxWidth, maxHeight * aspect.toFloat())
                    val height = width / aspect.toFloat()
                    val scale = maxOf(width.value / image.width, height.value / image.height) * zoom
                    val maxX = (image.width * scale - width.value).coerceAtLeast(0f) / 2
                    val maxY = (image.height * scale - height.value).coerceAtLeast(0f) / 2
                    val density = androidx.compose.ui.platform.LocalDensity.current.density
                    Box(Modifier.size(width, height).clipToBounds().background(Color.Black).border(1.dp, Color.White)
                        .semantics { contentDescription = "Background crop canvas" }
                        .pointerInput(image, zoom, ratio, maxX, maxY) {
                            detectDragGestures { change, delta ->
                                change.consume()
                                pan = Offset(if (maxX > 0) (pan.x + delta.x / density / maxX).coerceIn(-1f, 1f) else 0f,
                                    if (maxY > 0) (pan.y + delta.y / density / maxY).coerceIn(-1f, 1f) else 0f)
                            }
                        }, contentAlignment = Alignment.Center) {
                        Image(image.asImageBitmap(), null, Modifier.requiredSize((image.width * scale).dp, (image.height * scale).dp)
                            .offset { IntOffset((pan.x * maxX * density).roundToInt(), (pan.y * maxY * density).roundToInt()) }, contentScale = ContentScale.FillBounds)
                    }
                }
                Slider(zoom, { zoom = it }, valueRange = 1f..4f, modifier = Modifier.semantics { contentDescription = "Background zoom" })
                Text(mb("拖动图片调整位置", "Drag to adjust the crop"), style = MaterialTheme.typography.bodySmall)
                if (busy || preparing) CircularProgressIndicator()
                if (failed) Text(mb("无法更新背景图片", "Cannot update background image"), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
