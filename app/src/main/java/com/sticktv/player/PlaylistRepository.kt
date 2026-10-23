package com.sticktv.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

data class PlaylistLoadResult(
    val channels: List<Channel>,
    val sourceCount: Int,
    val loadedSourceCount: Int,
    val inputChannelCount: Int,
    val failures: List<String>
) {
    val alternativeCount: Int
        get() = channels.sumOf { it.alternatives.size }
}

class PlaylistRepository(context: Context, private val prefs: Prefs) {

    private val cacheDir = File(context.filesDir, "playlist_cache").apply { mkdirs() }
    private val legacyCacheFile = File(context.filesDir, "playlist_cache.m3u")

    private sealed class SourceResult {
        class Success(val index: Int, val text: String, val channels: List<Channel>) : SourceResult()
        class Failure(val message: String) : SourceResult()
    }

    suspend fun loadCached(
        url: String,
        extractPageLinks: Boolean = false
    ): List<Channel> = withContext(Dispatchers.IO) {
        if (prefs.cacheUrl != url || prefs.cacheExtractPageLinks != extractPageLinks) {
            return@withContext emptyList()
        }

        val channels = ArrayList<Channel>()
        parseSources(url).forEachIndexed { index, source ->
            val file = cacheFileFor(index)
            if (file.exists()) {
                runCatching {
                    channels += parseContent(file.readText(), source, extractPageLinks)
                }
            }
        }

        ChannelMerger.merge(channels)
    }

    suspend fun download(
        url: String,
        extractPageLinks: Boolean = false
    ): PlaylistLoadResult = withContext(Dispatchers.IO) {
        val sources = parseSources(url)

        if (sources.isEmpty()) {
            throw IOException("Nenhuma URL configurada.")
        }
        if (extractPageLinks && sources.size > 1) {
            throw IOException("Use apenas uma pÃ¡gina por vez ao extrair links de sites.")
        }

        // Baixa vÃ¡rias listas ao mesmo tempo, mas mantÃ©m a ordem original:
        // a primeira lista continua sendo a fonte principal.
        val semaphore = Semaphore(MAX_PARALLEL_DOWNLOADS)
        val results = coroutineScope {
            sources.mapIndexed { index, source ->
                async {
                    semaphore.withPermit { fetchSource(index, source, extractPageLinks) }
                }
            }.awaitAll()
        }

        val successes = results.filterIsInstance<SourceResult.Success>()
        val failures = results.filterIsInstance<SourceResult.Failure>().map { it.message }
        val loaded = successes.flatMap { it.channels }

        val merged = ChannelMerger.merge(loaded)
        if (merged.isEmpty()) {
            throw IOException(
                "Nenhuma lista pÃ´de ser carregada. " + failures.joinToString("; ")
            )
        }

        try {
            writeCache(successes)
            prefs.cacheUrl = url
            prefs.cacheExtractPageLinks = extractPageLinks
        } catch (_: Exception) {
            // Falha no cache nÃ£o impede o uso da lista
        }

        PlaylistLoadResult(
            channels = merged,
            sourceCount = sources.size,
            loadedSourceCount = successes.size,
            inputChannelCount = loaded.size,
            failures = failures
        )
    }

    suspend fun resolvePublicStream(channel: Channel): String? =
        withContext(Dispatchers.IO) {
            val page = httpGet(channel.url)
            HtmlLinkParser.extractPublicStream(page, channel.url)
        }

    private fun fetchSource(
        index: Int,
        source: String,
        extractPageLinks: Boolean
    ): SourceResult {
        return try {
            val text = httpGet(source)
            val parsed = parseContent(text, source, extractPageLinks)

            if (parsed.isEmpty()) {
                val html = text.trimStart().startsWith("<") ||
                    text.contains("<html", ignoreCase = true)

                throw IOException(
                    if (html && !extractPageLinks) {
                        "pÃ¡gina HTML; marque \"Extrair links\" para essa fonte"
                    } else {
                        "lista vazia ou formato invÃ¡lido"
                    }
                )
            }

            SourceResult.Success(index, text, parsed)
        } catch (e: CancellationException) {
            throw e
        } catch (e: OutOfMemoryError) {
            SourceResult.Failure("${hostOf(source)}: lista grande demais para a memÃ³ria do aparelho")
        } catch (e: Exception) {
            SourceResult.Failure("${hostOf(source)}: ${e.message ?: "falha desconhecida"}")
        }
    }

    private fun hostOf(source: String): String =
        runCatching { URL(source).host }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
            ?: source.take(60)

    private fun parseSources(url: String): List<String> =
        url.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()

    private fun cacheFileFor(index: Int) = File(cacheDir, "$index.m3u")

    private fun writeCache(successes: List<SourceResult.Success>) {
        cacheDir.listFiles()?.forEach { it.delete() }
        legacyCacheFile.delete()

        for (s in successes) {
            val tmp = File(cacheDir, "${s.index}.tmp")
            tmp.writeText(s.text)
            tmp.renameTo(cacheFileFor(s.index))
        }
    }

    private fun parseContent(
        content: String,
        sourceUrl: String,
        extractPageLinks: Boolean
    ): List<Channel> =
        if (extractPageLinks) {
            HtmlLinkParser.parse(content, sourceUrl)
        } else {
            M3uParser.parse(content)
        }

    private fun httpGet(start: String): String {
        var url = URL(start)
        var redirects = 0

        while (true) {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = false
            conn.useCaches = false
            conn.setRequestProperty("User-Agent", Config.USER_AGENT)
            conn.setRequestProperty("Accept", "*/*")
            conn.setRequestProperty("Accept-Encoding", "gzip")

            try {
                val code = conn.responseCode

                if (code in 300..399) {
                    val location = conn.getHeaderField("Location")
                    if (location == null || ++redirects > MAX_REDIRECTS) {
                        throw IOException("redirecionamento invÃ¡lido")
                    }
                    url = URL(url, location)
                    continue
                }

                if (code !in 200..299) {
                    throw IOException("o servidor respondeu HTTP $code")
                }

                val input = if ("gzip".equals(conn.contentEncoding, ignoreCase = true)) {
                    GZIPInputStream(conn.inputStream)
                } else {
                    conn.inputStream
                }

                return input.bufferedReader(Charsets.UTF_8)
                    .use { it.readText() }
                    .removePrefix("\uFEFF") // BOM quebra a detecÃ§Ã£o de #EXTM3U
            } finally {
                conn.disconnect()
            }
        }
    }

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val MAX_REDIRECTS = 5
    }
}
