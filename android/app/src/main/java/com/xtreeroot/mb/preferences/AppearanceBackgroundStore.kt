package com.xtreeroot.mb.preferences

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
import android.util.AtomicFile
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import coil.size.Scale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal const val appearanceBackgroundByteLimit = 12L * 1024 * 1024

internal class AppearanceBackgroundStore(private val context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "appearance-background.png"))
    private val files = Mutex()
    private val revision = MutableStateFlow(0L)
    private val restoreCount = MutableStateFlow(0L)
    val changes = revision.asStateFlow()
    val restores = restoreCount.asStateFlow()

    suspend fun load(): Bitmap? = withContext(Dispatchers.IO) {
        files.withLock {
            val input = try { file.openRead() } catch (_: FileNotFoundException) { return@withLock null }
            input.use { BitmapFactory.decodeStream(it) ?: throw IOException("Invalid appearance background") }
        }
    }

    suspend fun prepare(uri: Uri): Bitmap = withContext(Dispatchers.IO) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT)
        decode(uri)
    }

    suspend fun prepareRemote(address: String): Bitmap = withContext(Dispatchers.IO) {
        val url = address.trim().toHttpUrlOrNull() ?: throw IOException("Invalid image URL")
        require(url.username.isEmpty() && url.password.isEmpty())
        val client = (context.applicationContext as com.xtreeroot.mb.MBApplication).http.newBuilder()
            .callTimeout(30, java.util.concurrent.TimeUnit.SECONDS).build()
        val bytes = client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Image request failed")
            val body = response.body ?: throw IOException("Empty image")
            require(body.contentLength() <= appearanceBackgroundByteLimit)
            body.byteStream().use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= appearanceBackgroundByteLimit)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
        }
        decode(bytes)
    }

    private suspend fun decode(data: Any): Bitmap {
        val request = ImageRequest.Builder(context).data(data).size(maxDimension, maxDimension)
            .scale(Scale.FIT).precision(Precision.INEXACT).allowHardware(false)
            .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.DISABLED)
            .networkCachePolicy(CachePolicy.DISABLED).build()
        val result = context.imageLoader.execute(request) as? SuccessResult
        val decoded = (result?.drawable as? BitmapDrawable)?.bitmap ?: throw IOException("Invalid image")
        val longestSide = maxOf(decoded.width, decoded.height)
        return if (longestSide <= maxDimension) decoded else Bitmap.createScaledBitmap(decoded,
            (decoded.width.toLong() * maxDimension / longestSide).toInt().coerceAtLeast(1),
            (decoded.height.toLong() * maxDimension / longestSide).toInt().coerceAtLeast(1), true)
    }

    suspend fun save(bitmap: Bitmap): Bitmap = withContext(Dispatchers.IO) {
        require(bitmap.width in 1..maxDimension && bitmap.height in 1..maxDimension)
        files.withLock {
            write { if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Cannot encode image") }
        }
        bitmap
    }

    suspend fun replace(uri: Uri): Bitmap = save(prepare(uri))

    suspend fun <Value> snapshot(read: (File, Long) -> Value): Value? = files.withLock {
        if (!exists()) null else read(file.baseFile, revision.value)
    }

    suspend fun restoreIfMissing(source: File) = withContext(Dispatchers.IO) {
        files.withLock {
            if (exists()) return@withLock
            require(source.length() in 1..appearanceBackgroundByteLimit)
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outMimeType == "image/png" && bounds.outWidth in 1..maxDimension && bounds.outHeight in 1..maxDimension)
            val decoded = BitmapFactory.decodeFile(source.path) ?: throw IOException("Invalid appearance background")
            decoded.recycle()
            write { output -> source.inputStream().use { it.copyTo(output) } }
            restoreCount.value += 1
        }
    }

    private fun exists(): Boolean = try {
        file.openRead().use { true }
    } catch (_: FileNotFoundException) { false }

    private fun write(action: (OutputStream) -> Unit) {
        val output = file.startWrite()
        try {
            action(output)
            file.finishWrite(output)
            revision.value += 1
        } catch (failure: Exception) {
            file.failWrite(output)
            throw failure
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        files.withLock {
            file.delete()
            if (file.baseFile.exists()) throw IOException("Cannot remove appearance background")
            revision.value += 1
        }
    }

    private companion object { const val maxDimension = 1600 }
}
