package com.xtreeroot.mb.ui

import android.app.Activity
import android.content.Context
import android.os.Build
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.SecureFlagPolicy
import androidx.webkit.WebViewCompat
import com.xtreeroot.mb.plugin.CompatPluginBrowserActivity
import com.xtreeroot.mb.plugin.PluginBrowserIssue
import com.xtreeroot.mb.plugin.PluginBrowserPage
import com.xtreeroot.mb.plugin.PluginBrowserRoute
import com.xtreeroot.mb.plugin.PluginBrowserSession
import com.xtreeroot.mb.plugin.PluginWebLink
import com.xtreeroot.mb.plugin.PluginWebSignIn
import com.xtreeroot.mb.plugin.PluginWebSignInBroker
import com.xtreeroot.mb.plugin.disposableBrowserSupport
import com.xtreeroot.mb.plugin.pluginBrowserLogTag
import com.xtreeroot.mb.plugin.pluginBrowserRoute
import kotlinx.coroutines.delay

@Composable
internal fun PluginWebSignInView(request: PluginWebSignIn, broker: PluginWebSignInBroker, cancel: () -> Unit) {
    val english = LocalMBEnglish.current
    PluginBrowserDialog(PluginBrowserPage(request.title, request.url, request.id, request), mb("取消", "Cancel"), cancel,
        onFailure = { broker.fail(request.id, it.text(english)) }, onCookies = { broker.finish(request.id, it) },
        onDocumentError = { broker.fail(request.id, it) })
}

@Composable
internal fun PluginWebLinkView(link: PluginWebLink, close: () -> Unit) {
    PluginBrowserDialog(PluginBrowserPage(link.title, link.url, link.id), mb("完成", "Done"), close)
}

@Composable
private fun PluginBrowserDialog(page: PluginBrowserPage, closeLabel: String, close: () -> Unit,
                                onFailure: (PluginBrowserIssue) -> Unit = {}, onCookies: (String) -> Unit = {},
                                onDocumentError: (String) -> Unit = {}) {
    val context = LocalContext.current
    val english = LocalMBEnglish.current
    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    var issue by rememberSaveable(page.id) { mutableStateOf<PluginBrowserIssue?>(null) }
    var useProfiles by rememberSaveable(page.id) { mutableStateOf(false) }
    var launched by rememberSaveable(page.id) { mutableStateOf(false) }
    fun fail(failure: PluginBrowserIssue) {
        Log.w(pluginBrowserLogTag, "isolated browser failed: $failure")
        issue = failure
        onFailure(failure)
    }
    fun openProfile(disposable: Result<Boolean>, processIssue: PluginBrowserIssue? = null) {
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(context) }
        val installed = provider.getOrNull()
        Log.i(pluginBrowserLogTag, "SDK=${Build.VERSION.SDK_INT} provider=${installed?.packageName} version=${installed?.versionName}")
        provider.exceptionOrNull()?.let { Log.w(pluginBrowserLogTag, "provider query: ${it.javaClass.simpleName}") }
        val profiles = if (installed != null) runCatching { PluginBrowserSession.available() } else null
        profiles?.exceptionOrNull()?.let { Log.w(pluginBrowserLogTag, "profile capability query: ${it.javaClass.simpleName}") }
        val route = pluginBrowserRoute(disposable, provider.map { it != null }, profiles, processIssue)
        if (route == PluginBrowserRoute.Profile) useProfiles = true
        else fail(checkNotNull(route.issue))
    }
    fun compatFailed(failure: PluginBrowserIssue) {
        when (failure) {
            PluginBrowserIssue.Startup, PluginBrowserIssue.MissingProvider, PluginBrowserIssue.UnsupportedProvider ->
                openProfile(Result.success(false), processIssue = failure)
            else -> fail(failure)
        }
    }
    val compat = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        CompatPluginBrowserActivity.recordResult(result.data)
        when (result.resultCode) {
            Activity.RESULT_OK -> result.data?.getStringExtra(CompatPluginBrowserActivity.cookieKey)?.let(onCookies)
                ?: fail(PluginBrowserIssue.Startup)
            Activity.RESULT_CANCELED -> {
                if (result.data?.getBooleanExtra(CompatPluginBrowserActivity.userCancelledKey, false) == true) close()
                else compatFailed(PluginBrowserIssue.Startup)
            }
            CompatPluginBrowserActivity.failed -> {
                val pageError = result.data?.getStringExtra(CompatPluginBrowserActivity.documentErrorKey)
                if (pageError != null) onDocumentError(pageError) else {
                    val name = result.data?.getStringExtra(CompatPluginBrowserActivity.issueKey)
                    compatFailed(PluginBrowserIssue.entries.firstOrNull { it.name == name } ?: PluginBrowserIssue.Startup)
                }
            }
            else -> fail(PluginBrowserIssue.Startup)
        }
    }
    fun openCompat() {
        useProfiles = false
        launched = true
        Log.i(pluginBrowserLogTag, "starting disposable browser process")
        try { compat.launch(CompatPluginBrowserActivity.intent(context, page, english, dark)) }
        catch (failure: Exception) {
            Log.w(pluginBrowserLogTag, "compat activity launch: ${failure.javaClass.simpleName}")
            compatFailed(PluginBrowserIssue.Startup)
        }
    }
    LaunchedEffect(page.id) {
        if (launched) return@LaunchedEffect
        val disposable = disposableBrowserSupport(context)
        disposable.exceptionOrNull()?.let { Log.w(pluginBrowserLogTag, "data directory capability: ${it.javaClass.simpleName}") }
        if (disposable.getOrNull() == true) {
            if (CompatPluginBrowserActivity.awaitPreviousExit(context)) openCompat()
            else compatFailed(PluginBrowserIssue.Startup)
        } else openProfile(disposable)
    }
    if (!useProfiles && issue == null) return
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false, securePolicy = SecureFlagPolicy.SecureOn)) {
        PluginBrowserPanel(page, closeLabel, close, if (useProfiles) PluginBrowserSession::create else null,
            onFailure = ::fail, onCookies = onCookies, initialIssue = issue, onDocumentError = onDocumentError)
    }
}

