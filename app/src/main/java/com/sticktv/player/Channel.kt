package com.sticktv.player

data class Channel(
    val number: Int,
    val name: String,
    val url: String,
    val logo: String?,
    val group: String,
    val userAgent: String?,
    val referer: String?
) {
    val searchKey: String = name.normalizeForSearch()
}
