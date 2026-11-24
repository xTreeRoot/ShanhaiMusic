package com.xtreeroot.mb

import android.graphics.Color
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge

/** 所有页面共用透明系统栏；内容继续通过 WindowInsets 避让手势和导航按钮。 */
internal fun ComponentActivity.enableMBEdgeToEdge() {
    enableEdgeToEdge(navigationBarStyle = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT))
    // 默认 auto 会启用系统对比度遮罩，MIUI 手势导航因此给整个窗口加上白色底栏。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
}
