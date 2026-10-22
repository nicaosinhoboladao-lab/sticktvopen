package com.sticktv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

class PlaylistRepository(context: Context, private val prefs: Prefs) {

    private val cacheFile = File(context.filesDir, "playlist_cache.m3u")

    /** Lista salva no aparelho: o app abre instantaneamente, mesmo se o servidor estiver lento. */
    suspend fun loadCached(url: String): List<Channel> = withContext(Dispatchers.IO) {
        if (prefs.cacheUrl != url || !cacheFile.exists()) return@withContext emptyList()
        M3uParser.parse(cacheFile.readText())
    }

    suspend fun download(url: String): List<Channel> = withContext(Dispatchers.IO) {
        val text = httpGet(url)
        val list = M3uParser.parse(text)
        if (list.isEmpty()) throw IOException("A lista está vazia ou não é um M3U válido.")
        try {
            cacheFile.writeText(text)
            prefs.cacheUrl = url
        } catch (_: Exception) {
        }
        list
    }

    private fun httpGet(start: String): String {
        var url = URL(start)
        var redirects = 0
        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", Config.USER_AGENT)
            conn.setRequestProperty("Accept-Encoding", "gzip")
            try {
                val code = conn.responseCode
                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location == null || ++redirects > 5) throw IOException("Redirecionamento inválido")
                    url = URL(url, location)
                    continue
                }
                if (code !in 200..299) throw IOException("Servidor respondeu HTTP $code")
                val input = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }
                return input.bufferedReader(Charsets.UTF_8).use { it.readText() }
            } finally {
                conn.disconnect()
            }
        }
    }
}
