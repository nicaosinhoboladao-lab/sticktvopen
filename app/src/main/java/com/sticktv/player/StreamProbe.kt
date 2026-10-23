package com.sticktv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/** Faz uma verificacao rapida da resposta de uma fonte de video. */
object StreamProbe {

    suspend fun isAlive(variant: StreamVariant, timeoutMs: Int = 6_000): Boolean =
        withContext(Dispatchers.IO) {
            runCatching { probe(variant, timeoutMs) }.getOrDefault(false)
        }

    private fun probe(variant: StreamVariant, timeoutMs: Int): Boolean {
        var url = URL(variant.url)
        var redirects = 0
        val isHls = variant.url.contains(".m3u8", ignoreCase = true)

        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = timeoutMs
            conn.readTimeout = timeoutMs
            conn.instanceFollowRedirects = false
            conn.setRequestProperty(
                "User-Agent",
                variant.userAgent?.takeIf { it.isNotBlank() } ?: Config.USER_AGENT
            )
            variant.referer?.takeIf { it.isNotBlank() }?.let {
                conn.setRequestProperty("Referer", it)
            }
            if (!isHls) conn.setRequestProperty("Range", "bytes=0-2047")

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location") ?: return false
                    if (++redirects > 5) return false
                    url = URL(url, location)
                    continue
                }
                if (code !in 200..299) return false

                val buffer = ByteArray(2048)
                val read = conn.inputStream.use { it.read(buffer) }
                if (read <= 0) return false

                val head = String(buffer, 0, read, Charsets.ISO_8859_1)
                val hlsContent = isHls ||
                    conn.contentType?.contains("mpegurl", ignoreCase = true) == true

                // Some valid servers reject probes; the player will still attempt the source.
                return if (hlsContent) {
                    head.trimStart('\uFEFF', ' ', '\r', '\n', '\t').startsWith("#EXTM3U")
                } else {
                    !head.contains("<html", ignoreCase = true)
                }
            } finally {
                conn.disconnect()
            }
        }
    }
}

/** Keeps a short history of sources that worked or failed. */
class StreamHealth(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("stream_health", Context.MODE_PRIVATE)
    private val failedAt = ConcurrentHashMap<String, Long>()

    fun markFailed(url: String) {
        failedAt[url] = System.currentTimeMillis()
    }

    fun isRecentlyFailed(url: String): Boolean =
        failedAt[url]?.let { System.currentTimeMillis() - it < FAIL_TTL_MS } == true

    fun preferredUrl(channel: Channel): String? =
        prefs.getString(key(channel), null)

    fun markWorking(channel: Channel, url: String) {
        failedAt.remove(url)
        val k = key(channel)
        if (prefs.getString(k, null) != url) {
            prefs.edit().putString(k, url).apply()
        }
    }

    private fun key(channel: Channel) =
        "pref_" + (ChannelMerger.identityKey(channel) ?: channel.url)

    private companion object {
        const val FAIL_TTL_MS = 10 * 60 * 1000L
    }
}

/** Selects another source for the same channel after playback fails. */
class ChannelFailover(
    private val channel: Channel,
    private val health: StreamHealth
) {
    private val candidates: List<StreamVariant>
    private val tried = HashSet<String>()

    var current: StreamVariant
        private set

    val total: Int get() = candidates.size
    val position: Int get() = candidates.indexOfFirst { it.url == current.url } + 1

    init {
        val all = (listOf(StreamVariant(channel.url, channel.userAgent, channel.referer)) +
            channel.alternatives)
            .filter { it.url.isNotBlank() }
            .distinctBy { it.url.trim() }
        val available = all.ifEmpty {
            listOf(StreamVariant(channel.url, channel.userAgent, channel.referer))
        }
        val preferred = health.preferredUrl(channel)

        // Prefer the last working source and sources without recent failures.
        candidates = available.sortedWith(
            compareBy<StreamVariant>(
                { it.url != preferred },
                { health.isRecentlyFailed(it.url) }
            )
        )
        current = candidates.first()
        tried += current.url
    }

    fun markWorking() = health.markWorking(channel, current.url)

    /**
     * Prefer sources that respond, but do not discard the others:
     * some servers block the probe while still playing in the player.
     */
    suspend fun next(): StreamVariant? {
        health.markFailed(current.url)
        val remaining = candidates.filter { it.url !in tried }
        if (remaining.isEmpty()) return null

        val batch = remaining.take(3)
        val alive = coroutineScope {
            batch.map { async { StreamProbe.isAlive(it) } }.awaitAll()
        }
        val aliveIndex = alive.indexOfFirst { it }
        val next = if (aliveIndex >= 0) batch[aliveIndex] else batch.first()

        // An inconclusive probe does not remove a source; the player will still try it.
        tried += next.url
        current = next
        return current
    }
}