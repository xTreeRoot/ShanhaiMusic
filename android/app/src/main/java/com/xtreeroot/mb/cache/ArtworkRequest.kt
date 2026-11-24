package com.xtreeroot.mb.cache

import coil.request.ImageRequest
import java.util.Locale

/** 同一地址的不同凭据必须使用不同缓存；索引只保存请求头摘要。 */
internal fun artworkCacheKey(address: String, headers: Map<String, String>): String {
    val normalized = headers.entries.sortedBy { it.key.lowercase(Locale.ROOT) }
        .flatMap { listOf(it.key.lowercase(Locale.ROOT), it.value) }
    return DiskResponseCache.key("artwork-v1", address, *normalized.toTypedArray())
}

/** 页面、预取和锁屏封面共享同一份 Coil 磁盘文件。 */
internal fun ImageRequest.Builder.cachedArtwork(address: String, headers: Map<String, String>): ImageRequest.Builder {
    val key = artworkCacheKey(address, headers)
    return data(address).memoryCacheKey(key).diskCacheKey(key).apply {
        headers.forEach { (name, value) -> addHeader(name, value) }
    }
}
