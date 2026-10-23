package com.sticktv.player

object Config {
    /**
     * URL padrão da lista M3U. Pode ser trocada dentro do app
     * (tecla MENU ou botão "Configurações").
     */
    val DEFAULT_PLAYLIST_URL = listOf(
        "https://iptv-org.github.io/iptv/countries/br.m3u",
        "https://iptv-org.github.io/iptv/languages/por.m3u",
        "https://iptv-org.github.io/iptv/categories/news.m3u"
    ).joinToString("\n")

    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 9; Mi TV Stick) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
}
