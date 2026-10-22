package com.sticktv.player

import android.content.Context

class Prefs(context: Context) {

    private val sp = context.getSharedPreferences("sticktv_prefs", Context.MODE_PRIVATE)

    var playlistUrl: String
        get() = sp.getString(KEY_URL, null) ?: Config.DEFAULT_PLAYLIST_URL
        set(value) = sp.edit().putString(KEY_URL, value).apply()

    var cacheUrl: String?
        get() = sp.getString(KEY_CACHE_URL, null)
        set(value) = sp.edit().putString(KEY_CACHE_URL, value).apply()

    var lastChannelUrl: String?
        get() = sp.getString(KEY_LAST, null)
        set(value) = sp.edit().putString(KEY_LAST, value).apply()

    var favorites: Set<String>
        get() = sp.getStringSet(KEY_FAV, null)?.toHashSet() ?: hashSetOf()
        set(value) = sp.edit().putStringSet(KEY_FAV, HashSet(value)).apply()

    companion object {
        private const val KEY_URL = "playlist_url"
        private const val KEY_CACHE_URL = "cache_url"
        private const val KEY_LAST = "last_channel"
        private const val KEY_FAV = "favorites"
    }
}
