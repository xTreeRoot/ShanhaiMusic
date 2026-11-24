package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import java.io.File
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.preferences.SettingsStore
import com.xtreeroot.mb.update.AppRelease
import java.net.URI
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackgroundAndUpdatesDeviceTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val app=instrumentation.targetContext.applicationContext as MBApplication

 @Test fun backgroundCropPreviewAndRecropDoNotApplyUntilConfirmation()=runBlocking {
  val screen=start(); val original=app.settings.values.value;val saved=app.appearanceBackground.load()
  val source=Bitmap.createBitmap(600,1000,Bitmap.Config.ARGB_8888).apply {
   val canvas=android.graphics.Canvas(this)
   val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {shader=android.graphics.LinearGradient(0f,0f,600f,1000f,
    Color.rgb(50,72,115),Color.rgb(206,128,102),android.graphics.Shader.TileMode.CLAMP)}
   canvas.drawPaint(paint);paint.shader=null;paint.color=Color.rgb(250,199,133)
   canvas.drawCircle(400f,280f,110f,paint)
  }
  val cropping=mutableStateOf(true)
  val models=androidx.lifecycle.ViewModelStore()
  val model=withContext(Dispatchers.Main) {androidx.lifecycle.ViewModelProvider(models,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppearanceBackgroundModel::class.java]}
  try {
   await("background restore") {!model.state.value.busy}
   withContext(Dispatchers.Main) {screen.setContent {
    MBRoot(background=model)
    if(cropping.value) MaterialTheme {CompositionLocalProvider(LocalMBEnglish provides true) {
     BackgroundImageEditor(source,false,false,{cropping.value=false}) {model.beginPreview(it,0.2f);cropping.value=false}
    }}
   }}
   app.settings.update {it.copy(language=com.xtreeroot.mb.preferences.AppLanguage.English)}
   tap("Square");capture("android-background-crop");tap("Preview")
   awaitNode("Discovery background preview controls");awaitNode("No available sources")
   setProgress("Background transparency",0.35f)
   await("live transparency") {kotlin.math.abs(model.state.value.previewOpacity-0.65f)<0.01f}
   setProgress("Background image zoom",1.2f);setProgress("Background blur",4f)
   tap("Theme Rose");tap("Background player switch")
   assertEquals(original.backgroundStyle,app.settings.values.value.backgroundStyle)
   assertEquals(original.backgroundOpacity,app.settings.values.value.backgroundOpacity)
   assertEquals(saved!=null,app.appearanceBackground.load()!=null)
   capture("android-background-discovery-preview");tap("Use Background")
   await("confirmed image") {model.state.value.preview==null&&!model.state.value.busy}
   assertEquals(0.65f,SettingsStore(app).values.value.backgroundOpacity!!,0.01f)
   val style=SettingsStore(app).values.value.backgroundStyle
   assertEquals(1.2f,style.zoom,0.02f);assertEquals(4f,style.blur,0.02f)
   assertEquals(com.xtreeroot.mb.preferences.BackgroundAccent.Rose,style.accent);assertTrue(style.appliesToPlayer)
   val result=checkNotNull(app.appearanceBackground.load());assertEquals(result.width,result.height)
   withContext(Dispatchers.Main) {model.beginPreview(result,0.65f,style)}
   awaitNode("Background transparency");setProgress("Background transparency",0.9f);setProgress("Background blur",0f);tap("Cancel")
   await("cancel opacity") {model.state.value.preview==null}
   assertEquals(0.65f,SettingsStore(app).values.value.backgroundOpacity!!,0.01f)
   assertEquals(style,SettingsStore(app).values.value.backgroundStyle)
   assertTrue(result.sameAs(checkNotNull(app.appearanceBackground.load())))
   tap("Search");awaitNode("Search songs or videos");capture("android-background-search")
   tap("Library");awaitNode("Search Songs, Albums, Artists, Playlists");capture("android-background-library")
   tap("Me");awaitNode("Install and enable a plugin");capture("android-background-account")
   tap("Settings");awaitNode("Video Windows");capture("android-background-settings")
   tap("Done")
   val audio=File(app.cacheDir,"Skin-Fixture.wav").apply {writeBytes(com.xtreeroot.mb.playback.wave(60))}
   val local=app.local.importFile(com.xtreeroot.mb.local.LocalAudioDocument(android.net.Uri.fromFile(audio)))
   val player=withContext(Dispatchers.Main) {androidx.lifecycle.ViewModelProvider(screen)[com.xtreeroot.mb.playback.PlaybackModel::class.java]}
   try {
    withContext(Dispatchers.Main) {player.play(listOf(local.track),local.track)}
    awaitNode("Close Player");capture("android-background-player-enabled")
    tap("Close Player")
    app.settings.update {it.copy(backgroundStyle=it.backgroundStyle.copy(appliesToPlayer=false))}
    tap(local.track.title);awaitNode("Close Player");capture("android-background-player-disabled")
    tap("Close Player")
    await("player dismissed") {find("Close Player")==null}
    app.settings.update {it.copy(appearance=com.xtreeroot.mb.preferences.AppAppearance.Dark)}
    tap("Library");awaitNode("Search Songs, Albums, Artists, Playlists");capture("android-background-library-dark")
    tap("Me");awaitNode("Install and enable a plugin");tap("Settings");awaitNode("Dark");capture("android-background-settings-dark")
   } finally {withContext(Dispatchers.Main) {player.clear()};app.local.delete(local.track.sourceTrackId);audio.delete()}
  }finally {
   if(saved==null) app.appearanceBackground.clear() else app.appearanceBackground.save(saved)
   app.settings.update {original};withContext(Dispatchers.Main) {models.clear();screen.finish()}
  }
 }
 @Test fun cancellingImagePreviewDoesNotApplyCandidate()=runBlocking {
  val screen=start();val original=app.settings.values.value;val saved=app.appearanceBackground.load()
  val source=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888)
  val models=androidx.lifecycle.ViewModelStore()
  val model=withContext(Dispatchers.Main) {androidx.lifecycle.ViewModelProvider(models,androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AppearanceBackgroundModel::class.java]}
  try {
   await("background restore") {!model.state.value.busy}
   app.settings.update {it.copy(language=com.xtreeroot.mb.preferences.AppLanguage.English)}
   withContext(Dispatchers.Main) {screen.setContent {MBRoot(background=model)};model.beginPreview(source,0.2f)}
   awaitNode("Discovery background preview controls");setProgress("Background transparency",0.1f);tap("Cancel")
   await("cancelled image") {model.state.value.preview==null}
   assertEquals(original.backgroundOpacity,app.settings.values.value.backgroundOpacity)
   assertEquals(saved!=null,app.appearanceBackground.load()!=null)
  }finally {app.settings.update {original};withContext(Dispatchers.Main) {models.clear();screen.finish()}}
 }
 private suspend fun setProgress(label:String,value:Float) {
  val node=awaitNode(label)
  fun progress(item:AccessibilityNodeInfo):AccessibilityNodeInfo? {
   if(item.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)) return item
   return (0 until item.childCount).firstNotNullOfOrNull {item.getChild(it)?.let(::progress)}
  }
  val control=progress(node) ?: generateSequence(node) {it.parent}.first {it.actionList.contains(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS)}
  assertTrue(control.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS.id,
   android.os.Bundle().apply {putFloat(AccessibilityNodeInfo.ACTION_ARGUMENT_PROGRESS_VALUE,value)}))
 }
 @Test fun optingOutPersistsStopsAutomaticRequestsAndRetainsManualChecks()=runBlocking {
  val screen=start();val original=app.settings.values.value
  var requests=0
  val release=AppRelease("mb-android-v99.0.0","99.0.0",URI("https://github.com/xTreeRoot/ShanhaiMusic/releases/tag/mb-android-v99.0.0"),"Fixture update preserves the library",URI("https://github.com/xTreeRoot/ShanhaiMusic/releases/download/mb-android-v99.0.0/MB-android-v99.0.0.apk"),3,"a".repeat(64))
  val model=withContext(Dispatchers.Main) {AppUpdateModel(app) {requests++;release}}
  try {
   app.settings.update {it.copy(automaticUpdateChecks=true)}
   withContext(Dispatchers.Main) {model.check(automatic=true)}
   await("automatic prompt: requests=$requests state=${model.state.value}") {model.state.value.automaticPrompt}
   withContext(Dispatchers.Main) {screen.setContent {MaterialTheme {CompositionLocalProvider(LocalMBEnglish provides true) {
    val state by model.state.collectAsState()
    if(state.automaticPrompt) AppUpdateContent(model.version,state,{model.check()},{},{},{model.hideAutomaticPrompt()},dontDisturb={
     app.settings.update {it.copy(automaticUpdateChecks=false)};model.hideAutomaticPrompt()
    }) else Text("Prompt closed")
   }}}}
   val optOut=Rect().also(awaitNode("Don't Remind Me")::getBoundsInScreen)
   val update=Rect().also(awaitNode("Download Update")::getBoundsInScreen)
   assertTrue(kotlin.math.abs(optOut.centerY()-update.centerY())<=2);assertTrue(optOut.right<=update.left)
   capture("android-startup-update");tap("Don't Remind Me");await("opted out") {!model.state.value.automaticPrompt};assertFalse(SettingsStore(app).values.value.automaticUpdateChecks)
   withContext(Dispatchers.Main) {model.check(automatic=true)};assertEquals(1,requests)
   withContext(Dispatchers.Main) {model.check()};await("manual check") {requests==2&&!model.state.value.checking}
   assertNotNull(model.state.value.release)
  }finally {app.settings.update {original};withContext(Dispatchers.Main) {model.cancel();screen.finish()}}
 }
 @Test fun latestVersionAndNetworkFailureNeverRaiseAnAutomaticPrompt()=runBlocking {
  val original=app.settings.values.value
  try {
   app.settings.update {it.copy(automaticUpdateChecks=true)}
   val current=AppRelease("mb-android-v0.1.29","0.1.29",URI("https://github.com/xTreeRoot/ShanhaiMusic/releases/tag/mb-android-v0.1.29"),"Current",URI("https://github.com/xTreeRoot/ShanhaiMusic/releases/download/mb-android-v0.1.29/MB-android-v0.1.29.apk"),3,"a".repeat(64))
   val same=withContext(Dispatchers.Main) {AppUpdateModel(app) {current}}
   withContext(Dispatchers.Main) {same.check(automatic=true)}
   await("already current") {!same.state.value.checking}
   assertTrue(same.state.value.checked);assertNull(same.state.value.release);assertFalse(same.state.value.automaticPrompt)
   val failed=withContext(Dispatchers.Main) {AppUpdateModel(app) {throw java.io.IOException("Fixture offline")}}
   withContext(Dispatchers.Main) {failed.check(automatic=true)}
   await("offline check") {!failed.state.value.checking}
   assertNotNull(failed.state.value.error);assertFalse(failed.state.value.automaticPrompt)
  }finally {app.settings.update {original}}
 }
 @Test fun cancelledAutomaticRequestIgnoresALateRelease()=runBlocking {
  val original=app.settings.values.value;val gate=CompletableDeferred<AppRelease?>();val entered=CompletableDeferred<Unit>()
  try {
   app.settings.update {it.copy(automaticUpdateChecks=true)}
   val model=withContext(Dispatchers.Main) {AppUpdateModel(app) {entered.complete(Unit);withContext(NonCancellable) {gate.await()}}}
   withContext(Dispatchers.Main) {model.check(automatic=true)};withTimeout(5_000) {entered.await()}
   withContext(Dispatchers.Main) {model.cancel()};gate.complete(null)
   withContext(Dispatchers.Main) {yield()}
   assertFalse(model.state.value.checking);assertFalse(model.state.value.checked);assertFalse(model.state.value.automaticPrompt)
   assertNull(model.state.value.error)
  }finally {gate.complete(null);app.settings.update {original}}
 }
 private fun start()=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
 private suspend fun await(label:String="condition",check:()->Boolean) {
  val completed=withTimeoutOrNull(8_000) {while(!check()) delay(25);true}==true
  if(!completed) {
   fun dump(node:AccessibilityNodeInfo):String = "${node.text}|${node.contentDescription}|click=${node.isClickable}|enabled=${node.isEnabled}\n"+(0 until node.childCount).mapNotNull {node.getChild(it)}.joinToString("") {dump(it)}
   File(app.cacheDir,"background-ui-failure.txt").writeText(instrumentation.uiAutomation.rootInActiveWindow?.let(::dump).orEmpty())
   capture("background-ui-failure")
  }
  assertTrue("Timed out: $label",completed)
 }
 private fun find(label:String):AccessibilityNodeInfo? {
  fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
   if(node.isVisibleToUser&&(node.text?.toString()==label||node.contentDescription?.toString()==label)) return node
   return (0 until node.childCount).firstNotNullOfOrNull {node.getChild(it)?.let(::visit)}
  }
  return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
 }
 private suspend fun awaitNode(label:String):AccessibilityNodeInfo {await("node $label") {find(label)!=null};return checkNotNull(find(label))}
 private fun capture(name:String) {
  instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(100,5_000)
  val frame=instrumentation.uiAutomation.takeScreenshot() ?: return
  try {File(app.cacheDir,"$name.png").outputStream().use {frame.compress(Bitmap.CompressFormat.PNG,100,it)}}finally {frame.recycle()}
 }
 private suspend fun tap(label:String) {
  instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(100,5_000)
  val node=awaitNode(label);val action=generateSequence(node) {it.parent}.first {it.isClickable}
  assertTrue(action.isEnabled)
  val bounds=Rect().also(action::getBoundsInScreen);val down=SystemClock.uptimeMillis()
  for(kind in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
   val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),kind,bounds.exactCenterX(),bounds.exactCenterY(),0)
   event.source=InputDevice.SOURCE_TOUCHSCREEN
   try {assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))}finally {event.recycle()}
  }
  instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(100,5_000)
 }
}
