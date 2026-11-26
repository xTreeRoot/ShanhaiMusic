package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.xtreeroot.mb.R
import com.xtreeroot.mb.plugin.InstalledPlugin
import com.xtreeroot.mb.plugin.PluginCommunityPager

@Composable
internal fun PluginCommunityView(model: PluginCommunityPager, installed: List<InstalledPlugin>, busy: Boolean, install: (String) -> Unit) {
    val state by model.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (state.page == 0 && !state.loading) model.search(query) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(query, { query = it }, Modifier.weight(1f), label = { Text(mb("搜索插件", "Search Plugins")) },
                    singleLine = true, keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { model.search(query) }))
                TextButton(onClick = { model.search(query) }) { Text(mb("搜索", "Search")) }
            }
        }
        items(state.plugins, key = { it.id }) { plugin ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(plugin.avatar, null, Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)), contentScale = ContentScale.Crop,
                    error = painterResource(R.drawable.ic_plugin))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(plugin.name, style = MaterialTheme.typography.titleMedium)
                    Text(plugin.description.ifBlank { plugin.repository }, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                    Text("★ ${plugin.stars}", style = MaterialTheme.typography.labelSmall)
                }
                if (installed.any { it.repository.equals(plugin.repository, true) }) Text(mb("已安装", "Installed"), style = MaterialTheme.typography.labelMedium)
                else OutlinedButton(onClick = { install(plugin.repository) }, enabled = !busy) { Text(mb("安装", "Install")) }
            }
        }
        if (state.loading) item { CircularProgressIndicator(Modifier.size(28.dp)) }
        if (state.error != null) item {
            Text(state.error.orEmpty(), style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = model::loadMore) { Text(mb("重试", "Retry")) }
        } else if (state.more && !state.loading && state.page > 0) item {
            TextButton(onClick = model::loadMore) { Text(mb("加载更多", "Load More")) }
        } else if (state.plugins.isEmpty() && !state.loading) item { Text(mb("没有找到插件", "No plugins found")) }
    }
}
