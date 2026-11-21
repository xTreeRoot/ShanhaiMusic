package com.xtreeroot.mb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import com.xtreeroot.mb.ui.components.MBAlertDialog as AlertDialog
import com.xtreeroot.mb.ui.components.MBCheckbox as Checkbox
import com.xtreeroot.mb.ui.components.MBDropdownMenu as DropdownMenu
import com.xtreeroot.mb.ui.components.MBMenuItem as DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import com.xtreeroot.mb.ui.components.MBFilterChip as FilterChip
import androidx.compose.material3.Icon
import com.xtreeroot.mb.ui.components.MBIconButton as IconButton
import com.xtreeroot.mb.ui.components.MBListItem as ListItem
import androidx.compose.material3.MaterialTheme
import com.xtreeroot.mb.ui.components.MBTextField as OutlinedTextField
import androidx.compose.material3.Text
import com.xtreeroot.mb.ui.components.MBTextButton as TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogLink
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCollection
import com.xtreeroot.mb.plugin.PluginEntity
import com.xtreeroot.mb.plugin.PluginEntityKind
import com.xtreeroot.mb.plugin.PluginSearchKind
import com.xtreeroot.mb.plugin.PluginSource
import com.xtreeroot.mb.playback.PlaybackStation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

@Composable
internal fun PluginCatalogBrowser(source: PluginSource, destination: CatalogDestination, navigator: PluginCatalogNavigator,
                                 play: (List<Track>, Track) -> Unit,
                                 playStation: (List<Track>, Track, PlaybackStation) -> Unit) {
    key(destination) {
        when (destination) {
            is CatalogDestination.Content -> PluginCatalogContent(source, destination, navigator, play, playStation)
            is CatalogDestination.Search -> PluginTypedSearch(source, destination, navigator, play)
        }
    }
}

