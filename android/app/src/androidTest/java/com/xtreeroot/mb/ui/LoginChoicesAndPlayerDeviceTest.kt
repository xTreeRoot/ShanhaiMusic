package com.xtreeroot.mb.ui

import android.content.Intent
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.xtreeroot.mb.MainActivity
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.music.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LoginChoicesAndPlayerDeviceTest {
 private val instrumentation=InstrumentationRegistry.getInstrumentation()
 private val app=instrumentation.targetContext.applicationContext as MBApplication

 @Test fun loginChoiceWaitsForTapAndCancelDoesNotStartAProvider()=runBlocking {
  val screen=start();val store=ViewModelStore();val chosen=mutableListOf<String>();val confirmation=CompletableDeferred<SourceAccount>()
  val source=object:SignInMethodSource {
   override val id="choice-fixture"
   override val signInMethods=listOf(SignInMethod("first","First Method"),SignInMethod("second","Second Method"))
   override suspend fun search(query:String,page:Int)=TrackPage(emptyList(),false)
   override suspend fun account():SourceAccount?=null
   override suspend fun signIn(prompt:suspend(SignInPrompt)->Unit):SourceAccount=error("Default login must not start")
   override suspend fun signIn(method:String,prompt:suspend(SignInPrompt)->Unit):SourceAccount {
    chosen.add(method);prompt(SignInPrompt(null,message="Selected $method"));return confirmation.await()
   }
   override suspend fun signOut()=Unit
  }
  try {
   val model=withContext(Dispatchers.Main) {ViewModelProvider(store,ViewModelProvider.AndroidViewModelFactory.getInstance(app))[AccountModel::class.java].also {it.activate(source)}}
   await {model.account.value.canSignIn}
   withContext(Dispatchers.Main) {
    screen.setContent {MaterialTheme {CompositionLocalProvider(LocalMBEnglish provides true) {
     val state by model.account.collectAsState();if(state.signingIn) SignInDialog(state,model)
    }}}
    model.signIn()
   }
   awaitNode("First Method");assertTrue(chosen.isEmpty());tap("Cancel")
   await {!model.account.value.signingIn};assertTrue(chosen.isEmpty())
   withContext(Dispatchers.Main) {model.signIn()}
   tap("Second Method");awaitNode("Selected second");assertEquals(listOf("second"),chosen)
   tap("Cancel");confirmation.complete(SourceAccount("late","Late account",null))
   await {!model.account.value.signingIn};assertNull(model.account.value.account)
  } finally {withContext(Dispatchers.Main) {store.clear();screen.finish()}}
 }

 @Test fun networkPermissionDisclosureStartsCollapsedAndCanToggle()=runBlocking {
  val screen=start()
  try {
   withContext(Dispatchers.Main) {screen.setContent {MaterialTheme {CompositionLocalProvider(LocalMBEnglish provides true) {
    Surface(Modifier.fillMaxSize()) {Column {PluginNetworkPermissions("fixture",listOf("api.example.test","image.example.test"))}}
   }}}}
   awaitNode("Network permissions");assertNull(find("api.example.test"));tap("Expand")
   awaitNode("api.example.test");tap("Collapse");await {find("api.example.test")==null}
  } finally {withContext(Dispatchers.Main) {screen.finish()}}
 }

 @Test fun horizontalPlayerSwipesNavigateOnceAndLeaveVerticalScrollAndSeekingAlone()=runBlocking {
  val screen=start();val index=mutableIntStateOf(1);val progress=mutableFloatStateOf(0.2f)
  try {
   withContext(Dispatchers.Main) {screen.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {
    Column(Modifier.padding(24.dp)) {
     Column(Modifier.fillMaxWidth().height(320.dp).musicTrackSwipes(index.intValue,{index.intValue--},{index.intValue++})
      .semantics {contentDescription="Player swipe stage"}.verticalScroll(rememberScrollState())) {
       repeat(20) {Text("Lyric $it",Modifier.padding(10.dp))}
     }
     Slider(progress.floatValue,{progress.floatValue=it},modifier=Modifier.semantics {contentDescription="Seek progress"})
    }
   }}}}
   val area=Rect().also {awaitNode("Player swipe stage").getBoundsInScreen(it)}
   drag(area,0.85f,0.5f,0.15f,0.5f);await {index.intValue==2}
   drag(area,0.2f,0.5f,0.85f,0.5f);await {index.intValue==1}
   drag(area,0.5f,0.8f,0.5f,0.2f);assertEquals(1,index.intValue)
   drag(area,0.5f,0.5f,0.55f,0.5f);assertEquals(1,index.intValue)
   val seek=Rect().also {awaitNode("Seek progress").getBoundsInScreen(it)}
   drag(seek,0.25f,0.5f,0.8f,0.5f);await {progress.floatValue>0.6f};assertEquals(1,index.intValue)
  } finally {withContext(Dispatchers.Main) {screen.finish()}}
 }
 private fun start()=instrumentation.startActivitySync(Intent(app,MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
 private suspend fun await(check:()->Boolean) {withTimeout(5_000) {while(!check()) delay(25)}}
 private fun find(label:String):AccessibilityNodeInfo? {
  fun visit(node:AccessibilityNodeInfo):AccessibilityNodeInfo? {
   if(node.isVisibleToUser&&(node.text?.toString()==label||node.contentDescription?.toString()==label)) return node
   return (0 until node.childCount).firstNotNullOfOrNull {node.getChild(it)?.let(::visit)}
  }
  return instrumentation.uiAutomation.rootInActiveWindow?.let(::visit)
 }
 private suspend fun awaitNode(label:String):AccessibilityNodeInfo {await {find(label)!=null};return checkNotNull(find(label))}
 private suspend fun tap(label:String) {val node=awaitNode(label);val action=generateSequence(node) {it.parent}.first {it.isClickable};assertTrue(action.performAction(AccessibilityNodeInfo.ACTION_CLICK))}
 private fun drag(area:Rect,x0:Float,y0:Float,x1:Float,y1:Float) {
  // 等待上一手势触发的 Compose 重建完成，避免测试把新触点送进旧 pointerInput。
  instrumentation.waitForIdleSync()
  instrumentation.uiAutomation.waitForIdle(100,5_000)
  val down=SystemClock.uptimeMillis()
  for(step in 0..20) {
   val fraction=step/20f;val action=when(step) {0->MotionEvent.ACTION_DOWN;20->MotionEvent.ACTION_UP;else->MotionEvent.ACTION_MOVE}
   val event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,area.left+area.width()*(x0+(x1-x0)*fraction),area.top+area.height()*(y0+(y1-y0)*fraction),0)
   event.source=InputDevice.SOURCE_TOUCHSCREEN
   try {assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
   SystemClock.sleep(12)
  }
  instrumentation.waitForIdleSync()
  instrumentation.uiAutomation.waitForIdle(100,5_000)
 }
}
