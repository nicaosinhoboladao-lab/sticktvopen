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
    suspend fun loadCached(url: String, extractPageLinks: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        if (prefs.cacheUrl != url || prefs.cacheExtractPageLinks != extractPageLinks || !cacheFile.exists()) {
            return@withContext emptyList()
        }
        parseContent(cacheFile.readText(), url, extractPageLinks)
    }

    suspend fun download(url: String, extractPageLinks: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        val text = httpGet(url)
        val list = parseContent(text, url, extractPageLinks)
        if (list.isEmpty()) {
            val detail = if (extractPageLinks) "Nenhum link interno de página foi encontrado." else "A resposta não é uma playlist M3U válida."
            throw IOException(detail)
        }
        try {
            cacheFile.writeText(text)
            prefs.cacheUrl = url
            prefs.cacheExtractPageLinks = extractPageLinks
        } catch (_: Exception) {
        }
        list
    }

    suspend fun resolvePublicStream(channel: Channel): String? = withContext(Dispatchers.IO) {
        val page = httpGet(channel.url)
        HtmlLinkParser.extractPublicStream(page, channel.url)
    }
    private fun parseContent(content: String, sourceUrl: String, extractPageLinks: Boolean): List<Channel> =
        if (extractPageLinks) HtmlLinkParser.parse(content, sourceUrl) else M3uParser.parse(content)

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