@Composable
internal fun catalogTitle(destination: CatalogDestination, source: PluginSource): String = when (destination) {
    is CatalogDestination.Search -> mb("搜索音源", "Search Source")
    is CatalogDestination.Content -> {
        val status by destination.pager.state.collectAsStateWithLifecycle()
        when (val request = destination.request) {
            PluginCatalogRequest.Home -> LocalSourceNames.current[source.id] ?: source.manifest.name
            is PluginCatalogRequest.Browse -> status.page?.title?.takeIf(String::isNotBlank)
                ?: request.title.ifBlank { mb("更多", "More") }
            is PluginCatalogRequest.Collection -> request.title.ifBlank { request.collection.label() }
            is PluginCatalogRequest.Entity -> status.page?.title ?: request.entity.title
            is PluginCatalogRequest.Search -> request.query
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PluginCatalogContent(source: PluginSource, destination: CatalogDestination.Content, navigator: PluginCatalogNavigator,
                                 play: (List<Track>, Track) -> Unit,
                                 playStation: ((List<Track>, Track, PlaybackStation) -> Unit)? = null) {
    val request = destination.request
    val pager = destination.pager
    val status by pager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val entity = (request as? PluginCatalogRequest.Entity)?.entity
    val catalog = source.catalog
    var links by remember { mutableStateOf(emptyList<PluginCatalogLink>()) }
    var capabilities by remember { mutableStateOf(emptySet<String>()) }
    val saved = destination.savedOverride ?: (entity?.saved == true)
    var edit by remember { mutableStateOf<PlaylistDialog?>(null) }
    var changing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }
    suspend fun load(append: Boolean = false) = pager.load(append) { catalog.load(request, it) }
    LaunchedEffect(source, request, status.needsReload) {
        if (status.needsReload) load()
    }
    LaunchedEffect(source, request) {
        try {
            if (request == PluginCatalogRequest.Home) links = catalog.navigation()
            val methods = buildSet {
                if (request == PluginCatalogRequest.Home) addAll(listOf("search", "searchVideos"))
                if ((request as? PluginCatalogRequest.Collection)?.collection == PluginCollection.Playlists) add("createPlaylist")
                entity?.saveMethod?.let(::add)
                if (entity?.editable == true) addAll(listOf("renamePlaylist", "deletePlaylist", "addToPlaylist", "removeFromPlaylist", "movePlaylistTrack"))
            }
            capabilities = methods.filter { catalog.supports(it) }.toSet()
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { if (status.page != null) actionError = error.message }
    }
    fun change(action: suspend () -> Unit) {
        if (changing) return
        changing = true
        actionError = null
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { actionError = error.message ?: "Plugin action failed" }
            finally { changing = false }
        }
    }
    val page = status.page
    PrefetchArtwork(page?.sections.orEmpty().flatMap { section ->
        section.tracks.mapNotNull { it.artworkReference() } +
            (section.entities.mapNotNull { it.image } + section.links.mapNotNull { it.image }).map { ArtworkReference(it.url, it.headers) }
    })
    val tracks = page?.sections?.flatMap { it.tracks }.orEmpty()
    val station = entity?.takeIf { it.kind == PluginEntityKind.Station }?.let { PlaybackStation(source.id, it.id, it.title) }
    val select: (List<Track>, Track) -> Unit = if (station != null && playStation != null) {
        { batch, track -> playStation(batch, track, station) }
    } else play
    val videoMode = LocalVideoBrowse.current
    val saveMethod = entity?.saveMethod
    val content: LazyGridScope.() -> Unit = {
        if (page != null) {
            if (request != PluginCatalogRequest.Home && (page.subtitle.isNotBlank() || page.description.isNotBlank())) item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    if (page.subtitle.isNotBlank()) Text(page.subtitle, style = MaterialTheme.typography.titleMedium)
                    if (page.description.isNotBlank()) Text(page.description, style = MaterialTheme.typography.bodyMedium)
                }
            }
            val firstPlayable = tracks.firstOrNull { !it.isUnavailable }
            if (entity != null && firstPlayable != null) item(span = { GridItemSpan(maxLineSpan) }) {
                TextButton(onClick = { select(tracks, firstPlayable) }) { Text(mb("播放全部", "Play All")) }
            }
            var trackIndex = 0
            page.sections.forEach { section ->
                if (request == PluginCatalogRequest.Home) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        val more = section.more ?: links.firstOrNull { it.title.isNotBlank() && it.title == section.title }
                        PluginDiscoverySection(section.copy(more = more), navigator::open, select)
                    }
                    return@forEach
                }
                if (section.title.isNotBlank()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(section.title, Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleMedium)
                }
                section.tracks.forEach { track ->
                    val index = trackIndex++
                    val playSection = { select(if (entity != null) tracks else section.tracks, track) }
                    if (section.videos || videoMode && entity?.editable != true) item { VideoCoverCard(track, playSection) }
                    else item(span = { GridItemSpan(maxLineSpan) }) {
                        TrackRow(track, actions = {
                            if (entity?.editable == true && ("removeFromPlaylist" in capabilities || "movePlaylistTrack" in capabilities && index > 0)) {
                                PluginPlaylistTrackMenu(track, changing,
                                    remove = if ("removeFromPlaylist" in capabilities) ({
                                        change { catalog.changePlaylistTracks(entity, listOf(track.sourceTrackId), add = false); load() }
                                    }) else null,
                                    move = if ("movePlaylistTrack" in capabilities && index > 0) ({
                                        change { catalog.movePlaylistTrack(entity, index, index - 1); load() }
                                    }) else null)
                            } else TrackActionsButton(track)
                        }, play = playSection)
                    }
                }
                section.entities.forEach { item -> item(span = { GridItemSpan(maxLineSpan) }) {
                    ListItem(modifier = Modifier.clickable { navigator.open(PluginCatalogRequest.Entity(item)) },
                        headlineContent = { Text(item.title) }, supportingContent = item.subtitle.takeIf(String::isNotBlank)?.let { subtitle ->
                            { Text(subtitle, maxLines = 2, overflow = TextOverflow.Ellipsis) }
                        }, leadingContent = { Artwork(item.image?.url, item.image?.headers.orEmpty(), Modifier.size(48.dp)) })
                } }
                section.links.forEach { link -> item(span = { GridItemSpan(maxLineSpan) }) { CatalogLinkRow(link, navigator::open) } }
                section.more?.let { link -> item(span = { GridItemSpan(maxLineSpan) }) { CatalogLinkRow(link, navigator::open) } }
                if (section.empty.isNotBlank() && section.tracks.isEmpty() && section.entities.isEmpty() && section.links.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(section.empty) }
                }
            }
        }
        if (entity != null && saveMethod in capabilities) item(span = { GridItemSpan(maxLineSpan) }) {
            TextButton(enabled = !changing, onClick = { change { catalog.setSaved(entity, !saved); destination.savedOverride = !saved } }) {
                Text(entity.kind.saveLabel(saved))
            }
        }
        if ("createPlaylist" in capabilities || "renamePlaylist" in capabilities || "deletePlaylist" in capabilities || "addToPlaylist" in capabilities) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.padding(horizontal = 12.dp)) {
                    if ("createPlaylist" in capabilities) TextButton(enabled = !changing, onClick = { edit = PlaylistDialog.Name }) {
                        Text(mb("新建平台歌单", "Create Source Playlist"))
                    }
                    if ("addToPlaylist" in capabilities) TextButton(enabled = !changing, onClick = { edit = PlaylistDialog.Add }) {
                        Text(mb("添加平台歌曲", "Add Songs"))
                    }
                    if ("renamePlaylist" in capabilities) TextButton(enabled = !changing, onClick = { edit = PlaylistDialog.Name }) {
                        Text(mb("重命名歌单", "Rename Playlist"))
                    }
                    if ("deletePlaylist" in capabilities) TextButton(enabled = !changing, onClick = { edit = PlaylistDialog.Delete }) {
                        Text(mb("删除平台歌单", "Delete Source Playlist"), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        if (request == PluginCatalogRequest.Home && page != null) links.filter { link ->
            page.sections.none { section -> section.more?.destination == link.destination || link.title.isNotBlank() && section.title == link.title }
        }.forEach { link -> item(span = { GridItemSpan(maxLineSpan) }) { PluginNavigationShelf(source, link, navigator::open, select) } }
        if (status.busy && (page == null || status.appending)) item(span = { GridItemSpan(maxLineSpan) }) { Loading() }
        status.error?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) { Retry(error) { scope.launch { load(status.appendRetry) } } } }
        actionError?.let { error -> item(span = { GridItemSpan(maxLineSpan) }) {
            Text(error, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.error)
        } }
        if (!status.busy && status.error == null && page?.nextOffset != null) item(span = { GridItemSpan(maxLineSpan) }) {
            LaunchedEffect(page.nextOffset, page.sections.size) { if (!changing) load(append = true) }
            Loading()
        }
        if (!status.busy && status.error == null && page != null && page.sections.none { it.hasContent } && links.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { EmptyMessage(mb("暂无内容", "No content")) }
        }
    }
    PullToRefreshBox(isRefreshing = status.busy && !status.appending && page != null,
        onRefresh = { if (!changing) scope.launch { catalog.invalidate(); pager.invalidate() } }, modifier = Modifier.fillMaxSize()) {
        LazyVerticalGrid(columns = GridCells.Adaptive(155.dp), modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 12.dp), content = content)
    }
    when (edit) {
        PlaylistDialog.Name -> PlaylistNameDialog(mb("歌单名称", "Playlist Name"), entity?.let { page?.title ?: it.title }.orEmpty(),
            close = { edit = null }, save = { name -> edit = null; change { catalog.namePlaylist(entity, name); load() } })
        PlaylistDialog.Delete -> if (entity != null) AlertDialog(onDismissRequest = { edit = null },
            title = { Text(mb("删除平台歌单？", "Delete this source playlist?")) },
            confirmButton = { TextButton(onClick = { edit = null; change { catalog.deletePlaylist(entity); navigator.back(refresh = true) } }) {
                Text(mb("删除", "Delete"), color = MaterialTheme.colorScheme.error)
            } }, dismissButton = { TextButton(onClick = { edit = null }) { Text(mb("取消", "Cancel")) } })
        PlaylistDialog.Add -> if (entity != null) PluginPlaylistAddDialog(source, entity, tracks.map(Track::sourceTrackId).toSet(),
            close = { edit = null }, added = { edit = null; scope.launch { load() } })
        null -> Unit
    }
}

