package com.xtreeroot.mb.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import com.xtreeroot.mb.ui.components.MBTextButton as FilledTonalButton
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.ui.components.MBGroupedSection
import com.xtreeroot.mb.ui.components.MBFormRow
import com.xtreeroot.mb.ui.components.MBFormDivider
import com.xtreeroot.mb.R
import com.xtreeroot.mb.local.LocalAudioDocument
import com.xtreeroot.mb.local.LocalMusicIndex
import com.xtreeroot.mb.local.LocalTrack
import com.xtreeroot.mb.local.matchesLocalMusic
import com.xtreeroot.mb.music.Track
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class LocalLibraryModel(application: Application) : AndroidViewModel(application) {
    private val source = (application as MBApplication).local
    private val state = MutableStateFlow(LoadState(emptyList<LocalTrack>()))
    val library = state.asStateFlow()

    fun refresh() = update { }

    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        update { importFiles(uris.map { LocalAudioDocument(it) }) }
    }

    fun importFolder(uri: Uri?) {
        if (uri == null) return
        update {
            val files = source.audioDocuments(uri)
            if (files.isEmpty()) throw IllegalStateException("文件夹内没有可导入的音频")
            importFiles(files)
        }
    }

    private suspend fun importFiles(documents: List<LocalAudioDocument>) {
        var failed = 0
        for (document in documents.distinctBy(LocalAudioDocument::uri)) {
            try { source.importFile(document) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { failed++ }
        }
        if (failed > 0) throw IllegalStateException("$failed 个文件未能导入")
    }

    fun attach(trackId: String, uri: Uri) = update { source.attachLyrics(trackId, uri) }
    fun delete(trackId: String) = update { source.delete(trackId) }

    private fun update(action: suspend () -> Unit) {
        if (state.value.loading) return
        state.value = state.value.copy(loading = true, error = null)
        viewModelScope.launch {
            var error: String? = null
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { error = failure.message ?: "操作失败" }
            try { state.value = LoadState(source.catalog.list(), error = error) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { state.value = state.value.copy(loading = false, error = failure.message ?: "资料库读取失败") }
        }
    }
}

private enum class Category(val chinese: String, val english: String) {
    Songs("歌曲", "Songs"), Albums("专辑", "Albums"), Artists("作者", "Artists"),
    Favorites("喜欢", "Liked Songs"), Recent("最近播放", "Recently Played"),
}

private enum class LibraryPage { Downloads, Playlists, DataLocation }

@Composable
internal fun LocalLibraryView(play: (List<Track>, Track) -> Unit, playGroup: (List<Track>, Boolean) -> Unit, saved: SavedLibraryModel,
                              model: LocalLibraryModel = viewModel()) {
    val state by model.library.collectAsStateWithLifecycle()
    val refreshing by saved.refreshing.collectAsStateWithLifecycle()
    val portable by saved.portable.status.collectAsStateWithLifecycle()
    val offline by saved.offline.collectAsStateWithLifecycle()
    val transfers by saved.transfers.collectAsStateWithLifecycle()
    val activity by saved.activity.collectAsStateWithLifecycle()
    val playlists by saved.playlists.collectAsStateWithLifecycle()
    var category by rememberSaveable { mutableStateOf<Category?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var collectionId by rememberSaveable { mutableStateOf<String?>(null) }
    var collectionCategory by rememberSaveable { mutableStateOf<Category?>(null) }
    var categoryQuery by rememberSaveable { mutableStateOf("") }
    var collectionQuery by rememberSaveable { mutableStateOf("") }
    var editingLyrics by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by rememberSaveable { mutableStateOf<String?>(null) }
    var page by rememberSaveable { mutableStateOf<LibraryPage?>(null) }
    var openedPlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var addMenu by remember { mutableStateOf(false) }
    var clearCache by rememberSaveable { mutableStateOf(false) }
    var createPlaylist by rememberSaveable { mutableStateOf(false) }
    var renamePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    var deletePlaylist by rememberSaveable { mutableStateOf<String?>(null) }
    val importAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments(), model::import)
    val importFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree(), model::importFolder)
    val importLyrics = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = editingLyrics
        if (id != null && uri != null) model.attach(id, uri)
        editingLyrics = null
    }
    LaunchedEffect(portable.savedAt) { model.refresh() }
    val resources by saved.resources.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val index = remember(state.value, offline.downloads, locale) { LocalMusicIndex(state.value, offline.downloads, locale) }
    val imports = remember(state.value) { state.value.mapTo(mutableSetOf()) { it.track.id } }
    fun openCollection(kind: Category, id: String) { collectionQuery = ""; collectionCategory = kind; collectionId = id }
    fun closeCollection() { collectionId = null; collectionCategory = null; collectionQuery = "" }
    fun openPlaylist(id: String? = null) { openedPlaylist = id; page = LibraryPage.Playlists }
    fun closePage() { page = null; openedPlaylist = null }
    BackHandler(page != null || collectionId != null || category != null) {
        when { page != null -> closePage(); collectionId != null -> closeCollection()
            else -> { category = null; categoryQuery = "" } }
    }
    when (page) {
        LibraryPage.Downloads -> { DownloadsPage(saved, play) { page = null }; return }
        LibraryPage.Playlists -> { PlaylistsPage(saved, index, play, playGroup, openedPlaylist, ::closePage); return }
        LibraryPage.DataLocation -> { DataLocationPage(saved.portable) { page = null }; return }
        null -> Unit
    }
    val home = category == null && collectionId == null
    val searching = home && query.isNotBlank()
    val activeCategory = collectionCategory ?: category ?: Category.Songs
    val collections = when (activeCategory) {
        Category.Albums -> index.albums
        Category.Artists -> index.artists
        else -> null
    }
    val collection = collections?.firstOrNull { it.id == collectionId }
    val entries = if (searching) index.songs else when (activeCategory) {
        Category.Albums, Category.Artists -> collection?.tracks.orEmpty()
        Category.Recent -> index.songs.filter { (activity[it.id]?.playedAt ?: 0) > 0 }.sortedByDescending { activity[it.id]?.playedAt }.take(100)
        Category.Favorites -> index.songs.filter { (activity[it.id]?.likedAt ?: 0) > 0 }.sortedByDescending { activity[it.id]?.likedAt }
        Category.Songs -> index.songs
    }
    val matching = index.filter(entries, if (home) query else collectionQuery)
    val matchedAlbums = if (searching) index.albums.filter { matchesLocalMusic(it.title, query) } else emptyList()
    val matchedArtists = if (searching) index.artists.filter { matchesLocalMusic(it.title, query) } else emptyList()
    val matchedPlaylists = if (searching) playlists.filter { matchesLocalMusic(it.name, query) } else emptyList()
    val songsById = remember(index) { index.songs.associateBy(Track::id) }
    val listState = key(category, collectionId) { rememberLazyListState() }
    RefreshableList(refreshing = refreshing || state.loading, refresh = { model.refresh(); saved.refresh() }, state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
        item(key = "local-controls") {
            Column {
                if (!home) {
                    LibraryPageHeader(if (collectionId == null) mb(activeCategory.chinese, activeCategory.english) else null,
                        back = { if (collectionId != null) closeCollection() else { category = null; categoryQuery = "" } })
                    if (collections == null || collectionId != null) {
                        LocalCollectionHero(collection?.title ?: mb(activeCategory.chinese, activeCategory.english), collection?.artist.orEmpty(),
                            entries, collection?.artwork ?: entries.firstOrNull { it.artworkUrl != null })
                    }
                    if (collections != null && collectionId == null) LocalMusicSearch(categoryQuery, { categoryQuery = it }, mb("在此页面搜索", "Search This Page"))
                    else LocalMusicSearch(collectionQuery, { collectionQuery = it }, mb("在此页面搜索", "Search This Page"))
                } else {
                    Row(Modifier.fillMaxWidth().padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        LocalMusicSearch(query, { query = it }, mb("搜索本地歌曲、专辑、作者、歌单", "Search Songs, Albums, Artists, Playlists"),
                            Modifier.weight(1f))
                        Box {
                            IconButton(onClick = { addMenu = true }) { Icon(Icons.Default.Add, mb("添加到资料库", "Add to Library")) }
                            DropdownMenu(addMenu, { addMenu = false }) {
                                DropdownMenuItem(text = { Text(mb("导入音频", "Import Audio")) }, enabled = !state.loading, onClick = {
                                    addMenu = false; importAudio.launch(arrayOf("audio/*", "application/ogg"))
                                })
                                DropdownMenuItem(text = { Text(mb("导入文件夹", "Import Folder")) }, enabled = !state.loading, onClick = {
                                    addMenu = false; importFolder.launch(null)
                                })
                                DropdownMenuItem(text = { Text(mb("新建歌单", "New Playlist")) }, onClick = {
                                    addMenu = false; createPlaylist = true
                                })
                            }
                        }
                    }
                    if (!searching) MBGroupedSection {
                        Category.entries.forEachIndexed { position, item ->
                            if (position > 0) MBFormDivider()
                            val count = when (item) {
                                Category.Songs -> index.songs.size; Category.Albums -> index.albums.size; Category.Artists -> index.artists.size
                                Category.Favorites -> index.songs.count { (activity[it.id]?.likedAt ?: 0) > 0 }
                                Category.Recent -> index.songs.count { (activity[it.id]?.playedAt ?: 0) > 0 }.coerceAtMost(100)
                            }
                            MBFormRow(mb(item.chinese, item.english), leading = { LibraryCategoryIcon(item) },
                                trailing = { Text(count.toString(), color = MaterialTheme.colorScheme.onSurfaceVariant) },
                                disclosure = true, onClick = { categoryQuery = ""; collectionQuery = ""; category = item })
                        }
                    }
                }
            }
        }
        if (home && !searching && resources.isNotEmpty()) {
            item(key = "resource-heading") { LibrarySearchHeading(mb("已保存资源", "Saved Resources")) }
            items(resources, key = { "resource:${it.id}" }) { track -> ResourceRow(track, saved, inLibrary = true) { play(listOf(track), track) } }
        }
        state.error?.let { error -> item { Retry(error, model::refresh) } }
        if (home) activeDownloadSection(transfers, saved::cancel)
        if (home && !searching && index.recentAdditions.isNotEmpty()) {
            item(key = "recent-additions") { RecentAdditions(index.recentAdditions, play) }
        }
        if (home && !searching) {
            item(key = "playlist-section") {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    val allPlaylistsLabel = mb("本地歌单", "Local Playlists")
                    Text(mb("我的歌单", "My Playlists"), Modifier.weight(1f).semantics { heading() },
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    TextButton(onClick = { openPlaylist() }, modifier = Modifier.semantics {
                        contentDescription = allPlaylistsLabel
                    }) { Text(mb("全部", "See All")) }
                }
            }
            items(playlists, key = { "home-playlist:${it.id}" }) { playlist ->
                LocalGroupRow(playlist.name, "", playlist.trackIds.mapNotNull(songsById::get), actions = {
                    PlaylistMenu(playlist, { renamePlaylist = playlist.id }, { deletePlaylist = playlist.id })
                }) { openPlaylist(playlist.id) }
            }
            item(key = "create-playlist") {
                TextButton(onClick = { createPlaylist = true }, modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(mb("新建歌单", "New Playlist"))
                }
            }
        }
        if (!home && collections != null && collectionId == null) {
            val filteredCollections = collections.filter { matchesLocalMusic(it.title, categoryQuery) }
            if (!state.loading && filteredCollections.isEmpty()) item { EmptyMessage(mb("没有匹配的分类", "No Matching Collections")) }
            items(filteredCollections, key = { it.id }) { group ->
                LocalGroupRow(group.title, group.artist, group.tracks, group.artwork) { openCollection(activeCategory, group.id) }
            }
        } else if (!home || searching) {
            if (searching && matching.isNotEmpty()) item { LibrarySearchHeading(mb("歌曲", "Songs")) }
            if (if (searching) matching.isNotEmpty() else entries.isNotEmpty()) item { LocalPlaybackButtons(matching, playGroup) }
            if (!searching && !state.loading && matching.isEmpty()) item {
                EmptyMessage(if (entries.isNotEmpty()) mb("没有匹配的歌曲", "No Matching Songs") else mb("暂无本地曲目", "No Local Tracks"))
            }
            items(matching, key = Track::id) { track ->
                var menu by remember { mutableStateOf(false) }
                val trackActions = LocalTrackActions.current
                val favorite = (activity[track.id]?.likedAt ?: 0) > 0
                TrackRow(track, actions = {
                    Box {
                        IconButton(enabled = !state.loading, onClick = { menu = true }) {
                            Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}"))
                        }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem(text = { Text(if (favorite) mb("取消喜欢", "Unlike") else mb("喜欢", "Like")) },
                                onClick = { menu = false; saved.toggleFavorite(track) })
                            if (track.id in imports) {
                                DropdownMenuItem(text = { Text(mb("导入 LRC", "Import LRC")) }, onClick = {
                                    menu = false; editingLyrics = track.sourceTrackId; importLyrics.launch(arrayOf("*/*"))
                                })
                                DropdownMenuItem(text = { Text(mb("删除音频", "Delete Audio")) }, onClick = {
                                    menu = false; deleting = track.sourceTrackId
                                })
                            }
                            DropdownMenuItem(text = { Text(mb("播放、歌单与导出", "Playback, Playlists & Export")) }, onClick = { menu = false; trackActions(track) })
                        }
                    }
                }) { play(matching, track) }
            }
            if (matchedAlbums.isNotEmpty()) item { LibrarySearchHeading(mb("专辑", "Albums")) }
            items(matchedAlbums, key = { "album:${it.id}" }) { group ->
                LocalGroupRow(group.title, group.artist, group.tracks, group.artwork) { openCollection(Category.Albums, group.id) }
            }
            if (matchedArtists.isNotEmpty()) item { LibrarySearchHeading(mb("作者", "Artists")) }
            items(matchedArtists, key = { "artist:${it.id}" }) { group ->
                LocalGroupRow(group.title, group.artist, group.tracks, group.artwork) { openCollection(Category.Artists, group.id) }
            }
            if (matchedPlaylists.isNotEmpty()) item { LibrarySearchHeading(mb("歌单", "Playlists")) }
            items(matchedPlaylists, key = { "playlist:${it.id}" }) { playlist ->
                LocalGroupRow(playlist.name, "", playlist.trackIds.mapNotNull(songsById::get)) { openPlaylist(playlist.id) }
            }
            if (searching && !state.loading && matching.isEmpty() && matchedAlbums.isEmpty() && matchedArtists.isEmpty() && matchedPlaylists.isEmpty()) {
                item { EmptyMessage(mb("没有匹配的本地内容", "No Matching Library Items")) }
            }
        }
        if (home) item(key = "library-storage") {
            MBGroupedSection {
                MBFormRow(mb("下载与缓存", "Downloads & Cache"), disclosure = true, onClick = { page = LibraryPage.Downloads })
                MBFormDivider()
                MBFormRow(mb("数据位置", "Data Location"), disclosure = true, onClick = { page = LibraryPage.DataLocation })
            }
            MBGroupedSection {
                MBFormRow(mb("播放缓存", "Playback Cache"), trailing = {
                    Text(android.text.format.Formatter.formatShortFileSize(context, offline.cacheBytes), color = MaterialTheme.colorScheme.onSurfaceVariant)
                })
                MBFormDivider()
                MBFormRow(mb("清空播放缓存", "Clear Playback Cache"), color = MaterialTheme.colorScheme.error,
                    enabled = offline.cacheBytes > 0, onClick = { clearCache = true })
            }
        }
    }
    if (clearCache) LibraryConfirmation(mb("清空播放缓存？", "Clear playback cache?"),
        mb("已下载和导入的音频会保留。", "Downloaded and imported audio will be kept."), mb("清空", "Clear"), { clearCache = false }) {
        clearCache = false; saved.clearCache()
    }
    if (createPlaylist) PlaylistNameDialog(mb("新建歌单", "New Playlist"), close = { createPlaylist = false }) { name ->
        createPlaylist = false; saved.create(name)
    }
    playlists.firstOrNull { it.id == renamePlaylist }?.let { playlist ->
        RenameLocalPlaylist(playlist, saved) { renamePlaylist = null }
    }
    playlists.firstOrNull { it.id == deletePlaylist }?.let { playlist ->
        DeleteLocalPlaylist(playlist, saved, { deletePlaylist = null })
    }
    deleting?.let { id ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text(mb("删除本地音频？", "Delete Local Audio?")) },
            text = { Text(mb("只删除山海保存的副本，不影响导入前的文件。", "Only the copy stored by Shanhai will be deleted. The original will be kept.")) },
            confirmButton = { TextButton(onClick = { deleting = null; model.delete(id) }) { Text(mb("删除", "Delete")) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(mb("取消", "Cancel")) } })
    }
}

