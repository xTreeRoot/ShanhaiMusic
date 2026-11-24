package com.xtreeroot.mb.ui

import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.playback.PlaybackState
import com.xtreeroot.mb.preferences.PlayerStyle
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
internal fun PlayerArtwork(track: Track, state: PlaybackState, style: PlayerStyle, animate: Boolean, modifier: Modifier) {
    val turn = if (animate) (state.positionMs % 1_800) / 5f else 0f
    val progress = (state.positionMs.toFloat() / state.durationMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val side = minOf(maxWidth, maxHeight, 440.dp)
        val surface = modifier.semantics { testTag = "player-artwork-${style.name.lowercase()}" }
        when (style) {
            PlayerStyle.Cover -> Artwork(track, surface.size(side).shadow(12.dp, RoundedCornerShape(8.dp)))
            PlayerStyle.Vinyl -> Box(surface.size(side).shadow(12.dp, RoundedCornerShape(18.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFF583D32), Color(0xFF201814))), RoundedCornerShape(18.dp))) {
                val accent = if (state.playing) Color(0xFFFFA548) else Color.Gray
                Canvas(Modifier.fillMaxSize()) {
                    val recordCenter = Offset(size.width * 0.455f, size.height * 0.465f)
                    val radius = size.width * 0.39f
                    drawCircle(Brush.radialGradient(listOf(Color(0xFF343434), Color(0xFF111111)), recordCenter, radius), radius, recordCenter)
                    repeat(18) { ring ->
                        drawCircle(Color.White.copy(alpha = if (ring % 3 == 0) 0.14f else 0.06f),
                            radius * (0.40f + ring * 0.032f), recordCenter, style = Stroke(1.dp.toPx()))
                    }
                    val pivot = Offset(size.width * 0.84f, size.height * 0.15f)
                    rotate(if (state.playing) 18f + progress * 14f else -5f, pivot) {
                        val joint = Offset(pivot.x, size.height * 0.63f)
                        val stylus = Offset(size.width * 0.79f, size.height * 0.76f)
                        drawLine(Color.LightGray, pivot, joint, 6.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.LightGray, joint, stylus, 6.dp.toPx(), StrokeCap.Round)
                        drawCircle(Color(0xFF313131), 6.dp.toPx(), stylus)
                    }
                    drawCircle(Color(0xFFB6B2AF), 10.dp.toPx(), pivot)
                }
                Box(Modifier.align(Alignment.Center).graphicsLayer {
                    translationX = -side.toPx() * 0.045f
                    translationY = -side.toPx() * 0.035f
                }, contentAlignment = Alignment.Center) {
                    Artwork(track, Modifier.size(side * 0.29f).clip(CircleShape).graphicsLayer { rotationZ = turn })
                    Box(Modifier.size(6.dp).background(Color.White.copy(alpha = 0.7f), CircleShape))
                }
                Row(Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.size(8.dp).background(accent, CircleShape))
                    Text("33 ⅓", color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Spacer(Modifier.weight(1f))
                    Text("山海", color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp, fontWeight = FontWeight.Black)
                }
            }
            PlayerStyle.Cassette -> {
                val width = minOf(maxWidth, maxHeight / 0.66f, 440.dp)
                Column(surface.size(width, width * 0.66f).shadow(12.dp, RoundedCornerShape(16.dp))
                    .background(Brush.verticalGradient(listOf(Color(0xFF494949), Color(0xFF212121))), RoundedCornerShape(16.dp))
                    .border(1.dp, Color.White.copy(alpha = 0.22f), RoundedCornerShape(16.dp)).padding(width * 0.055f),
                    verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Column(Modifier.fillMaxWidth().weight(1f).background(Color(0xFFE3DDC9), RoundedCornerShape(6.dp))
                        .padding(horizontal = width * 0.048f, vertical = 4.dp), verticalArrangement = Arrangement.SpaceEvenly) {
                        val ink = Color(0xFF20345A)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(track.title, color = ink, style = MaterialTheme.typography.labelLarge, maxLines = 1,
                                    fontFamily = FontFamily.Serif, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, color = ink, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                                    overflow = TextOverflow.Ellipsis)
                            }
                            Text("A", color = ink, fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleMedium)
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Artwork(track, Modifier.size(width * 0.10f))
                            Row(Modifier.weight(1f).height(width * 0.15f).background(Color(0xFF141414), CircleShape)
                                .padding(horizontal = width * 0.09f), horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically) {
                                TapeReel(1f - progress, turn, Modifier.size(width * 0.135f))
                                TapeReel(progress, turn, Modifier.size(width * 0.135f))
                            }
                        }
                        Box(Modifier.fillMaxWidth().height(width * 0.02f).background(MaterialTheme.colorScheme.primary))
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        repeat(2) {
                            Box(Modifier.padding(horizontal = 16.dp).size(width * 0.025f).background(Color.Black, CircleShape)
                                .border(1.dp, Color.Gray, CircleShape))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TapeReel(fill: Float, turn: Float, modifier: Modifier) {
    Canvas(modifier) {
        val radius = size.minDimension / 2
        drawCircle(Color(0xFF5C3B28), radius * (0.53f + sqrt(fill) * 0.46f))
        drawCircle(Color.Gray, radius * 0.48f, style = Stroke(radius * 0.18f))
        rotate(turn) {
            repeat(6) { spoke ->
                val angle = spoke * PI / 3
                drawLine(Color.LightGray, center, center + Offset(cos(angle).toFloat(), sin(angle).toFloat()) * radius * 0.44f,
                    radius * 0.11f)
            }
        }
        drawCircle(Color.Black, radius * 0.15f)
    }
}

@Composable
internal fun ProgressSmoke(progress: Float, position: Long, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val inset = 6.dp.toPx()
        val head = progress * (size.width - 2 * inset).coerceAtLeast(0f) + inset
        repeat(24) { particle ->
            val age = particle / 24f
            val left = head - age * 70.dp.toPx()
            if (left >= 0f) drawCircle(accent.copy(alpha = (1f - age) * 0.45f), (1.5f + age * 3).dp.toPx(), Offset(
                left, size.height / 2 + sin(position / 250f + age * 9) * age * 15.dp.toPx()))
        }
    }
}

@Composable
internal fun PlaybackFog(track: Track, bass: Float, animate: Boolean, modifier: Modifier) {
    val level = if (animate) animateFloatAsState(bass, tween(200), label = "bass fog").value else 0f
    val background = MaterialTheme.colorScheme.background
    val accent = MaterialTheme.colorScheme.primary
    Box(modifier.background(background).clipToBounds().clearAndSetSemantics { }) {
        if (Build.VERSION.SDK_INT >= 31 && track.artworkUrl != null) {
            Artwork(track, Modifier.fillMaxSize().graphicsLayer {
                scaleX = 1.15f + level * 0.25f
                scaleY = scaleX
                translationY = -level * 35.dp.toPx()
                alpha = 0.23f + level * 0.12f
            }.blur(65.dp))
        } else Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width * 0.52f, size.height * (0.53f - level * 0.09f))
            drawRect(Brush.radialGradient(listOf(accent.copy(alpha = 0.13f + level * 0.15f), Color.Transparent),
                center, size.maxDimension * (0.57f + level * 0.14f)))
        }
    }
}