@Composable
internal fun PluginPersonalContent(source: PluginSource, navigator: PluginCatalogNavigator, model: AccountModel, state: AccountState,
                                   openSettings: () -> Unit = {}, openPlugins: () -> Unit = {}, openService: () -> Unit = {}) {
    val account = state.account
    var signOut by remember(source) { mutableStateOf(false) }
    var collections by remember(source) { mutableStateOf(emptyList<PluginCollection>()) }
    var error by remember(source) { mutableStateOf<String?>(null) }
    var loading by remember(source) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun refresh() {
        if (loading) return
        loading = true
        error = null
        try {
            collections = PluginCollection.entries.filter { source.catalog.supports(it.method) }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load source account" }
        finally { loading = false }
    }
    LaunchedEffect(source) { refresh() }
    val refreshing = (state.loading && !state.signingIn || loading) && (account != null || collections.isNotEmpty())
    RefreshableList(refreshing = refreshing, refresh = {
        if (!state.loading && !loading) {
            model.refresh()
            scope.launch { source.catalog.invalidate(); refresh() }
        }
    }, contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            AccountHeader(LocalSourceNames.current[source.id] ?: source.manifest.name, account, state.loading,
                mb("登录音源", "Sign In to Source").takeIf { state.canSignIn }, model::signIn)
        }
        val profile = account?.page?.takeIf(String::isNotBlank)
        if (profile != null || collections.isNotEmpty()) item {
            val destinations = buildList {
                if (profile != null) {
                    val title = mb("我的主页", "My Profile")
                    add(AccountDestination(title, Icons.Default.Person) { navigator.open(PluginCatalogRequest.Browse(profile, title)) })
                }
                collections.forEach { collection ->
                    val title = collection.label()
                    val icon = when (collection) {
                        PluginCollection.Liked -> Icons.Default.Favorite
                        PluginCollection.Recent, PluginCollection.Shows, PluginCollection.Stations -> Icons.Default.PlayArrow
                        else -> Icons.AutoMirrored.Filled.List
                    }
                    add(AccountDestination(title, icon) { navigator.open(PluginCatalogRequest.Collection(collection, title)) })
                }
            }
            AccountDestinations(destinations)
        }
        item { AccountTools(openSettings, openPlugins, openService) }
        if (account != null && state.canSignOut) item { AccountSignOutRow(state.loading) { signOut = true } }
        if (!refreshing && (loading || state.loading && !state.signingIn)) item { Loading() }
        state.error?.takeUnless { state.signingIn }?.let { message -> item { Retry(message, model::refresh) } }
        error?.let { message -> item { Retry(message) { scope.launch { refresh() } } } }

    }
    if (signOut) AccountSignOutDialog({ signOut = false }, model::signOut)
}

