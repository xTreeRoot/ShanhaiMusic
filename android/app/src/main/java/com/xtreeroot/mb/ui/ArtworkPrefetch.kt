package com.xtreeroot.mb.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.xtreeroot.mb.cache.artworkCacheKey
import com.xtreeroot.mb.cache.cachedArtwork
import com.xtreeroot.mb.music.Track
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

internal data class ArtworkReference(val address: String, val headers: Map<String, String> = emptyMap())

internal fun Track.artworkReference(): ArtworkReference? = artworkUrl?.let { ArtworkReference(it, artworkHeaders) }

/** 预取当前已加载分页里的全部封面，四个工作协程限制并发，不追逐未加载的分页。 */
@Composable
internal fun PrefetchArtwork(images: List<ArtworkReference>) {
    val context = LocalContext.current
    LaunchedEffect(images) {
        val pending = images.distinctBy { artworkCacheKey(it.address, it.headers) }
        coroutineScope {
            repeat(minOf(4, pending.size)) { worker ->
                launch {
                    for (index in worker until pending.size step 4) {
                        val image = pending[index]
                        context.imageLoader.execute(ImageRequest.Builder(context)
                            .cachedArtwork(image.address, image.headers).size(256)
                            .memoryCachePolicy(CachePolicy.DISABLED).build())
                    }
                }
            }
        }
    }
}
