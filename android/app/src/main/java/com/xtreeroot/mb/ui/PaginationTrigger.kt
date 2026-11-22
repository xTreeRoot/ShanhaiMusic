package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp

/** 仅在尾部进入实际可见区域时续页，懒列表预创建和离屏预取不会触发网络请求。 */
@Composable
internal fun PaginationTrigger(cursor: Any, enabled: Boolean = true, load: () -> Unit) {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    var requested by remember { mutableStateOf<Any?>(null) }
    val latest by rememberUpdatedState(load)
    Box(Modifier.fillMaxWidth().height(40.dp).onGloballyPositioned { coordinates ->
        val frame = coordinates.boundsInWindow()
        visible = !frame.isEmpty && frame.overlaps(Rect(0f, 0f, view.width.toFloat(), view.height.toFloat()))
    }) { Loading() }
    LaunchedEffect(cursor, visible, enabled) {
        if (visible && enabled && requested != cursor) { requested = cursor; latest() }
    }
}