@Composable
private fun PluginTypedSearch(source: PluginSource, destination: CatalogDestination.Search, navigator: PluginCatalogNavigator,
                              play: (List<Track>, Track) -> Unit) {
    var kinds by remember { mutableStateOf(emptyList<PluginSearchKind>()) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(source) {
        try {
            val methods = PluginSearchKind.entries.map { it.method }.distinct().filter { source.catalog.supports(it) }.toSet()
            kinds = PluginSearchKind.entries.filter { it.method in methods }
            if (destination.kind !in kinds) kinds.firstOrNull()?.let(destination::select)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: Exception) { error = failure.message ?: "Could not load search types" }
    }
    Column(Modifier.fillMaxSize()) {
        if (kinds.isEmpty()) {
            EmptyMessage(error ?: mb("没有可用的搜索类型", "No search types available"))
            return@Column
        }
        CatalogSearchField(destination.input, { destination.input = it }, destination::submit)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(kinds) { kind ->
                FilterChip(destination.kind == kind, { destination.select(kind) }, label = { Text(kind.label()) })
            }
        }
        val result = destination.results
        if (result == null) EmptyMessage(mb("搜索音源", "Search Source"))
        else key(result) { PluginCatalogContent(source, result, navigator, play) }
    }
}

@Composable
private fun PluginPlaylistAddDialog(source: PluginSource, playlist: PluginEntity, existing: Set<String>, close: () -> Unit, added: () -> Unit) {
    var input by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val pager = remember(query) { PluginCatalogPager() }
    val status by pager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    suspend fun load(append: Boolean = false) = pager.load(append) {
        source.catalog.load(PluginCatalogRequest.Search(query, PluginSearchKind.Tracks), it)
    }
    LaunchedEffect(query) { if (query.isNotEmpty()) load() }
    AlertDialog(onDismissRequest = { if (!saving) close() }, title = { Text(mb("添加平台歌曲", "Add Songs")) },
        text = {
            Column {
                CatalogSearchField(input, { input = it }) { query = input.trim(); selected = emptySet(); error = null }
                val tracks = status.page?.sections?.flatMap { it.tracks }.orEmpty().distinctBy(Track::id)
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(tracks, key = Track::id) { track ->
                        val id = track.sourceTrackId
                        val enabled = !saving && !track.isUnavailable && id !in existing
                        TrackRow(track, enabled, actions = { Checkbox(id in selected || id in existing, null, enabled = enabled) }, picker = true) {
                            selected = if (id in selected) selected - id else selected + id
                        }
                    }
                    if (status.busy) item { Loading() }
                    status.error?.let { message -> item { Retry(message) { scope.launch { load(status.appendRetry) } } } }
                    if (!status.busy && status.error == null && status.page?.nextOffset != null) item {
                        TextButton(onClick = { scope.launch { load(append = true) } }) { Text(mb("加载更多", "Load More")) }
                    }
                    if (status.page != null && tracks.isEmpty() && status.error == null) item { EmptyMessage(mb("暂无内容", "No content")) }
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = {
            TextButton(enabled = !saving && selected.isNotEmpty(), onClick = {
                saving = true
                error = null
                scope.launch {
                    try { source.catalog.changePlaylistTracks(playlist, selected.sorted(), add = true); added() }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { error = failure.message ?: "Could not add songs" }
                    finally { saving = false }
                }
            }) { Text(mb("添加 ${selected.size} 首", "Add ${selected.size}")) }
        }, dismissButton = { TextButton(enabled = !saving, onClick = close) { Text(mb("取消", "Cancel")) } })
}

@Composable
private fun CatalogSearchField(input: String, edit: (String) -> Unit, submit: () -> Unit) {
    val focus = LocalFocusManager.current
    val search = { focus.clearFocus(); submit() }
    OutlinedTextField(input, edit, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), singleLine = true,
        label = { Text(mb("搜索音源", "Search Source")) }, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }), trailingIcon = {
            IconButton(onClick = search, enabled = input.isNotBlank()) { Icon(Icons.Default.Search, mb("搜索", "Search")) }
        })
}

