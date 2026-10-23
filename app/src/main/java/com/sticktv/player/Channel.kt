package com.sticktv.player

data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referer: String?,
    val isPageLink: Boolean = false,
    val sourcePageUrl: String? = null,
    val channelId: String? = null,
    val alternatives: List<StreamVariant> = emptyList()
) {
    val searchKey: String = name.normalizeForSearch()
}

data class StreamVariant(val url: String, val userAgent: String?, val referer: String?)
