package com.xtreeroot.mb.ui

import com.xtreeroot.mb.ui.components.MBTextButton as TextButton

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.SourceAccount
import com.xtreeroot.mb.ui.components.MBFormDivider
import com.xtreeroot.mb.ui.components.MBFormRow
import com.xtreeroot.mb.ui.components.MBGroupedSection

@Composable
internal fun AccountHeader(source: String, account: SourceAccount?, loading: Boolean, signInLabel: String?, signIn: () -> Unit,
                            upload: AccountDestination? = null) {
    val colors = MaterialTheme.colorScheme
    MBGroupedSection {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                val avatar = Modifier.size(64.dp).clip(CircleShape)
                if (account?.avatarUrl != null) Artwork(account.avatarUrl, account.avatarHeaders, avatar)
                else Box(avatar.background(colors.primaryContainer), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, null, Modifier.size(30.dp), tint = colors.onPrimaryContainer)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(account?.name?.takeIf(String::isNotBlank) ?: mb("我的音乐", "My Music"), Modifier.semantics { heading() },
                        style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(source, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                }
            }
            if (account == null && signInLabel != null) TextButton(onClick = signIn, enabled = !loading) { Text(signInLabel) }
        }
        if (upload != null) MBFormRow(upload.title, disclosure = true, onClick = upload.open)
    }
}

internal data class AccountDestination(val title: String, val icon: ImageVector, val open: () -> Unit)

@Composable
internal fun AccountDestinations(destinations: List<AccountDestination>) {
    MBGroupedSection(mb("我的内容", "My Content")) {
        destinations.forEachIndexed { index, destination ->
            if (index > 0) MBFormDivider()
            MBFormRow(destination.title, disclosure = true, leading = {
                Icon(destination.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
            }, onClick = destination.open)
        }
    }
}

@Composable
internal fun AccountTools(openSettings: () -> Unit, openPlugins: () -> Unit, openService: () -> Unit = {}) {
    MBGroupedSection {
        MBFormRow(mb("设置", "Settings"), leading = { Icon(Icons.Default.Settings, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
            disclosure = true, onClick = openSettings)
        MBFormDivider()
        MBFormRow(mb("插件", "Plugins"), leading = { Icon(Icons.AutoMirrored.Filled.List, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
            disclosure = true, onClick = openPlugins)
        MBFormDivider()
        MBFormRow(mb("山海服务", "Shanhai Service"), leading = { Icon(Icons.Default.Refresh, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) },
            disclosure = true, onClick = openService)
    }
}

@Composable
internal fun AccountSignOutRow(loading: Boolean, signOut: () -> Unit) {
    MBGroupedSection {
        MBFormRow(mb("退出登录", "Sign Out"), enabled = !loading, color = MaterialTheme.colorScheme.error, onClick = signOut)
    }
}
