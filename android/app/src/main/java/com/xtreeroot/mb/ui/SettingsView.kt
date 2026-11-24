package com.xtreeroot.mb.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Scaffold
import com.xtreeroot.mb.ui.components.MBSlider as Slider
import com.xtreeroot.mb.ui.components.MBSwitch as Switch
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import com.xtreeroot.mb.ui.components.MBTopAppBar as TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.stateDescription
import com.xtreeroot.mb.ui.components.MBGroupedSection
import com.xtreeroot.mb.ui.components.MBFormRow
import com.xtreeroot.mb.ui.components.MBFormDivider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.local.OfflineMediaStore
import com.xtreeroot.mb.music.AudioQuality
import com.xtreeroot.mb.preferences.AppAppearance
import com.xtreeroot.mb.preferences.AppLanguage
import com.xtreeroot.mb.preferences.AppPreferences
import com.xtreeroot.mb.preferences.AudioNormalization
import com.xtreeroot.mb.preferences.LyricStyle
import com.xtreeroot.mb.preferences.PlayerStyle
import com.xtreeroot.mb.preferences.ProxyPolicy
import kotlin.math.roundToInt

internal val LocalMBEnglish = staticCompositionLocalOf { false }

@Composable
internal fun AppLanguage.usesEnglish(): Boolean = when (this) {
    AppLanguage.System -> LocalConfiguration.current.locales[0]?.language == "en"
    AppLanguage.Chinese -> false
    AppLanguage.English -> true
}

