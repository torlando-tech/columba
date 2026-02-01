package com.lxmf.messenger.ui.screens

import android.net.Uri

private fun cleanUrlForOpening(raw: String): String {
    val trimmed = raw.trim()
    return trimmed.trimEnd { ch -> ch in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}') }
}

/**
 * Cleans a raw URL string and ensures it has a proper scheme.
 * - Trims whitespace
 * - Removes trailing punctuation (period, comma, etc.)
 * - Adds https:// scheme if none present
 *
 * @return The cleaned URL string ready for parsing
 */
internal fun toBrowsableUrl(rawUrl: String): String {
    val cleaned = cleanUrlForOpening(rawUrl)
    val hasScheme = cleaned.startsWith("http://", ignoreCase = true) || cleaned.startsWith("https://", ignoreCase = true)
    return if (hasScheme) cleaned else "https://$cleaned"
}

/**
 * Converts a raw URL string to a browsable Uri.
 * @see toBrowsableUrl for the string cleaning logic
 */
internal fun toBrowsableUri(rawUrl: String): Uri = Uri.parse(toBrowsableUrl(rawUrl))
