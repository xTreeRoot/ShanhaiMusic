package com.xtreeroot.mb.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

internal val musicLight = com.xtreeroot.mb.ui.components.mbLight
internal val musicDark = com.xtreeroot.mb.ui.components.mbDark

@Composable
internal fun MusicBottomDock(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(skinSurface(MaterialTheme.colorScheme.background))
        .windowInsetsPadding(WindowInsets.navigationBars), content = content)
}

@Composable
internal fun MusicNavigation(content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
    Row(Modifier.fillMaxWidth().selectableGroup(), verticalAlignment = Alignment.CenterVertically, content = content)
}

@Composable
internal fun MusicNavigationItem(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit,
                                 modifier: Modifier = Modifier) {
    val color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier.heightIn(min = 56.dp).selectable(active, interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick, role = Role.Tab)
        .padding(horizontal = 3.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(22.dp), tint = color)
        Text(label, color = color, textAlign = TextAlign.Center, maxLines = 2,
            style = MaterialTheme.typography.labelMedium, fontWeight = if (active) FontWeight.Bold else FontWeight.Medium)
    }
}
