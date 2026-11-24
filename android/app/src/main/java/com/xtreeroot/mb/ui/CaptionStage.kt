package com.xtreeroot.mb.ui

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.CaptionLine
import com.xtreeroot.mb.music.CaptionWord
import com.xtreeroot.mb.music.activeCaption
import com.xtreeroot.mb.music.timedWords
import com.xtreeroot.mb.preferences.LyricStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@Composable
internal fun CaptionStage(lines: List<CaptionLine>, position: Long, style: LyricStyle, sparks: Boolean,
                          animate: Boolean, compact: Boolean = false, seek: (Long) -> Unit) {
    var selectedIndex by remember(lines) { mutableStateOf<Int?>(null) }
    val index = lines.activeCaption(position)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = index.coerceAtLeast(0))
    val fontScale = LocalDensity.current.fontScale
    BoxWithConstraints(Modifier.fillMaxSize().semantics { testTag = "lyric-style-${style.name.lowercase()}" }) {
        LaunchedEffect(index, lines, style, maxWidth, maxHeight, fontScale, animate, selectedIndex) {
            if (index < 0 || selectedIndex != null) return@LaunchedEffect
            if (compact) {
                if (animate) list.animateScrollToItem(index) else list.scrollToItem(index)
                return@LaunchedEffect
            }
            val alreadyVisible = list.layoutInfo.visibleItemsInfo.any { it.index == index }
            if (!alreadyVisible) list.scrollToItem(index)
            val layout = list.layoutInfo
            val current = layout.visibleItemsInfo.firstOrNull { it.index == index } ?: return@LaunchedEffect
            val viewportCenter = (layout.viewportStartOffset + layout.viewportEndOffset) / 2f
            val distance = current.offset + current.size / 2f - viewportCenter
            if (animate && alreadyVisible) list.animateScrollBy(distance, tween(250)) else list.scrollBy(distance)
        }
        LazyColumn(state = list, modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = if (compact) 0.dp else maxHeight / 2, bottom = if (compact) 48.dp else maxHeight / 2)) {
            itemsIndexed(lines, key = { lineIndex, line -> "${line.startMs}:$lineIndex" }) { lineIndex, line ->
                val active = lineIndex == index
                val words = remember(line) { line.timedWords() }
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                    .background(if (selectedIndex == lineIndex) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f) else Color.Transparent)
                    .padding(horizontal = 8.dp).then(if (compact) Modifier.height(48.dp) else Modifier.heightIn(min = 64.dp)), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).clickable(onClickLabel = mb("选择歌词 ${line.text}", "Select lyric ${line.text}")) {
                        selectedIndex = lineIndex
                    }.semantics {
                        selected = selectedIndex == lineIndex || active
                        contentDescription = listOfNotNull(line.text, line.translation).joinToString("\n")
                    }.padding(vertical = if (compact) 4.dp else 10.dp)) {
                        if (compact) {
                            Text(line.text, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
                                color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        } else WordLayout(style) {
                            words.forEachIndexed { wordIndex, word ->
                                LyricWord(word, if (active) position else -1, style, wordIndex, animate, sparks && animate && active, compact)
                            }
                        }
                        line.translation?.takeIf { !compact && it.isNotBlank() }?.let { translation ->
                            Text(translation, Modifier.padding(top = 4.dp).clearAndSetSemantics { }, style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (selectedIndex == lineIndex) {
                        val confirmLabel = mb("从这一句播放 ${line.text}", "Play from lyric ${line.text}")
                        Column(Modifier.width(if (compact) 48.dp else 64.dp).heightIn(min = 48.dp).clickable(role = Role.Button) {
                            selectedIndex = null
                            seek(line.startMs)
                        }.semantics { contentDescription = confirmLabel },
                            horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(Icons.Default.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                            Text(timeLabel(line.startMs), Modifier.clearAndSetSemantics { }, style = MaterialTheme.typography.labelSmall)
                        }
                    } else Spacer(Modifier.width(if (compact) 48.dp else 64.dp))
                }

            }
        }
    }
}

@Composable
private fun WordLayout(style: LyricStyle, words: @Composable () -> Unit) {
    Layout(content = words, modifier = Modifier.clearAndSetSemantics { }) { measurables, constraints ->
        val gap = (if (style == LyricStyle.Flow) 1.dp else 3.dp).roundToPx()
        val lineGap = (if (style == LyricStyle.Flow) 5.dp else 10.dp).roundToPx()
        val places = measurables.map { it.measure(Constraints(maxWidth = constraints.maxWidth)) }
        val offsets = mutableListOf<Pair<Int, Int>>()
        var left = 0
        var top = 0
        var height = 0
        var row = 0
        places.forEach { place ->
            if (left > 0 && left + place.width > constraints.maxWidth) {
                row++
                top += height + lineGap
                height = 0
                left = if (style == LyricStyle.Steps) (12.dp * (row % 3)).roundToPx()
                    .coerceAtMost(constraints.maxWidth - place.width) else 0
            }
            offsets.add(left to top)
            left += place.width + gap
            height = maxOf(height, place.height)
        }
        layout(constraints.maxWidth, (top + height).coerceIn(constraints.minHeight, constraints.maxHeight)) {
            places.forEachIndexed { index, place -> place.placeRelative(offsets[index].first, offsets[index].second) }
        }
    }
}

@Composable
private fun LyricWord(word: CaptionWord, position: Long, style: LyricStyle, index: Int, animate: Boolean, sparks: Boolean, compact: Boolean) {
    val typography = if (compact) MaterialTheme.typography.bodyLarge else if (style == LyricStyle.Tilt) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.headlineSmall
    val weight = if (style == LyricStyle.Tilt && index % 2 == 0) FontWeight.Black else FontWeight.SemiBold
    val accent = MaterialTheme.colorScheme.primary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (position >= 0) 0.92f else 0.62f)
    val progress = word.progress(position)
    val age = (position - word.startMs) / 600f
    TimedCaptionWord(word.text, progress, typography, weight, dim,
        Modifier.graphicsLayer { rotationZ = if (style == LyricStyle.Tilt && animate && index % 3 == 0) -4f else 0f }
        .drawWithContent {
            drawContent()
            if (sparks && age >= 0f && age < 1f) {
                repeat(6) { particle ->
                    val angle = particle * PI / 3
                    drawCircle(accent.copy(alpha = 1f - age), 1.25.dp.toPx(), Offset(
                        size.width * progress + cos(angle).toFloat() * age * 24.dp.toPx(),
                        size.height / 2 + (sin(angle).toFloat() * 22 - 15) * age * 1.dp.toPx()))
                }
            }
        })
}

@Composable
internal fun TimedCaptionWord(text: String, progress: Float, typography: TextStyle, weight: FontWeight,
                              dim: Color, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier) {
        Text(text, color = dim, style = typography, fontWeight = weight)
        if (progress > 0f) Text(text, Modifier.matchParentSize().clearAndSetSemantics { }.drawWithContent {
            if (layoutDirection == LayoutDirection.Ltr) {
                clipRect(right = size.width * progress) { this@drawWithContent.drawContent() }
            } else clipRect(left = size.width * (1f - progress)) { this@drawWithContent.drawContent() }
        }, color = accent, style = typography, fontWeight = weight)
    }
}
