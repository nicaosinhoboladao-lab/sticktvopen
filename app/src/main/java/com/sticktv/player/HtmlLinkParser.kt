package com.sticktv.player

import android.text.Html
import java.net.URI
import java.util.Locale

/** Imports visible same-site page links only; it does not inspect scripts or media players. */
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
                referer = sourceUrl
            )
            if (channels.size >= 500) break
        }
        return channels
    }

    @Suppress("DEPRECATION")
    private fun decode(value: String): String = Html.fromHtml(value).toString()
}
