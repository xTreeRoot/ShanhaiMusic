package com.xtreeroot.mb.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

@Composable
internal fun MBListItem(headlineContent: @Composable () -> Unit, modifier: Modifier = Modifier,
                        supportingContent: @Composable (() -> Unit)? = null, leadingContent: @Composable (() -> Unit)? = null,
                        trailingContent: @Composable (() -> Unit)? = null) {
    ListItem(headlineContent, Modifier.padding(horizontal = 12.dp, vertical = 3.dp).clip(RoundedCornerShape(18.dp)).then(modifier),
        supportingContent = supportingContent, leadingContent = leadingContent, trailingContent = trailingContent,
        colors = ListItemDefaults.colors(containerColor = com.xtreeroot.mb.ui.skinSurface()), tonalElevation = 0.dp)
}

@Composable
internal fun MBAlertDialog(onDismissRequest: () -> Unit, confirmButton: @Composable () -> Unit,
                           modifier: Modifier = Modifier, dismissButton: @Composable (() -> Unit)? = null,
                           title: @Composable (() -> Unit)? = null, text: @Composable (() -> Unit)? = null) {
    AlertDialog(onDismissRequest, {
        CompositionLocalProvider(LocalMBPrimaryAction provides true, content = confirmButton)
    }, modifier, dismissButton = dismissButton, title = title, text = text, shape = RoundedCornerShape(28.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp)
}

@Composable
internal fun MBDropdownMenu(expanded: Boolean, onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
                            content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(expanded, onDismissRequest, modifier, shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp, shadowElevation = 6.dp, content = content)
}

@Composable
internal fun MBMenuItem(text: @Composable () -> Unit, onClick: () -> Unit, modifier: Modifier = Modifier,
                        leadingIcon: @Composable (() -> Unit)? = null, trailingIcon: @Composable (() -> Unit)? = null,
                        enabled: Boolean = true) {
    DropdownMenuItem(text, onClick, modifier.padding(horizontal = 4.dp).clip(RoundedCornerShape(12.dp)),
        leadingIcon = leadingIcon, trailingIcon = trailingIcon, enabled = enabled,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp))
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBBottomSheet(onDismissRequest: () -> Unit, modifier: Modifier = Modifier,
                           sheetState: SheetState = rememberModalBottomSheetState(),
                           sheetMaxWidth: Dp = 640.dp,
                           containerColor: Color = MaterialTheme.colorScheme.surface,
                           content: @Composable ColumnScope.() -> Unit) {
    ModalBottomSheet(onDismissRequest, modifier, sheetState = sheetState, sheetMaxWidth = sheetMaxWidth, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = containerColor, tonalElevation = 0.dp, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBTopAppBar(title: @Composable () -> Unit, modifier: Modifier = Modifier,
                         navigationIcon: @Composable () -> Unit = {}, actions: @Composable RowScope.() -> Unit = {},
                         expandedHeight: Dp = 64.dp, colors: TopAppBarColors = TopAppBarDefaults.topAppBarColors(
                             containerColor = MaterialTheme.colorScheme.background), largeTitle: Boolean = false) {
    val resolved = if (com.xtreeroot.mb.ui.LocalSkinEnabled.current) TopAppBarDefaults.topAppBarColors(
        containerColor = Color.Transparent, scrolledContainerColor = Color.Transparent) else colors
    if (largeTitle) {
        Surface(modifier, color = resolved.containerColor, contentColor = resolved.titleContentColor) {
            Column(Modifier.windowInsetsPadding(TopAppBarDefaults.windowInsets).padding(horizontal = 20.dp)) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    navigationIcon()
                    Spacer(Modifier.weight(1f))
                    actions()
                }
                ProvideTextStyle(MaterialTheme.typography.headlineLarge) { Box(Modifier.padding(top = 4.dp, bottom = 16.dp)) { title() } }
            }
        }
    } else TopAppBar(title, modifier, navigationIcon, actions, expandedHeight = expandedHeight, colors = resolved)
}

@Composable
internal fun MBTab(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier,
                   text: @Composable (() -> Unit)? = null) {
    Tab(selected, onClick, modifier, text = text, selectedContentColor = MaterialTheme.colorScheme.onSurface,
        unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBTabRow(selectedTabIndex: Int, tabs: @Composable () -> Unit) {
    PrimaryTabRow(selectedTabIndex, containerColor = if (com.xtreeroot.mb.ui.LocalSkinEnabled.current) Color.Transparent else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface, divider = {}, tabs = tabs)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun MBScrollableTabRow(selectedTabIndex: Int, edgePadding: Dp = 12.dp, tabs: @Composable () -> Unit) {
    PrimaryScrollableTabRow(selectedTabIndex, containerColor = if (com.xtreeroot.mb.ui.LocalSkinEnabled.current) Color.Transparent else MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurface, edgePadding = edgePadding, divider = {}, tabs = tabs)
}

@Composable
internal fun MBNavigationRailItem(selected: Boolean, onClick: () -> Unit, icon: @Composable () -> Unit,
                                  modifier: Modifier = Modifier, label: @Composable (() -> Unit)? = null) {
    NavigationRailItem(selected, onClick, icon, modifier, label = label,
        colors = NavigationRailItemDefaults.colors(indicatorColor = Color.Transparent,
            selectedIconColor = MaterialTheme.colorScheme.onSurface, selectedTextColor = MaterialTheme.colorScheme.onSurface))
}
