package com.sticktv.player

import java.text.Normalizer
import java.util.Locale

private val DIACRITICS = Regex("\\p{Mn}+")

/** Remove acentos e deixa minúsculo, para a pesquisa funcionar com "espn", "ESPN", "Esportes" etc. */
fun String.normalizeForSearch(): String =
    DIACRITICS.replace(Normalizer.normalize(this, Normalizer.Form.NFD), "")
        .lowercase(Locale.ROOT)
        .trim()
