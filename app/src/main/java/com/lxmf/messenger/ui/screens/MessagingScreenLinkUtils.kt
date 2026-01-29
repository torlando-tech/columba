package com.lxmf.messenger.ui.screens

import android.net.Uri

private fun cleanUrlForOpening(raw: String): String {
    val trimmed = raw.trim()
    return trimmed.trimEnd { ch -> ch in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}') }
}

fun toBrowsableUri(rawUrl: String): Uri {
    val cleaned = cleanUrlForOpening(rawUrl)
    val hasScheme = cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)
    val withScheme = if (hasScheme) cleaned else "https://$cleaned"
    return Uri.parse(withScheme)
}
