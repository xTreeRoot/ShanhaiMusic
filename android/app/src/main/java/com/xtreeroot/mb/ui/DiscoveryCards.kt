package com.xtreeroot.mb.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import com.xtreeroot.mb.music.Track
import com.xtreeroot.mb.plugin.PluginCatalogRequest
import com.xtreeroot.mb.plugin.PluginCatalogSection
import com.xtreeroot.mb.plugin.PluginImage

@Composable
internal fun MusicCoverCard(track: Track, play: () -> Unit) {
    val mark = LocalMarkedTrack.current?.takeIf { it.id == track.id }?.playback
    Column(Modifier.width(156.dp).trackRowInput(track, enabled = true, mark, highlighted = mark != null, play = play),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            TrackRowArtwork(track, mark, Modifier.fillMaxWidth().aspectRatio(1f))
            TrackActionsButton(track, Modifier.align(Alignment.TopEnd), onArtwork = true)
        }
        Text(track.title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        Text(track.artist, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PluginDiscoverySection(section: PluginCatalogSection, open: (PluginCatalogRequest) -> Unit,
                                    play: (List<Track>, Track) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DiscoverySectionHeading(section.title, open = section.more?.let { more -> { open(more.destination) } })
        val count = section.tracks.size + section.entities.size + section.links.size
        val rows = minOf(2, maxOf(1, count))
        val rowHeight = 246.dp * LocalDensity.current.fontScale.coerceAtLeast(1f)
        LazyHorizontalGrid(rows = GridCells.Fixed(rows), modifier = Modifier.height(rowHeight * rows + 16.dp * (rows - 1)),
            contentPadding = PaddingValues(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            items(section.tracks, key = { "track-${it.id}" }) { track ->
                MusicCoverCard(track) { play(section.tracks, track) }
            }
            items(section.entities, key = { "entity-${it.kind}-${it.id}" }) { entity ->
                CatalogCoverCard(entity.title, entity.subtitle, entity.image) { open(PluginCatalogRequest.Entity(entity)) }
            }
            items(section.links, key = { "link-${it.destination}" }) { link ->
                CatalogCoverCard(link.title, link.subtitle, link.image) { open(link.destination) }
            }
        }
        if (section.tracks.isEmpty() && section.entities.isEmpty() && section.links.isEmpty() && section.empty.isNotBlank()) {
            EmptyMessage(section.empty)
        }
    }
}

@Composable
private fun CatalogCoverCard(title: String, subtitle: String, image: PluginImage?, open: () -> Unit) {
    Column(Modifier.width(156.dp).clickable(onClick = open), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Artwork(image?.url, image?.headers.orEmpty(), Modifier.fillMaxWidth().aspectRatio(1f))
        Text(title, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold)
        if (subtitle.isNotBlank()) Text(subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
