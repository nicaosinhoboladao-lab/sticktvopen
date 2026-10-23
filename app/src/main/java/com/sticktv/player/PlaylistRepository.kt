package com.sticktv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

data class PlaylistLoadResult(
    val channels: List<Channel>,
    val sourceCount: Int,
    val loadedSourceCount: Int,
    val inputChannelCount: Int,
    val failures: List<String>
) {
    val alternativeCount: Int get() = channels.sumOf { it.alternatives.size }
}

class PlaylistRepository(context: Context, private val prefs: Prefs) {

    private val cacheFile = File(context.filesDir, "playlist_cache.m3u")

    suspend fun loadCached(url: String, extractPageLinks: Boolean = false): List<Channel> = withContext(Dispatchers.IO) {
        if (prefs.cacheUrl != url || prefs.cacheExtractPageLinks != extractPageLinks || !cacheFile.exists()) {
            return@withContext emptyList()
        }
        ChannelMerger.merge(parseContent(cacheFile.readText(), url.lineSequence().first(), extractPageLinks))
    }

    suspend fun download(url: String, extractPageLinks: Boolean = false): PlaylistLoadResult = withContext(Dispatchers.IO) {
        val sources = url.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.distinct().toList()
        if (sources.isEmpty()) throw IOException("Nenhuma URL configurada.")
        if (extractPageLinks && sources.size > 1) throw IOException("Use apenas uma pagina por vez ao extrair links de site.")

        val loaded = ArrayList<Channel>()
        val texts = ArrayList<String>()
        val failures = ArrayList<String>()
        for (source in sources) {
            try {
                val text = httpGet(source)
                val parsed = parseContent(text, source, extractPageLinks)
                if (parsed.isEmpty()) {
                    val html = text.trimStart().startsWith("<") || text.contains("<html", ignoreCase = true)
                    throw IOException(if (html && !extractPageLinks) "pagina HTML; marque Extrair links para essa fonte" else "lista vazia ou formato invalido")
                }
                texts.add(text)
                loaded.addAll(parsed)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                val host = runCatching { URL(source).host }.getOrDefault(source.take(60))
                failures.add("$host: ${e.message ?: "falha"}")
            }
        }
        val result = ChannelMerger.merge(loaded)
        if (result.isEmpty()) throw IOException("Nenhuma lista pode ser carregada. " + failures.joinToString("; "))
        try {
            cacheFile.writeText(texts.joinToString("\n"))
            prefs.cacheUrl = url
            prefs.cacheExtractPageLinks = extractPageLinks
        } catch (_: Exception) {
        }
        PlaylistLoadResult(result, sources.size, texts.size, loaded.size, failures)
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
                    if (location == null || ++redirects > 5) throw IOException("Redirecionamento invalido")
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

