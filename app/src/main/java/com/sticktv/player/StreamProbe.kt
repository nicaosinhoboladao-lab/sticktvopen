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

/** Teste rÃ¡pido para saber se uma URL de stream estÃ¡ respondendo. */
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

/** MemÃ³ria de quais fontes funcionam ou falham. */
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

/** Controla a troca de fontes de UM canal. */
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
            .distinctBy { it.url.trim() }

        val preferred = health.preferredUrl(channel)

        // Ordem: fonte que funcionou da Ãºltima vez, depois as que nÃ£o falharam
        // recentemente, mantendo a ordem original das listas.
        candidates = all.sortedWith(
            compareBy<StreamVariant>(
                { it.url != preferred },
                { health.isRecentlyFailed(it.url) }
            )
        )
        current = candidates.first()
        tried += current.url
    }

    fun markWorking() = health.markWorking(channel, current.url)

    /** Marca a fonte atual como quebrada e devolve a prÃ³xima que responder, ou null. */
    suspend fun next(): StreamVariant? {
        health.markFailed(current.url)

        val remaining = candidates.filter { it.url !in tried }
        for (batch in remaining.chunked(3)) {
            val alive = coroutineScope {
                batch.map { async { StreamProbe.isAlive(it) } }.awaitAll()
            }

            batch.forEachIndexed { i, variant ->
                tried += variant.url
                if (!alive[i]) health.markFailed(variant.url)
            }

            val index = alive.indexOfFirst { it }
            if (index >= 0) {
                current = batch[index]
                return current
            }
        }
        return null
    }
}