@Composable
private fun PluginSearchKind.label(): String = when (this) {
    PluginSearchKind.Tracks -> mb("歌曲", "Tracks")
    PluginSearchKind.Albums -> mb("专辑", "Albums")
    PluginSearchKind.Artists -> mb("作者", "Artists")
    PluginSearchKind.Playlists -> mb("歌单", "Playlists")
    PluginSearchKind.Shows -> mb("播客", "Podcasts")
    PluginSearchKind.Videos -> mb("视频", "Videos")
}

private enum class PlaylistDialog { Name, Delete, Add }

@Composable
private fun CatalogLinkRow(link: PluginCatalogLink, open: (PluginCatalogRequest) -> Unit) {
    val title = link.title.ifBlank { (link.destination as? PluginCatalogRequest.Collection)?.collection?.label() ?: mb("查看更多", "See More") }
    ListItem(modifier = Modifier.clickable { open(link.destination) }, headlineContent = { Text(title) },
        leadingContent = link.image?.let { image -> { Artwork(image.url, image.headers, Modifier.size(48.dp)) } },
        supportingContent = link.subtitle.takeIf(String::isNotBlank)?.let { subtitle -> { Text(subtitle) } })
}

@Composable
private fun PluginPlaylistTrackMenu(track: Track, busy: Boolean, remove: (() -> Unit)?, move: (() -> Unit)?) {
    val openActions = LocalTrackActions.current
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }, enabled = !busy) { Icon(Icons.Default.MoreVert, mb("操作 ${track.title}", "Actions for ${track.title}")) }
        DropdownMenu(expanded, { expanded = false }) {
            if (move != null) DropdownMenuItem(text = { Text(mb("向上移动", "Move Up")) }, onClick = { expanded = false; move() })
            if (remove != null) DropdownMenuItem(text = { Text(mb("从歌单移除", "Remove from Playlist")) }, onClick = { expanded = false; remove() })
            DropdownMenuItem(text = { Text(mb("更多操作", "More Actions")) }, onClick = { expanded = false; openActions(track) })
        }
    }
}

@Composable
internal fun PluginCollection.label(): String = when (this) {
    PluginCollection.Playlists -> mb("平台歌单", "Source Playlists")
    PluginCollection.Liked -> mb("喜欢的歌曲", "Liked Songs")
    PluginCollection.Recent -> mb("最近播放", "Recently Played")
    PluginCollection.SavedAlbums -> mb("收藏专辑", "Saved Albums")
    PluginCollection.Shows -> mb("订阅播客", "Subscribed Podcasts")
    PluginCollection.Stations -> mb("电台", "Stations")
}

@Composable
private fun PluginEntityKind.saveLabel(saved: Boolean): String = when (this) {
    PluginEntityKind.Album -> if (saved) mb("取消收藏专辑", "Unsave Album") else mb("收藏专辑", "Save Album")
    PluginEntityKind.Artist -> if (saved) mb("取消关注作者", "Unfollow Artist") else mb("关注作者", "Follow Artist")
    PluginEntityKind.Playlist -> if (saved) mb("取消收藏歌单", "Unsave Playlist") else mb("收藏歌单", "Save Playlist")
    PluginEntityKind.Show -> if (saved) mb("取消订阅", "Unsubscribe") else mb("订阅播客", "Subscribe")
    PluginEntityKind.Station -> ""
}
