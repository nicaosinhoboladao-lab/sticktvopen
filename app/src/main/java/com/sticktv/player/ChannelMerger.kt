package com.sticktv.player

object ChannelMerger {
    private val qualitySuffix = Regex("""\b(uhd|4k|fhd|full hd|1080p|720p|hd|sd)\b""", RegexOption.IGNORE_CASE)
    private val separators = Regex("""[^\p{L}\p{N}]+""")

    fun merge(channels: List<Channel>): List<Channel> {
        val merged = LinkedHashMap<String, Channel>()
        for (channel in channels) {
            val key = channel.channelId?.normalizeForSearch()?.takeIf { it.isNotBlank() }
                ?: separators.replace(qualitySuffix.replace(channel.name.normalizeForSearch(), " "), "")
            val previous = merged[key]
            if (previous == null) {
                merged[key] = channel.copy(number = merged.size + 1, alternatives = emptyList())
            } else if (previous.url != channel.url) {
                val candidates = previous.alternatives + StreamVariant(channel.url, channel.userAgent, channel.referer) + channel.alternatives
                merged[key] = previous.copy(alternatives = candidates.distinctBy { it.url })
            }
        }
        return merged.values.mapIndexed { index, item -> item.copy(number = index + 1) }
    }
}
