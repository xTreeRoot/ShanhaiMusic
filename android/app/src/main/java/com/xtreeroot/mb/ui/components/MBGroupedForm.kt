package com.xtreeroot.mb.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 与 iOS 分组列表对应的卡片容器，供资料库入口和设置表单共用。 */
@Composable
internal fun MBGroupedSection(title: String? = null, footer: String? = null, containerColor: Color = com.xtreeroot.mb.ui.skinSurface(), content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 16.dp)) {
        if (title != null) Text(title, Modifier.padding(start = 16.dp, bottom = 6.dp).semantics { heading() },
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Surface(shape = RoundedCornerShape(14.dp), color = containerColor) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        if (footer != null) Text(footer, Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun MBFormDivider() {
    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f))
}

@Composable
internal fun MBFormRow(label: String, modifier: Modifier = Modifier, detail: String? = null,
                       enabled: Boolean = true, color: Color = MaterialTheme.colorScheme.onSurface,
                       leading: (@Composable () -> Unit)? = null, trailing: (@Composable () -> Unit)? = null,
                       disclosure: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().heightIn(min = 52.dp)
        .then(if (onClick == null) Modifier else Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick))
        .padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        leading?.invoke()
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = color.copy(alpha = if (enabled) 1f else 0.38f))
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        trailing?.invoke()
        if (disclosure) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
    }
}
