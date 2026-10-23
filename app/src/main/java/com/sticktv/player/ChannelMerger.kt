package com.sticktv.player

import java.text.Normalizer
import java.util.Locale

object ChannelMerger {

    private val diacritics = Regex("""\p{Mn}+""")
    private val separators = Regex("""[^\p{L}\p{N}]+""")

    private val qualityWords = Regex(
        """\b(uhd|8k|4k|fhd|full\s*hd|hd|sd|hq|2160p?|1080p?|720p?|480p?|h\s?26[45]|hevc|\d{2}\s?fps)\b"""
    )

    // Ignore source labels such as Alt, Backup, Option, and Link when matching channel names
    private val noiseWords = Regex(
        """\b(alt|alternativo|backup|bkp|reserva|opcao|op|link|fonte|server|srv|sinal)\s*\d*\b"""
    )

    private val leadingNoise = setOf("tv", "rede", "canal", "br", "brasil", "brazil")
    private val trailingNoise = setOf("tv", "ao", "vivo", "live", "br", "brasil", "brazil", "24h", "24hs")

    private val brazilRegions = setOf(
        "sp", "rj", "mg", "rs", "pr", "sc", "ba", "pe", "ce", "go", "df", "am", "pa", "mt",
        "ms", "es", "rn", "pb", "al", "se", "pi", "ma", "to", "ro", "rr", "ap", "ac"
    )

    /**
     * Junta canais iguais vindos de listas diferentes.
     * The first match is the primary source; later matches become alternatives,
     * in the configured playlist order.
     *
     * @param mergeRegionalVariants se true, "Globo SP" e "Globo RJ" viram o mesmo canal.
     *        The default is false so fallback keeps regional channels separate.
     */
    fun merge(
        channels: List<Channel>,
        mergeRegionalVariants: Boolean = false
    ): List<Channel> {
        val merged = ArrayList<Channel>()
        val keyToIndex = HashMap<String, Int>()

        for (channel in channels) {
            if (channel.url.isBlank()) continue

            val key = identityKey(channel, mergeRegionalVariants)
            val index = key?.let { keyToIndex[it] }

            if (index == null) {
                key?.let { keyToIndex[it] = merged.size }
                merged.add(
                    channel.copy(
                        alternatives = cleanAlternatives(channel.url, channel.alternatives)
                    )
                )
                continue
            }

            val previous = merged[index]
            val incoming = buildList {
                add(StreamVariant(channel.url, channel.userAgent, channel.referer))
                addAll(channel.alternatives)
            }

            merged[index] = previous.copy(
                alternatives = cleanAlternatives(previous.url, previous.alternatives + incoming)
            )
        }

        return merged.mapIndexed { i, item -> item.copy(number = i + 1) }
    }

    /** Chave usada para reconhecer o mesmo canal em listas diferentes. */
    fun identityKey(channel: Channel, mergeRegionalVariants: Boolean = false): String? {
        canonicalName(channel.name, mergeRegionalVariants)?.let { return "name:$it" }

        // Use tvg-id only when the name is unavailable because many playlists
        // reuse the same id for different regional channels.
        return channel.channelId
            ?.let { normalize(it) }
            ?.replace(separators, "")
            ?.takeIf { it.isNotBlank() }
            ?.let { "id:$it" }
    }

    private fun cleanAlternatives(
        mainUrl: String,
        list: List<StreamVariant>
    ): List<StreamVariant> {
        val main = mainUrl.trim()
        return list
            .filter { it.url.isNotBlank() && it.url.trim() != main }
            .distinctBy { it.url.trim() }
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFKD) // Normalize accents and quality labels
            .replace(diacritics, "")
            .lowercase(Locale.ROOT)

    private fun canonicalName(name: String, mergeRegional: Boolean): String? {
        var text = normalize(name)
        text = noiseWords.replace(text, " ")
        text = qualityWords.replace(text, " ")

        val words = text
            .split(separators)
            .filter { it.isNotBlank() }
            .toMutableList()

        // size > 1 evita que "TV Brasil" vire um nome vazio
        while (words.size > 1 && words[0] in leadingNoise) {
            words.removeAt(0)
        }

        while (words.size > 1) {
            val last = words.last()
            val remove = last in trailingNoise || (mergeRegional && last in brazilRegions)
            if (!remove) break
            words.removeAt(words.lastIndex)
        }

        return words.joinToString("").takeIf { it.isNotBlank() }
    }
}