@Composable
private fun LibraryCategoryIcon(category: Category) {
    val modifier = Modifier.size(22.dp)
    val tint = MaterialTheme.colorScheme.primary
    when (category) {
        Category.Favorites -> Icon(Icons.Default.Favorite, null, modifier, tint)
        Category.Artists -> Icon(Icons.Default.Person, null, modifier, tint)
        else -> Icon(painterResource(when (category) {
            Category.Albums -> R.drawable.ic_library_album
            Category.Recent -> R.drawable.ic_library_recent
            else -> R.drawable.ic_music_note
        }), null, modifier, tint)
    }
}

@Composable
private fun RecentAdditions(tracks: List<Track>, play: (List<Track>, Track) -> Unit) {
    Text(mb("最近添加", "Recently Added"), Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items(tracks, key = Track::id) { track ->
            val label = mb("播放 ${track.title}", "Play ${track.title}")
            Column(Modifier.width(116.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button) { play(tracks, track) }
                .semantics { contentDescription = label }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Artwork(track, Modifier.size(116.dp))
                Text(track.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (track.artist.isNotBlank()) Text(track.artist, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
internal fun LocalMusicSearch(query: String, change: (String) -> Unit, label: String, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    OutlinedTextField(query, onValueChange = change, singleLine = true,
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp), placeholder = { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = { Icon(Icons.Default.Search, null) }, trailingIcon = if (query.isEmpty()) null else {
            { IconButton(onClick = { change("") }) { Icon(Icons.Default.Close, mb("清空搜索", "Clear Search")) } }
        }, colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = Color.Transparent,
            focusedContainerColor = colors.surfaceContainer, unfocusedContainerColor = colors.surfaceContainer),
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }))
}

@Composable
private fun LibrarySearchHeading(title: String) {
    Text(title, Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
}

@Composable
internal fun LocalGroupRow(title: String, subtitle: String, tracks: List<Track>, artwork: Track? = tracks.firstOrNull(),
                           actions: (@Composable () -> Unit)? = null, open: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp)
        .clip(RoundedCornerShape(12.dp)).background(colors.surfaceContainerLow), verticalAlignment = Alignment.CenterVertically) {
        Row(Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Button, onClick = open)
            .padding(horizontal = 8.dp, vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Artwork(artwork?.artworkUrl, artwork?.artworkHeaders.orEmpty(), Modifier.size(48.dp))
            Column(Modifier.weight(1f)) {
                Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                Text(listOf(subtitle, localSongCount(tracks.size)).filter(String::isNotBlank).joinToString(" · "),
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            if (actions == null) Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(20.dp), tint = colors.onSurfaceVariant)
        }
        actions?.invoke()
    }
}

@Composable
internal fun LocalCollectionHero(title: String, artist: String, tracks: List<Track>,
                                artwork: Track? = tracks.firstOrNull { it.artworkUrl != null }) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
        .clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Artwork(artwork?.artworkUrl, artwork?.artworkHeaders.orEmpty(), Modifier.size(88.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (artist.isNotBlank()) Text(artist, style = MaterialTheme.typography.bodyMedium,
                maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(localSongCount(tracks.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun LocalPlaybackButtons(tracks: List<Track>, play: (List<Track>, Boolean) -> Unit) {
    val enabled = tracks.any { !it.isUnavailable }
    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { play(tracks, false) }, enabled = enabled) {
            Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
            Text(mb("播放全部", "Play All"), Modifier.padding(start = 6.dp))
        }
        TextButton(onClick = { play(tracks, true) }, enabled = enabled) {
            Icon(painterResource(R.drawable.ic_shuffle), null, Modifier.size(18.dp))
            Text(mb("随机播放", "Shuffle Play"), Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun localSongCount(count: Int): String = mb("$count 首", if (count == 1) "1 song" else "$count songs")
