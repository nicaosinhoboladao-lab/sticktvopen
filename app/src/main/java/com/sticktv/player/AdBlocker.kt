package com.sticktv.player

import android.net.Uri
import android.webkit.WebResourceResponse
import java.io.ByteArrayInputStream

/** Bloqueador de anúncios usado apenas no modo web (reserva). */
object AdBlocker {

    private val blockedDomains = hashSetOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com",
        "adservice.google.com", "imasdk.googleapis.com",
        "popads.net", "popcash.net", "propellerads.com", "propellerclick.com",
        "exoclick.com", "exosrv.com", "exdynsrv.com", "juicyads.com",
        "adsterra.com", "adsterratech.com", "hilltopads.net", "hilltopads.com",
        "trafficjunky.net", "adcash.com", "onclickads.net", "onclkds.com",
        "clickadu.com", "mgid.com", "taboola.com", "outbrain.com",
        "revcontent.com", "adnxs.com", "criteo.com", "criteo.net",
        "amazon-adsystem.com", "advertising.com", "zedo.com", "yllix.com",
        "a-ads.com", "bidvertiser.com", "admaven.com", "ad-maven.com",
        "monetag.com", "highperformanceformat.com", "profitablegatecpm.com",
        "pushame.com", "richpush.co", "galaksion.com", "popmyads.com",
        "adskeeper.com", "adspyglass.com", "realsrv.com", "tsyndicate.com",
        "scorecardresearch.com", "quantserve.com", "coinhive.com",
        "pubmatic.com", "rubiconproject.com", "openx.net", "smartadserver.com",
        "adform.net", "yieldmo.com", "media.net", "infolinks.com",
        "popunder.net", "clickaine.com", "adxpansion.com", "plugrush.com",
        "dtscout.com", "dtscdn.com", "acint.net", "displayvertising.com"
    )

    private val blockedKeywords = listOf(
        "/ads/", "/adserver", "popunder", "/popads", "adsbygoogle",
        "/pagead/", "doubleclick", "/advert", "prebid"
    )

    fun isAd(uri: Uri): Boolean {
        val host = uri.host?.lowercase() ?: return false
        var h = host
        while (true) {
            if (h in blockedDomains) return true
            val dot = h.indexOf('.')
            if (dot < 0) break
            h = h.substring(dot + 1)
        }
        val full = uri.toString().lowercase()
        return blockedKeywords.any { full.contains(it) }
    }

    fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))

    /** Domínio principal (ex: player.site.com.br -> site.com.br). */
    fun baseDomain(host: String): String {
        val parts = host.lowercase().removePrefix("www.").split('.').filter { it.isNotEmpty() }
        if (parts.size <= 2) return parts.joinToString(".")
        val last = parts[parts.size - 1]
        val second = parts[parts.size - 2]
        val n = if (last.length == 2 && second.length <= 3) 3 else 2
        return parts.takeLast(n).joinToString(".")
    }
}
