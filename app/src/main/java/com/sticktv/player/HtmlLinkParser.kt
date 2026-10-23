package com.sticktv.player

import android.text.Html
import java.net.URI
import java.util.Locale

/** Imports same-site page links and reads only explicit standard HTML video sources. */
object HtmlLinkParser {
    private val anchorRegex = Regex("""(?is)<a\b([^>]*)>(.*?)</a\s*>""")
    private val hrefRegex = Regex("""(?is)\bhref\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val tagRegex = Regex("""(?is)<[^>]+>""")
    private val blockedLabels = setOf(
        "home", "inicio", "início", "contato", "contact", "privacidade", "privacy",
        "termos", "terms", "login", "entrar", "registrar", "register", "pesquisar",
        "search", "facebook", "instagram", "telegram", "whatsapp", "twitter", "x"
    )
    private val mediaExtension = Regex("""(?i)\.(m3u8?|mpd|mp4|mkv|m4v|ts|webm|mov)(?:$|[?#])""")
    private val mediaSrcRegex = Regex("""(?is)\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")
    private val metaContentRegex = Regex("""(?is)\bcontent\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun parse(html: String, sourceUrl: String): List<Channel> {
        val base = runCatching { URI(sourceUrl) }.getOrNull() ?: return emptyList()
        val baseHost = base.host?.lowercase(Locale.ROOT) ?: return emptyList()
        val seen = HashSet<String>()
        val channels = ArrayList<Channel>()

        for (match in anchorRegex.findAll(html)) {
            val hrefMatch = hrefRegex.find(match.groupValues[1]) ?: continue
            val rawHref = (1..3).firstNotNullOfOrNull { hrefMatch.groups[it]?.value } ?: continue
            val href = decode(rawHref).trim()
            if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:", true)) continue

            val resolved = runCatching { base.resolve(URI(href.replace(" ", "%20"))).normalize() }.getOrNull() ?: continue
            if (resolved.scheme !in setOf("http", "https") || !resolved.host.equals(baseHost, ignoreCase = true)) continue
            val target = resolved.toASCIIString().substringBefore("#")
            if (target == sourceUrl.substringBefore("#") || mediaExtension.containsMatchIn(target)) continue

            val anchorText = match.groupValues[2]
                .replace(Regex("""(?is)<script\b.*?</script>"""), " ")
                .replace(Regex("""(?is)<style\b.*?</style>"""), " ")
                .replace(tagRegex, " ")
                .replace(Regex("""\s+"""), " ")
                .trim()
            val imageAlt = Regex("""(?is)\balt\s*=\s*(?:"([^"]*)"|'([^']*)')""").find(match.groupValues[2])
                ?.let { it.groups[1]?.value ?: it.groups[2]?.value }
            val label = decode(anchorText).ifBlank { decode(imageAlt.orEmpty()).trim() }
            if (label.length !in 2..90 || label.lowercase(Locale.ROOT) in blockedLabels) continue
            if (!seen.add(target)) continue

            channels += Channel(
                number = channels.size + 1,
                name = label,
                url = target,
                logo = null,
                group = "Links da página",
                userAgent = null,
                referer = sourceUrl,
                isPageLink = true
            )
            if (channels.size >= 500) break
        }
        return channels
    }

    /** Reads only media URLs explicitly present in standard public HTML tags. */
    fun extractPublicStream(html: String, pageUrl: String): String? {
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        val candidates = ArrayList<String>()
        val tagRegex = Regex("""(?is)<(?:video|source)\b[^>]*>""")
        for (match in tagRegex.findAll(html)) {
            val tag = match.value
            mediaSrcRegex.find(tag)?.let { found -> (1..3).firstNotNullOfOrNull { found.groups[it]?.value }?.let(candidates::add) }
        }
        val metaRegex = Regex("""(?is)<meta\b[^>]*>""")
        val videoProperties = setOf("og:video", "og:video:url", "twitter:player:stream", "contenturl")
        for (match in metaRegex.findAll(html)) {
            val tag = match.value
            val property = Regex("""(?is)\b(?:property|name|itemprop)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
                .find(tag)?.let { it.groups[1]?.value ?: it.groups[2]?.value }?.lowercase(Locale.ROOT)
            if (property !in videoProperties) continue
            metaContentRegex.find(tag)?.let { found -> (1..3).firstNotNullOfOrNull { found.groups[it]?.value }?.let(candidates::add) }
        }
        for (candidate in candidates) {
            val raw = decode(candidate).trim()
            val resolved = runCatching { base.resolve(URI(raw.replace(" ", "%20")).normalize()) }.getOrNull() ?: continue
            if (resolved.scheme in setOf("http", "https") && !resolved.host.isNullOrBlank()) return resolved.toASCIIString()
        }
        return null
    }
    @Suppress("DEPRECATION")
    private fun decode(value: String): String = Html.fromHtml(value).toString()
}
