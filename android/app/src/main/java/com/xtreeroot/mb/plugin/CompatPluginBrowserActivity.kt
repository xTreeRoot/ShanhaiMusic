package com.xtreeroot.mb.plugin

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import com.xtreeroot.mb.enableMBEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import androidx.webkit.ProcessGlobalConfig
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.xtreeroot.mb.ui.LocalMBEnglish
import com.xtreeroot.mb.ui.PluginBrowserPanel
import com.xtreeroot.mb.ui.mb
import java.io.File
import java.net.URI
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val processName = ":plugin_browser"
private const val directorySuffixPrefix = "mb-plugin-browser-"
internal const val compatDataDirectoryPrefix = "app_webview_$directorySuffixPrefix"

internal fun isPluginBrowserProcess(context: Context): Boolean {
    val current = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Application.getProcessName()
    else context.getSystemService(ActivityManager::class.java)?.runningAppProcesses
        ?.firstOrNull { it.pid == Process.myPid() }?.processName
    return current == "${context.packageName}$processName"
}

internal fun disposableBrowserSupport(context: Context): Result<Boolean> = runCatching {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ||
        WebViewFeature.isStartupFeatureSupported(context, WebViewFeature.STARTUP_FEATURE_SET_DATA_DIRECTORY_SUFFIX)
}

internal data class PluginBrowserPage(val title: String, val url: URI, val id: UUID, val signIn: PluginWebSignIn? = null)

