package com.xtreeroot.mb.playback

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import com.google.common.util.concurrent.SettableFuture
import com.xtreeroot.mb.MBApplication
import com.xtreeroot.mb.cache.cachedArtwork
import com.xtreeroot.mb.plugin.PluginSource
import java.net.URI
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
internal class SessionArtworkLoader(private val app: MBApplication, private val scope: CoroutineScope) :
    BitmapLoader by CacheBitmapLoader(DataSourceBitmapLoader(app)) {
    private data class Cover(val uri: Uri, val trackId: String?, val headers: Map<String, String>, val plugin: PluginSource?)
    private data class Load(val cover: Cover, val job: Job, val result: ListenableFuture<Bitmap>)

    private var current: Load? = null
    private val noCover = SettableFuture.create<Bitmap>().apply { set(null) }

    override fun loadBitmapFromMetadata(metadata: MediaMetadata): ListenableFuture<Bitmap> {
        val address = metadata.artworkUri
        val bytes = metadata.artworkData
        if (bytes != null || address == null) {
            current?.job?.cancel()
            current = null
            return bytes?.let(::decodeBitmap) ?: noCover
        }
        val track = metadata.track()
        val plugin = track?.let { app.playbackSource(it.sourceId) as? PluginSource }
        val cover = Cover(address, track?.id, track?.artworkHeaders.orEmpty(), plugin)
        current?.takeIf { it.cover == cover && !it.result.isCancelled }?.let { return it.result }
        current?.job?.cancel()
        val result = SettableFuture.create<Bitmap>()
        val job = scope.launch {
            try {
                val headers = plugin?.artworkHeaders(URI(address.toString())) ?: cover.headers
                val request = ImageRequest.Builder(app).cachedArtwork(address.toString(), headers)
                    .size(384, 384).scale(Scale.FIT).precision(Precision.EXACT).allowHardware(false).build()
                val image = app.imageLoader.execute(request) as? SuccessResult
                result.set((image?.drawable as? BitmapDrawable)?.bitmap)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { result.set(null) }
        }
        job.invokeOnCompletion { result.set(null) }
        result.addListener({ if (result.isCancelled) job.cancel() }, MoreExecutors.directExecutor())
        current = Load(cover, job, result)
        return result
    }
}
