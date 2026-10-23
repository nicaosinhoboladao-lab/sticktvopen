package com.sticktv.player

object Config {
    /** Public Brazilian and Portuguese channel lists maintained by iptv-org on GitHub. */
    val PUBLIC_PORTUGUESE_PLAYLISTS = listOf(
        "https://iptv-org.github.io/iptv/countries/br.m3u",
        "https://iptv-org.github.io/iptv/languages/por.m3u",
        "https://iptv-org.github.io/iptv/countries/pt.m3u"
    )

    /** Default sources; users can change them in settings. */
    val DEFAULT_PLAYLIST_URL = PUBLIC_PORTUGUESE_PLAYLISTS.joinToString("\n")

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; Mi TV Stick) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}