package com.sticktv.player

object M3uParser {

    private val attrRegex = Regex("""([\w-]+)\s*=\s*"([^"]*)"""")

    fun parse(content: String): List<Channel> {
        val out = ArrayList<Channel>()

        var pendingName: String? = null
        var logo: String? = null
        var group: String? = null
        var userAgent: String? = null
        var referer: String? = null
        var extGroup: String? = null

        fun reset() {
            pendingName = null
            logo = null
            group = null
            userAgent = null
            referer = null
            extGroup = null
        }

        for (raw in content.lineSequence()) {
            val line = raw.trim().trimStart('\uFEFF')
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    val matches = attrRegex.findAll(line).toList()
                    val attrs = HashMap<String, String>()
                    for (m in matches) {
                        attrs[m.groupValues[1].lowercase()] = m.groupValues[2].trim()
                    }
                    val searchFrom = matches.lastOrNull()?.range?.last?.plus(1) ?: 0
                    val comma = line.indexOf(',', searchFrom)
                    var title = if (comma >= 0) line.substring(comma + 1).trim() else ""
                    if (title.isEmpty()) title = attrs["tvg-name"].orEmpty()

                    pendingName = title.ifEmpty { "Canal" }
                    logo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.isNotBlank() }
                    group = attrs["group-title"]?.takeIf { it.isNotBlank() }
                    userAgent = (attrs["user-agent"] ?: attrs["http-user-agent"])?.takeIf { it.isNotBlank() }
                    referer = (attrs["referer"] ?: attrs["http-referrer"] ?: attrs["http-referer"])
                        ?.takeIf { it.isNotBlank() }
                }

                line.startsWith("#EXTGRP", ignoreCase = true) -> {
                    extGroup = line.substringAfter(':', "").trim().ifEmpty { null }
                }

                line.startsWith("#EXTVLCOPT", ignoreCase = true) -> {
                    val opt = line.substringAfter(':', "").trim()
                    val key = opt.substringBefore('=').trim().lowercase()
                    val value = opt.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (key) {
                            "http-user-agent" -> userAgent = value
                            "http-referrer", "http-referer" -> referer = value
                        }
                    }
                }

                line.startsWith("#") -> Unit

                else -> {
                    var url = line
                    // Formato estilo Kodi: url|User-Agent=xxx&Referer=yyy
                    if (url.contains('|')) {
                        val parts = url.split('|', limit = 2)
                        url = parts[0].trim()
                        for (kv in parts[1].split('&')) {
                            val k = kv.substringBefore('=').trim().lowercase()
                            val v = kv.substringAfter('=', "").trim()
                            if (v.isEmpty()) continue
                            when (k) {
                                "user-agent" -> userAgent = v
                                "referer", "referrer" -> referer = v
                            }
                        }
                    }
                    if (url.isNotEmpty()) {
                        val name = pendingName ?: url.substringAfterLast('/').ifEmpty { "Canal" }
                        out.add(
                            Channel(
                                number = out.size + 1,
                                name = name,
                                url = url,
                                logo = logo,
                                group = group ?: extGroup ?: "Sem categoria",
                                userAgent = userAgent,
                                referer = referer
                            )
                        )
                    }
                    reset()
                }
            }
        }
        return out
    }
}
