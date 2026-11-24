package com.xtreeroot.mb.ui.components

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 参考公开 APK 的灰白分组、蓝色操作和圆角层级；交互仍使用 Compose 原生语义。
internal val mbLight = lightColorScheme(
    primary = Color(0xFF1B68E6), onPrimary = Color.White, primaryContainer = Color(0xFFDCE9FF), onPrimaryContainer = Color(0xFF154CA2),
    secondary = Color(0xFF62666D), onSecondary = Color.White, secondaryContainer = Color(0xFFEDEEF0), onSecondaryContainer = Color(0xFF25272B),
    tertiary = Color(0xFF3480FF), onTertiary = Color.White,
    background = Color(0xFFF7F7F7), onBackground = Color(0xFF17181A), surface = Color.White, onSurface = Color(0xFF17181A),
    surfaceVariant = Color(0xFFF0F0F0), onSurfaceVariant = Color(0xFF686A70),
    surfaceContainerLow = Color(0xFFFAFAFA), surfaceContainer = Color(0xFFF0F0F0),
    surfaceContainerHigh = Color(0xFFEAEAEA), surfaceContainerHighest = Color(0xFFE5E5E5),
    outline = Color(0xFF989BA2), outlineVariant = Color(0xFFE2E3E6),
    error = Color(0xFFCC3030), errorContainer = Color(0xFFFBE4E4), onErrorContainer = Color(0xFF851919),
)
internal val mbDark = darkColorScheme(
    primary = Color(0xFF91BAFF), onPrimary = Color(0xFF102E5C), primaryContainer = Color(0xFF153D77), onPrimaryContainer = Color(0xFFDCE9FF),
    secondary = Color(0xFFBFC2C9), onSecondary = Color(0xFF292B30), secondaryContainer = Color(0xFF303136), onSecondaryContainer = Color(0xFFF1F2F4),
    tertiary = Color(0xFF91BAFF), onTertiary = Color(0xFF102E5C),
    background = Color(0xFF111113), onBackground = Color(0xFFF1F1F3), surface = Color(0xFF1D1D20), onSurface = Color(0xFFF1F1F3),
    surfaceVariant = Color(0xFF29292D), onSurfaceVariant = Color(0xFFB1B1B9),
    surfaceContainerLow = Color(0xFF19191C), surfaceContainer = Color(0xFF252528),
    surfaceContainerHigh = Color(0xFF303034), surfaceContainerHighest = Color(0xFF39393E),
    outline = Color(0xFF84848D), outlineVariant = Color(0xFF36363C),
    error = Color(0xFFFFB4B4), errorContainer = Color(0xFF482424), onErrorContainer = Color(0xFFFFDADA),
)
private val mbTypography = Typography(
    headlineLarge = TextStyle(fontSize = 34.sp, lineHeight = 42.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium),
)

@Composable
internal fun MBTheme(dark: Boolean, accent: Color? = null, content: @Composable () -> Unit) {
    val base = if (dark) mbDark else mbLight
    val colors = if (accent == null) base else base.copy(primary = accent,
        onPrimary = if (accent.luminance() > 0.5f) Color(0xFF17181A) else Color.White)
    MaterialTheme(colorScheme = colors, typography = mbTypography,
        shapes = Shapes(extraSmall = RoundedCornerShape(8.dp), small = RoundedCornerShape(12.dp),
            medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)),
        content = content)
}
