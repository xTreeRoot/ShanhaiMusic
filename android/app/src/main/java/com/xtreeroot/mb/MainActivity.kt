package com.xtreeroot.mb

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.xtreeroot.mb.playback.PlaybackModel
import com.xtreeroot.mb.plugin.PluginRepository
import com.xtreeroot.mb.ui.MBRoot
import com.xtreeroot.mb.ui.PluginManagerModel

internal class MainActivity : ComponentActivity() {
    private val plugins by viewModels<PluginManagerModel>()
    private val playback by viewModels<PlaybackModel>()
    val pictureInPicture = androidx.compose.runtime.mutableStateOf(false)
    val restoreVideoWindow = androidx.compose.runtime.mutableIntStateOf(0)
    private var videoWindowEligible = false
    private var videoWindowAspect = 16f / 9f

    fun configureVideoWindow(eligible: Boolean, playing: Boolean, aspect: Float) {
        videoWindowEligible = eligible
        videoWindowAspect = aspect.takeIf { it.isFinite() && it > 0 }?.coerceIn(1f / 2.39f, 2.39f) ?: (16f / 9f)
        if (packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            val action = android.app.RemoteAction(android.graphics.drawable.Icon.createWithResource(this,
                if (playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                if (playing) "暂停" else "播放", if (playing) "暂停" else "播放",
                android.app.PendingIntent.getBroadcast(this, 0, Intent(packageName + ".VIDEO_TOGGLE").setPackage(packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            val builder = android.app.PictureInPictureParams.Builder().setActions(listOf(action))
                .setAspectRatio(android.util.Rational((videoWindowAspect * 1000).toInt(), 1000))
            if (android.os.Build.VERSION.SDK_INT >= 31) builder.setAutoEnterEnabled(eligible).setSeamlessResizeEnabled(true)
            setPictureInPictureParams(builder.build())
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (videoWindowEligible && !isInPictureInPictureMode &&
            packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            runCatching { enterPictureInPictureMode(android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational((videoWindowAspect * 1000).toInt(), 1000)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        val wasActive = pictureInPicture.value
        pictureInPicture.value = isInPictureInPictureMode
        if (wasActive && !isInPictureInPictureMode) {
            if (lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) restoreVideoWindow.intValue++
            else {
                if (playback.playback.value.playWhenReady) playback.toggle()
                playback.closeVideo()
            }
        }
    }
    private val videoReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            if (intent.action == packageName + ".VIDEO_TOGGLE") playback.toggle()
        }
    }
    override fun onDestroy() {
        unregisterReceiver(videoReceiver)
        super.onDestroy()
    }

    private var pendingVoiceSearch: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableMBEdgeToEdge()
        androidx.core.content.ContextCompat.registerReceiver(this, videoReceiver,
            android.content.IntentFilter(packageName + ".VIDEO_TOGGLE"), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED)
        if (savedInstanceState == null) readIncomingIntent(intent)
        setContent { MBRoot(player = playback, plugins = plugins) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readIncomingIntent(intent)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) playIncomingSearch()
    }

    private fun readIncomingIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let(PluginRepository::fromInstallLink)?.let(plugins::openRepository)
            MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH -> {
                pendingVoiceSearch = intent.getStringExtra(SearchManager.QUERY).orEmpty().take(256)
                if (hasWindowFocus()) playIncomingSearch()
            }
        }
    }

    private fun playIncomingSearch() {
        val query = pendingVoiceSearch ?: return
        pendingVoiceSearch = null
        playback.playFromSearch(query)
    }
}
