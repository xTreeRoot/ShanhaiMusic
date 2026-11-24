package com.xtreeroot.mb.music

import android.os.SystemClock
import org.json.JSONObject

/** 通用媒体网络反馈，不包含平台格式、地址或账号。 */
internal object MediaNetworkConditions {
    @Volatile var constrained = false
    private var bandwidth: Long? = null
    private var sampledAt = 0L
    private var stalledUntil = 0L
    @Synchronized fun sample(bytes: Long, elapsedMs: Int, bitrate: Long) {
        if (bytes < 64 * 1024 || elapsedMs <= 0 || bitrate <= 0) return
        bandwidth = bandwidth?.let { minOf(bitrate, (it * 0.7 + bitrate * 0.3).toLong()) } ?: bitrate
        sampledAt = SystemClock.elapsedRealtime()
    }
    @Synchronized fun stalled() { stalledUntil = SystemClock.elapsedRealtime() + 30_000 }
    @Synchronized fun snapshot(): JSONObject = JSONObject().put("constrained", constrained)
        .put("recentStall", SystemClock.elapsedRealtime() < stalledUntil).apply {
            bandwidth?.takeIf { SystemClock.elapsedRealtime() - sampledAt < 90_000 }?.let { put("bandwidthBps", it) }
        }
}
