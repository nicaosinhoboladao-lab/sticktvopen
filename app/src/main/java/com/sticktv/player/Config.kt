package com.sticktv.player

object Config {
    private const val IPTV_ORG_PLAYLIST_BASE = "https://iptv-org.github.io/iptv/"
    const val PUBLIC_PLAYLIST_CATALOG_API =
        "https://api.github.com/repos/iptv-org/iptv/git/trees/gh-pages?recursive=1"

    // The catalog checks Portuguese-language and Lusophone-country playlists.
    val PUBLIC_PORTUGUESE_PLAYLIST_PATHS = listOf(
        "languages/por.m3u",
        "countries/br.m3u",
        "countries/pt.m3u",
        "countries/ao.m3u",
        "countries/mz.m3u",
        "countries/cv.m3u",
        "countries/gw.m3u",
        "countries/st.m3u",
        "countries/tl.m3u",
        "countries/gq.m3u"
    )

    val PUBLIC_PORTUGUESE_PLAYLISTS = listOf(
        "countries/br.m3u",
        "languages/por.m3u",
        "countries/pt.m3u"
    ).map { publicPlaylistUrl(it) }

    val DEFAULT_PLAYLIST_URL = listOf(
        "countries/br.m3u",
        "languages/por.m3u",
        "countries/pt.m3u"
    ).joinToString("\n") { publicPlaylistUrl(it) }

    fun publicPlaylistUrl(path: String): String = IPTV_ORG_PLAYLIST_BASE + path

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; Mi TV Stick) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}