@Composable
internal fun PluginBrowserPanel(page: PluginBrowserPage, closeLabel: String, close: () -> Unit,
                                create: ((Context, String?, (PluginBrowserIssue) -> Unit, (String) -> Unit) -> PluginBrowserSession)?,
                                onFailure: (PluginBrowserIssue) -> Unit, onCookies: (String) -> Unit,
                                initialIssue: PluginBrowserIssue? = null, onReleased: () -> Unit = {},
                                onDocumentError: (String) -> Unit = {}) {
    var session by remember { mutableStateOf<PluginBrowserSession?>(null) }
    var problem by remember { mutableStateOf<PluginBrowserIssue?>(null) }
    var documentError by remember { mutableStateOf<String?>(null) }
    var authority by remember(page.url) { mutableStateOf(page.url.rawAuthority) }
    val error = initialIssue ?: problem
    val blocked = error != null || documentError != null
    LaunchedEffect(session, blocked) {
        if (blocked) return@LaunchedEffect
        val signIn = page.signIn ?: return@LaunchedEffect
        val active = session ?: return@LaunchedEffect
        while (documentError == null && problem == null) {
            val header = try { active.cookies(signIn) } catch (failure: Exception) {
                Log.w(pluginBrowserLogTag, "cookie read: ${failure.javaClass.simpleName}")
                problem = PluginBrowserIssue.Startup
                break
            }
            if (header != null) { onCookies(header); break }
            delay(300)
        }
    }
    LaunchedEffect(problem) { problem?.let(onFailure) }
    LaunchedEffect(documentError) { documentError?.let(onDocumentError) }
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).windowInsetsPadding(WindowInsets.safeDrawing)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(page.title, style = MaterialTheme.typography.titleMedium)
                Text(authority, style = MaterialTheme.typography.labelMedium)
            }
            TextButton(onClick = { session?.reload(page) }, enabled = session != null && !blocked) { Text(mb("刷新", "Reload")) }
            TextButton(onClick = close) { Text(closeLabel) }
        }
        val message = documentError ?: error?.text(LocalMBEnglish.current)
        if (message != null) Text(message, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        if (create != null && !blocked) AndroidView(factory = { context ->
            FrameLayout(context).apply {
                try {
                    session = create(context, page.signIn?.userAgent, { if (problem == null && documentError == null) problem = it },
                        { authority = it }).also { browser ->
                        tag = browser
                        addView(browser.view, MATCH_PARENT, MATCH_PARENT)
                        browser.open(page) { if (documentError == null && problem == null) documentError = it }
                    }
                } catch (failure: Exception) {
                    Log.w(pluginBrowserLogTag, "browser startup: ${failure.javaClass.simpleName}")
                    problem = PluginBrowserIssue.Startup
                }
            }
        }, modifier = Modifier.fillMaxSize(), onRelease = { frame ->
            frame.removeAllViews()
            val active = frame.tag as? PluginBrowserSession
            if (active == null) onReleased()
            else runCatching { active.close(onReleased) }.onFailure { onReleased() }
            session = null
        }, update = {})
    }
}

private fun PluginBrowserIssue.text(english: Boolean): String {
    val code = when (this) {
        PluginBrowserIssue.MissingProvider -> "WV-01"
        PluginBrowserIssue.UnsupportedProvider -> "WV-02"
        PluginBrowserIssue.Startup -> "WV-03"
        PluginBrowserIssue.Navigation -> "WV-04"
        PluginBrowserIssue.ServerUnavailable -> "WV-05"
        PluginBrowserIssue.Renderer -> "WV-06"
    }
    val detail = if (english) when (this) {
        PluginBrowserIssue.MissingProvider -> "No usable WebView. Enable the system WebView provider in Android settings."
        PluginBrowserIssue.UnsupportedProvider -> "This WebView cannot isolate sign-in. Update the active WebView provider or Android."
        PluginBrowserIssue.Startup -> "Could not start the isolated WebView. Reopen Shanhai and retry."
        PluginBrowserIssue.Navigation -> "Could not load the page. Check your connection and try again."
        PluginBrowserIssue.ServerUnavailable -> "The website is temporarily unavailable. Try again later."
        PluginBrowserIssue.Renderer -> "The isolated WebView stopped responding. Close this page and retry."
    } else when (this) {
        PluginBrowserIssue.MissingProvider -> "没有可用的 WebView，请在系统设置中启用 WebView 提供程序。"
        PluginBrowserIssue.UnsupportedProvider -> "当前 WebView 不支持隔离登录，请更新系统正在使用的 WebView 或 Android。"
        PluginBrowserIssue.Startup -> "隔离 WebView 启动失败，请重新打开山海后重试。"
        PluginBrowserIssue.Navigation -> "网页无法加载，请检查网络后重试。"
        PluginBrowserIssue.ServerUnavailable -> "网站暂时不可用，请稍后重试。"
        PluginBrowserIssue.Renderer -> "隔离 WebView 已退出，请关闭页面后重试。"
    }
    return "$detail ($code)"
}