internal class CompatPluginBrowserActivity : ComponentActivity() {
    private val closing = mutableStateOf(false)
    private var ownsProcess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        val page = intent.page() ?: return finishWithIssue(PluginBrowserIssue.Startup)
        if (!isPluginBrowserProcess(this)) {
            Log.w(pluginBrowserLogTag, "compat activity is outside the isolated process")
            return finishWithIssue(PluginBrowserIssue.Startup)
        }
        if (attempt != null && attempt != page.id) return finishWithIssue(PluginBrowserIssue.Startup)
        ownsProcess = true
        val suffix = directorySuffix ?: return finishWithIssue(startupIssue(directoryProblem))
        try {
            if (attempt == null) {
                File(applicationInfo.dataDir).listFiles { file ->
                    file.name.startsWith(compatDataDirectoryPrefix) && file.name != "app_webview_$suffix"
                }?.forEach(File::deleteRecursively)
                cacheDir.listFiles { file ->
                    file.name.startsWith("webview_$directorySuffixPrefix") && file.name != "webview_$suffix"
                }?.forEach(File::deleteRecursively)
                attempt = page.id
            }
        } catch (failure: RuntimeException) {
            Log.w(pluginBrowserLogTag, "compat data cleanup: ${failure.javaClass.simpleName}")
            return finishWithIssue(PluginBrowserIssue.Startup)
        }
        enableMBEdgeToEdge()
        val dark = intent.getBooleanExtra("dark", false)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = !dark
            isAppearanceLightNavigationBars = !dark
        }
        setContent {
            val english = intent.getBooleanExtra("english", false)
            CompositionLocalProvider(LocalMBEnglish provides english) {
                MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
                    BackHandler(onBack = ::cancel)
                    if (!closing.value) PluginBrowserPanel(page, mb("取消", "Cancel").takeIf { page.signIn != null }
                        ?: mb("完成", "Done"), close = ::cancel,
                        create = PluginBrowserSession::createInDisposableProcess,
                        onFailure = { issue ->
                            val result = if (issue == PluginBrowserIssue.Startup) startupIssue() else issue
                            complete(failed, Intent().putExtra(issueKey, result.name))
                        },
                        onDocumentError = { complete(failed, Intent().putExtra(documentErrorKey, it)) },
                        onCookies = { complete(Activity.RESULT_OK, Intent().putExtra(cookieKey, it)) },
                        onReleased = { if (closing.value) finish() })
                }
            }
        }
    }

    private fun cancel() = complete(Activity.RESULT_CANCELED, Intent().putExtra(userCancelledKey, true))

    private fun complete(result: Int, data: Intent) {
        if (closing.value) return
        setResult(result, data.putExtra(retiringProcessKey, Process.myPid()))
        closing.value = true
        lifecycleScope.launch { delay(1_000); finish() }
    }

    private fun finishWithIssue(issue: PluginBrowserIssue) {
        setResult(failed, Intent().putExtra(issueKey, issue.name))
        finish()
    }

    private fun startupIssue(fallback: PluginBrowserIssue = PluginBrowserIssue.Startup): PluginBrowserIssue {
        val provider = runCatching { WebViewCompat.getCurrentWebViewPackage(this) }
        val installed = provider.getOrNull()
        Log.w(pluginBrowserLogTag, "compat SDK=${Build.VERSION.SDK_INT} provider=${installed?.packageName} version=${installed?.versionName}")
        return if (provider.isSuccess && installed == null) PluginBrowserIssue.MissingProvider else fallback
    }

    override fun onDestroy() {
        super.onDestroy()
        if (ownsProcess && isFinishing) Process.killProcess(Process.myPid())
    }

    companion object {
        private var directorySuffix: String? = null
        private var directoryProblem = PluginBrowserIssue.Startup
        private var attempt: UUID? = null
        private var retiringProcess: Int? = null
        private const val retiringProcessKey = "browser-retiring-process"
        const val failed = Activity.RESULT_FIRST_USER
        const val cookieKey = "completed-cookies"
        const val issueKey = "browser-issue"
        const val documentErrorKey = "browser-document-error"
        const val userCancelledKey = "browser-user-cancelled"

        fun recordResult(data: Intent?) {
            data?.getIntExtra(retiringProcessKey, 0)?.takeIf { it > 0 }?.let { retiringProcess = it }
        }

        suspend fun awaitPreviousExit(context: Context): Boolean {
            val previous = retiringProcess ?: return true
            val manager = context.getSystemService(ActivityManager::class.java) ?: return false
            val browserName = "${context.packageName}$processName"
            val exited = withTimeoutOrNull(2_000) {
                while (true) {
                    val processes = runCatching { manager.runningAppProcesses }.getOrNull() ?: return@withTimeoutOrNull false
                    if (processes.none { it.pid == previous && it.uid == Process.myUid() && it.processName == browserName }) break
                    delay(40)
                }
                true
            } == true
            if (exited && retiringProcess == previous) retiringProcess = null
            return exited
        }

        @SuppressLint("RequiresFeature")
        fun prepareProcess(context: Context) {
            val support = disposableBrowserSupport(context)
            support.exceptionOrNull()?.let { failure ->
                Log.w(pluginBrowserLogTag, "compat data directory capability: ${failure.javaClass.simpleName}")
                return
            }
            if (support.getOrNull() != true) {
                directoryProblem = PluginBrowserIssue.UnsupportedProvider
                return
            }
            val suffix = directorySuffixPrefix + UUID.randomUUID()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) WebView.setDataDirectorySuffix(suffix)
                else ProcessGlobalConfig.apply(ProcessGlobalConfig().setDataDirectorySuffix(context, suffix))
                directorySuffix = suffix
            } catch (failure: RuntimeException) {
                Log.w(pluginBrowserLogTag, "compat data directory: ${failure.javaClass.simpleName}")
            }
        }

        fun intent(context: Context, page: PluginBrowserPage, english: Boolean, dark: Boolean): Intent =
            Intent(context, CompatPluginBrowserActivity::class.java)
                .putExtra("id", page.id.toString()).putExtra("title", page.title).putExtra("url", page.url.toASCIIString())
                .putExtra("english", english).putExtra("dark", dark).apply {
                    page.signIn?.let { signIn ->
                        putExtra("cookies-of", signIn.cookiesOf.toASCIIString())
                        putExtra("until", signIn.until.toTypedArray())
                        putExtra("user-agent", signIn.userAgent)
                        signIn.html?.let { putExtra("html-utf8", it.encodeToByteArray()) }
                    }
                }

        private fun Intent.page(): PluginBrowserPage? {
            val id = getStringExtra("id")?.let { runCatching { UUID.fromString(it) }.getOrNull() } ?: return null
            val url = address("url") ?: return null
            val title = getStringExtra("title") ?: return null
            val signIn = if (hasExtra("cookies-of")) {
                val cookiesOf = address("cookies-of") ?: return null
                val names = getStringArrayExtra("until")?.toSet()?.takeIf { it.isNotEmpty() } ?: return null
                val agent = getStringExtra("user-agent")
                val html = if (hasExtra("html-utf8")) {
                    val bytes = getByteArrayExtra("html-utf8") ?: return null
                    if (bytes.isEmpty() || bytes.size > PluginWebSignIn.htmlLimit || !url.sameHttpsOrigin(cookiesOf)) return null
                    runCatching { bytes.decodeToString(throwOnInvalidSequence = true) }.getOrNull() ?: return null
                } else null
                PluginWebSignIn(title, url, cookiesOf, names, agent, id, html)
            } else null
            return PluginBrowserPage(title, url, id, signIn)
        }

        private fun Intent.address(key: String): URI? =
            getStringExtra(key)?.let(::uri)?.takeIf { it.isHttp() && it.scheme.equals("https", true) }
    }
}
