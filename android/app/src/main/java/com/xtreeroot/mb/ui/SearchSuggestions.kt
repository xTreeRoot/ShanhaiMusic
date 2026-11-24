package com.xtreeroot.mb.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import com.xtreeroot.mb.ui.components.MBFilterChip as FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.xtreeroot.mb.music.SuggestionSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal suspend fun searchSuggestions(source: SuggestionSource, query: String): List<String> {
    if (query.isBlank()) return emptyList()
    delay(250)
    return try {
        val values = source.suggestions(query)
        currentCoroutineContext().ensureActive()
        values.filter(String::isNotBlank).distinct().sorted()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        emptyList()
    }
}

@Composable
internal fun SearchSuggestions(source: SuggestionSource?, query: String, focused: Boolean, select: (String) -> Unit) {
    if (!focused || source == null || query.isBlank()) return
    var values by remember(source, query) { mutableStateOf(emptyList<String>()) }
    LaunchedEffect(source, query) { values = searchSuggestions(source, query) }
    if (values.isNotEmpty()) LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(values, key = { it }) { value -> FilterChip(false, { select(value) }, label = { Text(value) }) }
    }
}
