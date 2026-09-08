package network.columba.app.nomadnet

import android.util.Log
import java.util.concurrent.ConcurrentHashMap

/**
 * Image loading modes, mirroring upstream NomadNet's
 * `[textui] image_loading` (config values lowercase in config, never/manual/
 * auto/always, default auto).
 */
enum class ImageLoadingMode {
    NEVER,
    MANUAL,
    AUTO,
    ALWAYS,
    ;

    companion object {
        fun fromName(name: String?): ImageLoadingMode =
            entries.firstOrNull { it.name.equals(name?.trim(), ignoreCase = true) } ?: AUTO
    }
}

/**
 * Auto-mode gate, upstream parity (Browser.py `should_load_images`, commit
 * 0db7d4b): load automatically only when the link RTT is under
 * [AUTO_RTT_LIMIT_S] seconds AND the expected data rate exceeds
 * [AUTO_EDR_LIMIT_BPS] bits/s. Loopback (the device talking to its own host
 * service, if ever) always loads. No link data -> no load.
 *
 * [measuredResponseSpeedBps] is the fallback EDR source, mirroring upstream's
 * `last_response_speed()` (page transfer size / response time * 8).
 */
object NomadNetImagePolicy {
    private const val TAG = "NomadNetImagePolicy"

    // Upstream constants verbatim: rtt_limit = 1.5 s, edr_limit = 10000 bps.
    const val AUTO_RTT_LIMIT_S = 1.5
    const val AUTO_EDR_LIMIT_BPS = 10_000L

    /**
     * Per-image transfer cap in bytes. A separate safety axis from the
     * loading mode (Torlando 2026-09-07: "always mode still hits the size
     * cap"): a media response larger than this fails with an explicit
     * "too large" error in ALL modes, including always. Upstream NomadNet
     * caps decoded payloads at 16 MiB (MAX_CONVERTED_PAYLOAD_BYTES in
     * images/_imagedata.py); we cap the wire transfer at the same figure,
     * which is strictly tighter for WebP (always <= its decoded form).
     */
    const val MAX_IMAGE_BYTES = 16L * 1024 * 1024

    fun shouldLoadAutomatically(
        mode: ImageLoadingMode,
        isLoopback: Boolean,
        rttSeconds: Double?,
        expectedRateBps: Long?,
        measuredResponseSpeedBps: Double?,
    ): Boolean {
        if (isLoopback) return true
        return when (mode) {
            ImageLoadingMode.NEVER, ImageLoadingMode.MANUAL -> false
            ImageLoadingMode.ALWAYS -> true
            ImageLoadingMode.AUTO -> {
                val edr = expectedRateBps ?: measuredResponseSpeedBps?.toLong()
                val rttOk = rttSeconds != null && rttSeconds < AUTO_RTT_LIMIT_S
                val edrOk = edr != null && edr > AUTO_EDR_LIMIT_BPS
                Log.d(
                    TAG,
                    "auto-gate: rtt=${rttSeconds?.let { "%.2fs".format(it) } ?: "unknown"} " +
                        "edr=${edr?.let { "${it}bps" } ?: "unknown"} -> ${rttOk && edrOk}",
                )
                rttOk && edrOk
            }
        }
    }
}

/** Per-image fetch status (upstream page_images dict's failed/updated flags). */
enum class PageImageStatus {
    PLACEHOLDER,
    LOADING,
    LOADED,
    DENIED,
    FAILED,
}

/** Per-image fetch state, mirroring upstream page_images dict entries. */
data class PageImageState(
    val status: PageImageStatus = PageImageStatus.PLACEHOLDER,
    val file: java.io.File? = null,
    val progress: Float = 0f,
    val speedBps: Long? = null,
    val receivedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
    val ref: ParsedImageRef? = null,
)

/**
 * Key a resolved image URL to a cache key: "<nodeHash>:<mediaPath>".
 * Mirrors upstream Browser.parse_url: a leading colon means "same node as
 * the current page"; "<hash>:<path>" is explicit; anything else is
 * malformed (null).
 */
fun resolveImageUrl(
    url: String,
    currentNodeHash: String,
): String? {
    val trimmed = url.trim()
    val colonIdx = trimmed.indexOf(':')
    if (colonIdx < 0) return null
    val head = trimmed.substring(0, colonIdx)
    val path = trimmed.substring(colonIdx + 1)
    if (path.isEmpty()) return null
    return when {
        head.isEmpty() && currentNodeHash.isNotEmpty() -> "$currentNodeHash:$path"
        head.length == 32 && head.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } ->
            "${head.lowercase()}:$path"
        else -> null
    }
}

/** Stable per-page key for an image element (URL + size specs distinguish). */
fun pageImageKey(
    url: String,
    width: String?,
    height: String?,
): String = "$url#$width|$height"

/** Small thread-safe state map the fetch loop and the UI both touch. */
class PageImageStates {
    private val map = ConcurrentHashMap<String, PageImageState>()

    fun snapshot(): Map<String, PageImageState> = HashMap(map)

    fun get(key: String): PageImageState? = map[key]

    fun put(
        key: String,
        state: PageImageState,
    ) {
        map[key] = state
    }

    fun update(
        key: String,
        transform: (PageImageState) -> PageImageState,
    ) {
        map.compute(key) { _, existing -> if (existing == null) null else transform(existing) }
    }

    fun clear() = map.clear()
}
