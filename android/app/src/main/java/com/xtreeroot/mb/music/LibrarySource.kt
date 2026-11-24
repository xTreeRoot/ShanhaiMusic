package com.xtreeroot.mb.music

import java.io.Serializable

sealed interface LibraryRoute : Serializable {
    data object Favorites : LibraryRoute
    data object Liked : LibraryRoute
    data object History : LibraryRoute
    data object Following : LibraryRoute
    data class Folder(val id: String) : LibraryRoute
    data class Creator(val id: String) : LibraryRoute
}

data class LibraryLink(val id: String, val title: String, val subtitle: String, val imageUrl: String?, val route: LibraryRoute)

data class LibraryPage(
    val title: String? = null,
    val tracks: List<Track> = emptyList(),
    val links: List<LibraryLink> = emptyList(),
    val nextCursor: String? = null,
)

interface LibrarySource : MusicSource {
    suspend fun library(route: LibraryRoute, cursor: String?): LibraryPage
    suspend fun invalidateLibraryCache() {}
}
