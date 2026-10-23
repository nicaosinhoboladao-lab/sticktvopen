package com.sticktv.player

object ChannelMerger {
    private val qualityWords = Regex("""\b(uhd|4k|fhd|full hd|1080p|720p|hd|sd)\b""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""[^\p{L}\p{N}]+""")
    private val brazilRegions = setOf("br", "brasil", "brazil", "sp", "rj", "mg", "rs", "pr", "sc", "ba", "pe", "ce", "go", "df", "am", "pa", "mt", "ms", "es", "rn", "pb", "al", "se", "pi", "ma", "to", "ro", "rr", "ap", "ac")

    fun merge(channels: List<Channel>): List<Channel> {
        val merged = ArrayList<Channel>()
        val identityToIndex = HashMap<String, Int>()
        for (channel in channels) {
            val identities = identities(channel)
            val index = identities.firstNotNullOfOrNull { identityToIndex[it] }
            if (index == null) {
                val newIndex = merged.size
                merged.add(channel.copy(number = newIndex + 1, alternatives = channel.alternatives.distinctBy { it.url }))
                identities.forEach { identityToIndex.putIfAbsent(it, newIndex) }
                continue
            }

            val previous = merged[index]
            val candidates = previous.alternatives +
                (if (previous.url != channel.url) listOf(StreamVariant(channel.url, channel.userAgent, channel.referer)) else emptyList()) +
                channel.alternatives
            merged[index] = previous.copy(alternatives = candidates.distinctBy { it.url }.filterNot { it.url == previous.url })
            identities.forEach { identityToIndex.putIfAbsent(it, index) }
        }
        return merged.mapIndexed { index, item -> item.copy(number = index + 1) }
    }

    private fun identities(channel: Channel): List<String> = buildList {
        channel.channelId?.normalizeForSearch()?.takeIf { it.isNotBlank() }?.let { add("id:$it") }
        canonicalName(channel.name)?.let { add("name:$it") }
    }

    private fun canonicalName(name: String): String? {
        val cleaned = separators.replace(qualityWords.replace(name.normalizeForSearch(), " "), " ").trim()
        val words = cleaned.split(Regex("\\s+")).filter { it.isNotBlank() }.toMutableList()
        while (words.firstOrNull()?.let { it == "tv" || it == "rede" } == true) words.removeAt(0)
        while (words.lastOrNull()?.let { it in setOf("tv", "ao", "vivo", "live", "br", "brasil", "brazil") || it in brazilRegions } == true) {
            words.removeAt(words.lastIndex)
        }
        return words.joinToString("").takeIf { it.isNotBlank() }
    }
}




