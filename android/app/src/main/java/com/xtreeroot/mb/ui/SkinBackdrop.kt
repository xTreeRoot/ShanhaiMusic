package com.xtreeroot.mb.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.preferences.BackgroundAccent
import com.xtreeroot.mb.preferences.BackgroundSkinStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal val LocalSkinEnabled = staticCompositionLocalOf { false }

internal fun BackgroundAccent.color(fallback: Color): Color = when (this) {
    BackgroundAccent.System -> fallback
    BackgroundAccent.Peach -> Color(0xFFE0A692)
    BackgroundAccent.Rose -> Color(0xFFC4526B)
    BackgroundAccent.Silver -> Color(0xFF6E7887)
    BackgroundAccent.Forest -> Color(0xFF337A63)
    BackgroundAccent.Brick -> Color(0xFFB03D30)
    BackgroundAccent.Red -> Color(0xFFDB303B)
    BackgroundAccent.Orange -> Color(0xFFDE5E29)
}

@Composable
internal fun skinSurface(base: Color = MaterialTheme.colorScheme.surface): Color =
    if (LocalSkinEnabled.current) base.copy(alpha = 0.14f) else base

/** 根画布覆盖系统栏，所有内容和导航共享一张背景，避免各自裁切或不透明底色遮挡。 */
@Composable
internal fun AppearanceBackdrop(image: Bitmap?, dark: Boolean, opacity: Float? = null,
                                style: BackgroundSkinStyle = BackgroundSkinStyle(), content: @Composable (Color) -> Unit) {
    val base = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(base)) {
        if (image != null) SkinImage(image, style, opacity ?: if (dark) 0.24f else 0.20f, Modifier.matchParentSize())
        CompositionLocalProvider(LocalSkinEnabled provides (image != null)) { content(if (image == null) base else Color.Transparent) }
    }
}

@Composable
internal fun SkinImage(image: Bitmap, style: BackgroundSkinStyle, opacity: Float, modifier: Modifier = Modifier) {
    // API 31 起用原生渲染模糊；旧设备在后台生成降采样模糊图，避免 UI 线程逐像素计算。
    val oldBlur by produceState<Bitmap?>(null, image, style.blur.toInt()) {
        value = if (Build.VERSION.SDK_INT < 31 && style.blur >= 1) withContext(Dispatchers.Default) {
            val factor = (1 + style.blur / 2).toInt().coerceAtLeast(2)
            Bitmap.createScaledBitmap(image, (image.width / factor).coerceAtLeast(1), (image.height / factor).coerceAtLeast(1), true)
        } else null
    }
    BoxWithConstraints(modifier.clipToBounds()) {
        val density = LocalDensity.current.density
        val width = constraints.maxWidth.toFloat(); val height = constraints.maxHeight.toFloat()
        val scale = maxOf(width / image.width, height / image.height) * style.zoom
        val extra = style.blur * density * 4
        val drawnWidth = image.width * scale + extra; val drawnHeight = image.height * scale + extra
        Image(remember(oldBlur ?: image) { (oldBlur ?: image).asImageBitmap() }, null,
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = drawnWidth / width.coerceAtLeast(1f); scaleY = drawnHeight / height.coerceAtLeast(1f)
                translationX = (drawnWidth - width).coerceAtLeast(0f) / 2 * style.horizontal
                translationY = (drawnHeight - height).coerceAtLeast(0f) / 2 * style.vertical
                alpha = opacity.coerceIn(0f, 1f)
            }.then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(style.blur.dp) else Modifier),
            contentScale = ContentScale.FillBounds)
    }
}
