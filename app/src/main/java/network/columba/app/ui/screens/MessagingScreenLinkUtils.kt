package network.columba.app.ui.screens

import android.net.Uri
import android.util.Patterns
import java.util.regex.Pattern

/** Matches nomadnetwork:// and lxma:// URIs (not caught by Patterns.WEB_URL). */
internal val CUSTOM_SCHEME_URL: Pattern =
    Pattern.compile(
        """(?:nomadnetwork|lxma)://[^\s,;!?)\]]+(?<![.,;])""",
        Pattern.CASE_INSENSITIVE,
    )

/**
 * Matches a bare NomadNet page/file address: a 32-char hex destination hash
 * immediately followed by ":/<path>", e.g. "9ce9…fe4:/page/forum/register.mu".
 *
 * The leading negative lookbehind prevents matching the trailing 32 chars of a
 * longer hex run; the trailing lookbehind drops sentence punctuation. Bare 32-char
 * hashes WITHOUT the ":/" path are intentionally not matched — those are commonly
 * pasted identity/destination hashes, not navigable page links (#921).
 */
internal val NOMADNET_ADDRESS: Pattern =
    Pattern.compile(
        """(?<![0-9a-fA-F])[0-9a-fA-F]{32}:/[^\s,;!?)\]]+(?<![.,;:])""",
    )

/** Start-anchored form of [NOMADNET_ADDRESS], used to scheme-normalize a single token. */
private val BARE_NOMADNET_ADDRESS = Regex("""^[0-9a-fA-F]{32}:/[^\s,;!?)\]]+$""")

private fun cleanUrlForOpening(raw: String): String {
    val trimmed = raw.trim()
    return trimmed.trimEnd { ch -> ch in listOf('.', ',', ';', ':', '!', '?', ')', ']', '}') }
}

/**
 * Cleans a raw URL string and ensures it has a proper scheme.
 * - Trims whitespace
 * - Removes trailing punctuation (period, comma, etc.)
 * - Bare NomadNet addresses (`<32-hex>:/path`) get the `nomadnetwork://` scheme so they
 *   route to the in-app NomadNet browser instead of being opened as web links (#921)
 * - Otherwise adds https:// scheme if none present
 *
 * @return The cleaned URL string ready for parsing
 */
internal fun toBrowsableUrl(rawUrl: String): String {
    val cleaned = cleanUrlForOpening(rawUrl)
    val hasScheme =
        cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true) ||
            cleaned.startsWith("nomadnetwork://", ignoreCase = true) ||
            cleaned.startsWith("lxma://", ignoreCase = true)
    return when {
        hasScheme -> cleaned
        BARE_NOMADNET_ADDRESS.matches(cleaned) -> "nomadnetwork://$cleaned"
        else -> "https://$cleaned"
    }
}

/**
 * Converts a raw URL string to a browsable Uri.
 * @see toBrowsableUrl for the string cleaning logic
 */
internal fun toBrowsableUri(rawUrl: String): Uri = Uri.parse(toBrowsableUrl(rawUrl))

/**
 * Detect clickable link ranges in [text] as `(start, endExclusive)` pairs, sorted by
 * start with overlaps removed.
 *
 * Detection priority is NomadNet address > custom-scheme URI (nomadnetwork://, lxma://)
 * > web URL: a bare NomadNet address like "<hash>:/page/forum/register.mu" begins at the
 * hash, so it wins over the spurious ".mu" web-URL match nested inside it (#921). Priority
 * is realized positionally — the earliest-starting candidate is kept and anything
 * overlapping it is dropped.
 *
 * Extracted as a pure function so the detection logic is unit-testable.
 */
internal fun detectLinkRanges(text: String): List<Pair<Int, Int>> {
    val candidates = mutableListOf<Pair<Int, Int>>()

    val nomadMatcher = NOMADNET_ADDRESS.matcher(text)
    while (nomadMatcher.find()) {
        candidates.add(nomadMatcher.start() to nomadMatcher.end())
    }

    val webMatcher = Patterns.WEB_URL.matcher(text)
    while (webMatcher.find()) {
        candidates.add(webMatcher.start() to webMatcher.end())
    }

    val customSchemeMatcher = CUSTOM_SCHEME_URL.matcher(text)
    while (customSchemeMatcher.find()) {
        candidates.add(customSchemeMatcher.start() to customSchemeMatcher.end())
    }

    // Earliest start wins; on equal start the longer span wins. Then greedily drop any
    // candidate that overlaps one already kept.
    candidates.sortWith(compareBy({ it.first }, { -it.second }))
    val resolved = mutableListOf<Pair<Int, Int>>()
    var lastEnd = 0
    for (range in candidates) {
        if (range.first < lastEnd) continue
        resolved.add(range)
        lastEnd = range.second
    }
    return resolved
}
