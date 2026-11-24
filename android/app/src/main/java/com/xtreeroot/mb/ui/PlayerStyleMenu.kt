package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.R
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.LyricStyle
import com.xtreeroot.mb.preferences.PlayerStyle

@Composable
internal fun PlayerStyle.label(): String = when (this) {
    PlayerStyle.Cover -> mb("封面", "Cover")
    PlayerStyle.Vinyl -> mb("黑胶", "Vinyl")
    PlayerStyle.Cassette -> mb("磁带", "Cassette")
}

@Composable
internal fun LyricStyle.label(): String = when (this) {
    LyricStyle.Flow -> mb("Flow · 流动", "Flow")
    LyricStyle.Steps -> mb("Steps · 阶梯", "Steps")
    LyricStyle.Tilt -> mb("Tilt · 倾诉", "Tilt")
}

@Composable
internal fun PlayerStyleMenu(preferences: AppPreferences, showingLyrics: Boolean, chooseArtwork: (PlayerStyle) -> Unit,
                             chooseLyrics: (LyricStyle) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(painterResource(R.drawable.ic_player_style), mb("播放界面样式", "Playback Appearance"))
        }
        DropdownMenu(open, { open = false }) {
            Text(mb("播放器样式", "Player Style"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            PlayerStyle.entries.forEach { style ->
                val active = !showingLyrics && preferences.playerStyle == style
                DropdownMenuItem(text = { Text(style.label()) }, onClick = { open = false; chooseArtwork(style) },
                    modifier = Modifier.semantics { selected = active },
                    trailingIcon = if (active) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null)
            }
            HorizontalDivider()
            Text(mb("歌词舞台", "Lyric Stage"), Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LyricStyle.entries.forEach { style ->
                val active = showingLyrics && preferences.lyricStyle == style
                DropdownMenuItem(text = { Text(style.label()) }, onClick = { open = false; chooseLyrics(style) },
                    modifier = Modifier.semantics { selected = active },
                    trailingIcon = if (active) ({ Icon(Icons.Default.Check, contentDescription = null) }) else null)
            }
        }
    }
}