@Composable
internal fun mb(zh: String, en: String): String = if (LocalMBEnglish.current) en else zh

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsView(values: AppPreferences, update: ((AppPreferences) -> AppPreferences) -> Unit,
                          saved: SavedLibraryModel, browser: BrowseModel, backgroundState: AppearanceBackgroundState,
                          background: AppearanceBackgroundModel, surface: Color, openPlugins: () -> Unit, close: () -> Unit) {
    var appUpdate by rememberSaveable { mutableStateOf(false) }
    var skin by rememberSaveable { mutableStateOf(false) }
    var network by rememberSaveable { mutableStateOf(false) }
    var clearHistory by rememberSaveable { mutableStateOf(false) }
    val history by browser.history.collectAsStateWithLifecycle()
    val offline by saved.offline.collectAsStateWithLifecycle()
    BackHandler(network || skin) { network = false; skin = false }
    Scaffold(containerColor = surface, contentColor = MaterialTheme.colorScheme.onBackground, topBar = {
        TopAppBar(largeTitle = false, title = {
            Text(when { network -> mb("网络与系统代理", "Network & System Proxy")
                skin -> mb("背景皮肤", "Background Skin"); else -> "" })
        }, actions = {
            TextButton(onClick = { if (network || skin) { network = false; skin = false } else close() }) {
                Text(if (network || skin) mb("返回设置", "Back to Settings") else mb("完成", "Done"))
            }
        })
    }) { padding ->
        if (network) NetworkSettings(values.proxy, { policy -> update { it.copy(proxy = policy) } }, Modifier.padding(padding))
        else if (skin) LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MBGroupedSection { BackgroundImageFlow(backgroundState, background) } }
            item { MBGroupedSection {
                MBFormRow(mb("应用于音乐播放器背景", "Apply to Music Player"), trailing = {
                    Switch(values.backgroundStyle.appliesToPlayer, { enabled -> update { it.copy(backgroundStyle = it.backgroundStyle.copy(appliesToPlayer = enabled)) } })
                })
            } }
        }
        else LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item { MBGroupedSection(mb("视频小窗", "Video Windows")) {
                MBFormRow(mb("应用内视频小窗", "In-App Video Window"), trailing = {
                    Switch(values.inAppVideoWindow, { enabled -> update { it.copy(inAppVideoWindow = enabled) } })
                })
                MBFormDivider()
                MBFormRow(mb("离开应用时画中画", "Picture in Picture Outside App"), trailing = {
                    Switch(values.systemVideoWindow, { enabled -> update { it.copy(systemVideoWindow = enabled) } })
                })
            } }
            item { MBGroupedSection {
                MBFormRow(mb("检查版本", "App Updates"), disclosure = true, onClick = { appUpdate = true })
                MBFormDivider()
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(mb("启动时检查版本", "Check Updates on Launch"), Modifier.weight(1f))
                    androidx.compose.material3.Switch(values.automaticUpdateChecks, { enabled -> update { it.copy(automaticUpdateChecks = enabled) } })
                }
            } }
            item {
                MBGroupedSection(mb("显示", "Display")) {
                    Choice(mb("语言", "Language"), values.language, listOf(AppLanguage.System to mb("跟随系统", "System"),
                        AppLanguage.Chinese to "简体中文", AppLanguage.English to "English")) { option -> update { it.copy(language = option) } }
                    MBFormDivider()
                    Choice(mb("外观", "Appearance"), values.appearance, listOf(AppAppearance.System to mb("跟随系统", "System"),
                        AppAppearance.Light to mb("浅色", "Light"), AppAppearance.Dark to mb("深色", "Dark"))) { option -> update { it.copy(appearance = option) } }
                    MBFormDivider()
                    MBFormRow(mb("背景皮肤", "Background Skin"), disclosure = true, onClick = { skin = true })
                }
            }
            item {
                MBGroupedSection(mb("音源", "Sources")) {
                    MBFormRow(mb("插件", "Plugins"), disclosure = true, onClick = openPlugins)
                }
            }
            item {
                MBGroupedSection(mb("播放", "Playback")) {
                    SettingSwitch(mb("切换平台时暂停其他平台播放", "Pause Other Sources When Switching"), values.pauseOtherSourcesOnSwitch) { value -> update { it.copy(pauseOtherSourcesOnSwitch = value) } }
                    MBFormDivider()
                    Choice(mb("音质", "Audio Quality"), values.quality, listOf(AudioQuality.Standard to mb("标准", "Standard"),
                        AudioQuality.High to mb("高品质", "High"), AudioQuality.Lossless to mb("无损", "Lossless")),
                        hint = mb("新加载时生效；无损不可用时回退", "Applies on new streams; falls back if lossless is unavailable")) { option -> update { it.copy(quality = option) } }
                    MBFormDivider()
                    MBFormRow(mb("视频画质", "Video Quality"), detail = if (values.videoQuality == "auto") mb("自动（最高可用）", "Automatic (Best Available)") else values.videoQuality)
                    if (values.videoQuality != "auto") TextButton(onClick = { update { it.copy(videoQuality = "auto") } }) { Text(mb("重置视频画质为自动", "Reset Video Quality to Automatic")) }
                    MBFormDivider()
                    Choice(mb("响度标准化", "Loudness Normalization"), values.normalization,
                        listOf(AudioNormalization.Standard to mb("标准", "Standard"), AudioNormalization.Dynamic to mb("动态", "Dynamic"),
                            AudioNormalization.Off to mb("关闭", "Off"))) { option -> update { it.copy(normalization = option) } }
                    MBFormDivider()
                    val label = mb("应用音量", "App Volume")
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        Slider(values.volume, { volume -> update { it.copy(volume = volume) } }, modifier = Modifier.semantics { contentDescription = label })
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(label); Text("${(values.volume * 100).roundToInt()}%", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            item {
                MBGroupedSection(mb("锁屏与媒体控制", "Lock Screen & Media Controls"),
                    mb("主标题逐句显示歌词，副标题显示歌名；无歌词时显示歌名。", "The title follows the lyrics and the subtitle shows the song. Without lyrics, the title shows the song.")) {
                    SettingSwitch(mb("在主标题显示当前歌词", "Show Current Lyric in Media Title"), values.nowPlayingLyrics) { value -> update { it.copy(nowPlayingLyrics = value) } }
                }
            }
            item {
                MBGroupedSection(mb("缓存", "Cache"), mb("播放缓存按最近使用清理，已下载和导入的音频单独保留。", "Playback cache is cleared by last use. Downloaded and imported audio is kept separately.")) {
                    Choice(mb("缓存上限", "Cache Limit"), offline.limitMb, OfflineMediaStore.limitsMb.map { size ->
                        size to if (size == 0) mb("不缓存", "Off") else "$size MB"
                    }, changed = saved::setLimit)
                }
            }
            item {
                MBGroupedSection(mb("播放界面", "Player")) {
                    SettingSwitch(mb("默认打开歌词", "Open Lyrics by Default"), values.lyricsStage) { value -> update { it.copy(lyricsStage = value) } }
                    MBFormDivider()
                    SettingSwitch(mb("间奏显示热评", "Show Comments During Instrumentals"), values.hotComments) { value -> update { it.copy(hotComments = value) } }
                    MBFormDivider()
                    SettingSwitch(mb("进度条烟雾", "Progress Bar Smoke"), values.smoke) { value -> update { it.copy(smoke = value) } }
                    MBFormDivider()
                    SettingSwitch(mb("低音雾气背景", "Bass Fog Background"), values.fog) { value -> update { it.copy(fog = value) } }
                    MBFormDivider()
                    SettingSwitch(mb("从封面展开", "Expand from Cover"), values.growFromCover) { value -> update { it.copy(growFromCover = value) } }
                    MBFormDivider()
                    SettingSwitch(mb("歌词火花", "Lyric Sparks"), values.stageSparks) { value -> update { it.copy(stageSparks = value) } }
                    MBFormDivider()
                    Choice(mb("播放器样式", "Player Style"), values.playerStyle, PlayerStyle.entries.map { it to it.label() }) { option -> update { it.copy(playerStyle = option) } }
                    MBFormDivider()
                    Choice(mb("歌词舞台", "Lyric Stage"), values.lyricStyle, LyricStyle.entries.map { it to it.label() }) { option -> update { it.copy(lyricStyle = option) } }
                }
            }
            item {
                MBGroupedSection {
                    MBFormRow(mb("网络与系统代理", "Network & System Proxy"), disclosure = true, onClick = { network = true })
                    MBFormDivider()
                    MBFormRow(mb("清空搜索记录", "Clear Search History"), enabled = history.isNotEmpty(),
                        color = MaterialTheme.colorScheme.error, onClick = { clearHistory = true })
                }
            }
        }
    }
    if (appUpdate) AppUpdateDialog { appUpdate = false }
    if (clearHistory) AlertDialog(onDismissRequest = { clearHistory = false }, title = { Text(mb("清空搜索记录？", "Clear search history?")) },
        confirmButton = { TextButton(onClick = { browser.clearHistory(); clearHistory = false }) { Text(mb("清空", "Clear")) } },
        dismissButton = { TextButton(onClick = { clearHistory = false }) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun NetworkSettings(policy: ProxyPolicy, save: (ProxyPolicy) -> Unit, modifier: Modifier = Modifier) {
    var draft by rememberSaveable(policy.hosts) { mutableStateOf(policy.hosts.joinToString("\n")) }
    var invalid by rememberSaveable { mutableStateOf(false) }
    var saved by rememberSaveable { mutableStateOf(false) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        item {
            Text(mb("仅以下域名使用系统代理。其余地址直接连接。", "Only the following domains use the system proxy. Other traffic connects directly."),
                Modifier.padding(vertical = 12.dp))
            OutlinedTextField(draft, { draft = it; invalid = false; saved = false }, Modifier.fillMaxWidth(),
                label = { Text(mb("代理域名", "Proxy Domains")) }, minLines = 5, maxLines = 10, isError = invalid)
            if (invalid) Text(mb("只允许域名或 *.域名，不包含 URL、端口或账号。", "Enter domain names or *.domain, without a URL, port, or credentials."),
                color = MaterialTheme.colorScheme.error)
            if (saved) Text(mb("已保存，后续请求立即生效", "Saved. Applies to new requests."))
            TextButton(onClick = {
                val parsed = runCatching { ProxyPolicy.parse(draft) }.getOrNull()
                if (parsed == null) invalid = true else { save(parsed); draft = parsed.hosts.joinToString("\n"); saved = true }
            }) { Text(mb("保存", "Save")) }
            TextButton(onClick = { draft = ProxyPolicy().hosts.joinToString("\n"); invalid = false; saved = false }) {
                Text(mb("恢复默认域名", "Restore Default Domains"))
            }
        }
    }
}

@Composable
private fun <Value> Choice(label: String, value: Value, options: List<Pair<Value, String>>, hint: String? = null,
                           changed: (Value) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val selected = options.firstOrNull { it.first == value }?.second.orEmpty()
    Box {
        MBFormRow(label, detail = hint, modifier = Modifier.semantics { stateDescription = selected },
            disclosure = true, onClick = { expanded = true }, trailing = {
                Text(selected, color = MaterialTheme.colorScheme.onSurfaceVariant)
            })
        DropdownMenu(expanded, { expanded = false }) {
            options.forEach { (choice, text) -> DropdownMenuItem(text = { Text(text) }, onClick = { expanded = false; changed(choice) }) }
        }
    }
}

@Composable
private fun SettingSwitch(label: String, enabled: Boolean, toggle: (Boolean) -> Unit) {
    MBFormRow(label, modifier = Modifier.toggleable(enabled, role = Role.Switch, onValueChange = toggle),
        trailing = { Switch(enabled, null) })
}
