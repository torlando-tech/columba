package network.columba.app.nomadnet

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsNomadnet
import java.io.File

/**
 * Orchestrates page-image loading for one browser session, mirroring
 * upstream Browser.py's image updater (commits c0091db / 0db7d4b):
 *
 * - Parse pass ([scan]) registers every image element on the page; cache hits
 *   render instantly with zero requests (upstream `resolve_image`).
 * - Auto mode gates the queue on link RTT/EDR ([NomadNetImagePolicy]).
 * - Manual mode leaves placeholders until an explicit [loadImages] (upstream
 *   Ctrl+L) or per-image tap; forceReload drops caches first (Ctrl+X).
 * - Sequential single-flight fetching: one image at a time, so images never
 *   starve page traffic; cancelable via [cancelAll] / epoch bumps.
 * - WebP-only rule enforced at the cache boundary (7bd3a3a); server-side
 *   `.allowed` denials surface as DENIED, not FAILED.
 *
 * Size cap ([NomadNetImagePolicy.MAX_IMAGE_BYTES]) is enforced client-side in
 * [fetchOne] (the backends stage whatever the link delivers, so the app layer
 * is the enforcement point that holds for both of them).
 */
class PageImageLoader(
    private val nomadnet: RnsNomadnet,
    private val cache: NomadNetImageCache,
    private val scope: CoroutineScope,
    private val currentNodeHash: () -> String,
    private val imageLoadingMode: () -> ImageLoadingMode,
    /** Fallback EDR: speed of the last page response, bits/s. */
    private val lastResponseSpeedBps: () -> Double?,
) {
    private companion object {
        const val TAG = "PageImageLoader"
        const val MEDIA_TIMEOUT_SECONDS = 60f
    }

    private val states = PageImageStates()

    private val _snapshot = MutableStateFlow<Map<String, PageImageState>>(emptyMap())

    /** UI-observable image states, keyed by [pageImageKey]. */
    val imageStates: StateFlow<Map<String, PageImageState>> = _snapshot.asStateFlow()

    @Volatile
    private var queueJob: Job? = null

    @Volatile
    private var epoch = 0

    /**
     * Register the page's images after a parse. [refs] come from the page's
     * Image elements via [imageRefFor]. Cache hits go straight to LOADED;
     * everything else waits as PLACEHOLDER. Auto mode starts the queue when
     * the bandwidth gate passes; manual/never stay idle.
     */
    fun scan(
        refs: List<ParsedImageRef>,
        forceReload: Boolean = false,
    ) {
        val myEpoch = ++epoch
        queueJob?.cancel()
        states.clear()

        var needsQueue = false
        for (ref in refs) {
            val resolved = resolveImageUrl(ref.url, currentNodeHash())
            if (resolved == null) {
                // Structurally invalid URL: it can never resolve to a fetch, so
                // mark it MALFORMED (not PLACEHOLDER) - the UI renders the
                // error without a tap-to-retry affordance, and retryImage is a
                // no-op for it. A PLACEHOLDER here would advertise "tap to load"
                // on a reference that is permanently unfetchable (issue 8).
                states.put(
                    ref.key,
                    PageImageState(
                        ref = ref,
                        status = PageImageStatus.MALFORMED,
                        error = "Malformed image URL: ${ref.url}",
                    ),
                )
                continue
            }
            val resolvedRef = ref.withResolved(resolved)
            if (forceReload) cache.remove(resolved)
            val cached = if (forceReload) null else cache.get(resolved)
            if (cached != null) {
                states.put(ref.key, PageImageState(ref = resolvedRef, status = PageImageStatus.LOADED, file = cached))
            } else {
                states.put(ref.key, PageImageState(ref = resolvedRef))
                needsQueue = true
            }
        }
        publish()
        if (!needsQueue) return
        // The queue itself decides by mode + auto gate (the gate needs a
        // suspend call for link stats, so the decision lives in the coroutine).
        startQueue(myEpoch)
    }

    /** Explicit load (upstream Ctrl+L / placeholder tap). Bypasses the auto gate. */
    fun loadImages(forceReload: Boolean = false) {
        if (imageLoadingMode() == ImageLoadingMode.NEVER) return
        if (forceReload) {
            // Upstream Ctrl+X: drop caches for this page's images and re-queue
            // them as placeholders so they re-download.
            dropCachesAndReset()
            publish()
        }
        startQueueForExplicitLoad(epoch)
    }

    /** Ctrl+X step: evict each image's cache entry and reset it to placeholder. */
    private fun dropCachesAndReset() {
        for ((key, state) in states.snapshot()) {
            val ref = state.ref
            val resolved = ref?.let { resolveImageUrl(it.url, currentNodeHash()) }
            if (ref == null || resolved == null) continue
            cache.remove(resolved)
            states.put(key, PageImageState(ref = ref.withResolved(resolved)))
        }
    }

    /**
     * Retry/reload one image only (per-image tap on a placeholder, or reload on
     * a failed/denied one). Resets just that key to a fresh placeholder and
     * fetches only it - unlike [loadImages], which loads every image on the
     * page. Tapping a placeholder in manual mode must not drag the sibling
     * placeholders along with it.
     */
    fun retryImage(key: String) {
        if (imageLoadingMode() == ImageLoadingMode.NEVER) return
        val state = states.get(key)
        val ref = state?.ref
        // A malformed reference is structurally unfetchable - retrying it just
        // resets it to a placeholder and fetchOne returns (no node hash), so it
        // would sit as a permanently tappable no-op. DENIED and FAILED stay
        // retryable: the node may allow it later, or a transient fetch may
        // succeed on retry.
        if (state == null || state.status == PageImageStatus.MALFORMED || ref == null) return
        states.put(key, PageImageState(ref = ref))
        publish()
        startQueueForSingle(key, epoch)
    }

    fun cancelAll() {
        epoch++
        queueJob?.cancel()
        queueJob = null
    }

    fun clear() {
        cancelAll()
        states.clear()
        publish()
    }

    /**
     * Apply a loading-mode transition to the active loader (issue 5). Called
     * when the user selects a new [ImageLoadingMode] for a page that is
     * already displayed: a mode change must take effect on the current page,
     * not only on the next navigation.
     *
     * Every transition first cancels any running queue and resets in-flight
     * LOADING states back to placeholders, so no queue left over from a
     * previous mode keeps fetching under the new mode, and an abandoned
     * fetch does not sit in a stale "loading" state. The transition is then
     * re-derived from scratch:
     *
     * - ALWAYS: start an explicit load of every registered placeholder (the
     *   user just asked for all images to load).
     * - AUTO: re-evaluate the bandwidth gate for the registered placeholders.
     * - MANUAL: leave placeholders as-is (a manual user loads on demand).
     * - NEVER: leave placeholders as-is; nothing auto-fetches, so tapping is
     *   blocked and the queue simply does not start.
     *
     * Already-LOADED / DENIED / FAILED / MALFORMED states are untouched, so
     * switching modes never discards an already-rendered image.
     */
    fun applyMode(mode: ImageLoadingMode) {
        // Clean transition: stop any queue from the previous mode and reset
        // in-flight fetches so the new mode re-evaluates them.
        cancelAll()
        resetLoadingStates()
        publish()
        when (mode) {
            ImageLoadingMode.ALWAYS -> startQueueForExplicitLoad(epoch)
            ImageLoadingMode.AUTO -> startQueue(epoch)
            ImageLoadingMode.MANUAL -> Unit
            ImageLoadingMode.NEVER -> Unit
        }
    }

    /**
     * Reset any LOADING image states back to placeholders. Called after a
     * queue cancel so an abandoned fetch is re-evaluated by the new mode
     * instead of hanging in a perpetual "loading" state (the cancelled
     * [fetchOne] leaves its result unapplied because the epoch no longer
     * matches).
     */
    private fun resetLoadingStates() {
        for ((key, state) in states.snapshot()) {
            if (state.status == PageImageStatus.LOADING) {
                states.put(key, PageImageState(ref = state.ref))
            }
        }
    }

    private suspend fun gatePasses(nodeHash: String): Boolean {
        val stats = runCatching { nomadnet.getNomadnetLinkStats(nodeHash) }.getOrNull()
        return NomadNetImagePolicy.shouldLoadAutomatically(
            mode = imageLoadingMode(),
            // No loopback nodes in Columba's browsing model.
            isLoopback = false,
            rttSeconds = stats?.rttSeconds,
            expectedRateBps = stats?.expectedRateBps,
            measuredResponseSpeedBps = lastResponseSpeedBps(),
        )
    }

    private fun startQueue(myEpoch: Int) = startQueue(myEpoch, respectModeAndGate = true, onlyKey = null)

    /** Explicit (user-triggered) queue run: skips the mode/gate check but still honors NEVER. */
    private fun startQueueForExplicitLoad(myEpoch: Int) = startQueue(myEpoch, respectModeAndGate = false, onlyKey = null)

    /**
     * Fetch a single image (per-image tap/reload). Explicit, so it bypasses the
     * mode gate (still honors NEVER), and is restricted to [onlyKey] so sibling
     * placeholders are left untouched.
     */
    private fun startQueueForSingle(onlyKey: String, myEpoch: Int) =
        startQueue(myEpoch, respectModeAndGate = false, onlyKey = onlyKey)

    private fun startQueue(
        myEpoch: Int,
        respectModeAndGate: Boolean,
        onlyKey: String?,
    ) {
        queueJob?.cancel()
        queueJob =
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // Mode decision happens inside the coroutine (the gate needs a
                // suspend call). Explicit user loads skip the gate but NEVER
                // mode still blocks everything.
                if (imageLoadingMode() == ImageLoadingMode.NEVER) return@launch
                if (respectModeAndGate && imageLoadingMode() == ImageLoadingMode.MANUAL) return@launch
                // Snapshot keys once; a new scan bumps the epoch and restarts.
                val keys = (states.snapshot().keys.toList()).filter { onlyKey == null || it == onlyKey }
                for (key in keys) {
                    // processKey returns false when the epoch changed mid-loop
                    // (a new scan superseded this run), which stops the queue.
                    if (!processKey(key, myEpoch, respectModeAndGate)) return@launch
                }
            }
    }

    /**
     * Fetch a single registered image if it is a placeholder and passes the
     * per-image auto gate; no-op otherwise. Returns [false] only when the
     * epoch changed (a newer scan superseded this run), signalling the queue
     * loop to stop; [true] when this key was processed (fetched or
     * intentionally skipped) and the loop should continue.
     *
     * The per-image auto gate (issue 6) evaluates the RTT/EDR policy against
     * THIS image's own destination node, not the current page's node once for
     * the whole queue. A page can reference cross-node images (explicit
     * `<hash>:` path) that ride a different - possibly slow - link than the
     * page itself; gating the whole queue on the page node would authorize a
     * fast page's link to download from a slow destination, or block a fast
     * destination because the page node is slow.
     */
    private suspend fun processKey(
        key: String,
        myEpoch: Int,
        respectModeAndGate: Boolean,
    ): Boolean {
        if (epoch != myEpoch) return false
        val state = states.get(key)
        val placeholder = state != null && state.status == PageImageStatus.PLACEHOLDER
        // Auto-gate (issue 6): evaluate the RTT/EDR policy against THIS image's
        // own destination node. A null node hash means the reference never
        // resolved, so it cannot pass the gate (no fetch). Explicit loads
        // (respectModeAndGate = false) skip the gate entirely.
        val gateOk =
            !respectModeAndGate ||
                imageLoadingMode() != ImageLoadingMode.AUTO ||
                (state?.ref?.nodeHash?.let { gatePasses(it) } == true)
        if (placeholder && gateOk) {
            fetchOne(key, myEpoch)
        }
        return epoch == myEpoch
    }

    private suspend fun fetchOne(
        key: String,
        myEpoch: Int,
    ) {
        val state = states.get(key)
        val ref = state?.ref
        val hash = ref?.nodeHash
        if (state == null || ref == null || hash == null) return
        states.update(key) { it.copy(status = PageImageStatus.LOADING) }
        publish()

        val result =
            runCatching {
                // Pass the transfer cap to the backend so it refuses an
                // oversized payload at the response boundary (before staging to
                // disk), instead of the app layer deleting it after the fact.
                // The client-side check below remains as a belt-and-suspenders
                // guard for backends that predate the cap (or tests that stub
                // the interface without it).
                val media =
                    nomadnet.requestNomadnetMedia(hash, ref.mediaPath, MEDIA_TIMEOUT_SECONDS, NomadNetImagePolicy.MAX_IMAGE_BYTES)
                        .getOrThrow()
                if (media.fileSize > NomadNetImagePolicy.MAX_IMAGE_BYTES) {
                    runCatching { java.io.File(media.filePath).delete() }
                    error("Image too large (${media.fileSize} bytes; cap ${NomadNetImagePolicy.MAX_IMAGE_BYTES})")
                }
                media
            }

        if (epoch != myEpoch) {
            // Stale (page changed mid-flight): drop the staged file if any and
            // leave the state for the new scan to own.
            result.getOrNull()?.let { runCatching { File(it.filePath).delete() } }
        } else {
            result.fold(
                onSuccess = { media ->
                    val cached = cache.put("$hash:${ref.mediaPath}", File(media.filePath))
                    if (cached != null) {
                        states.update(key) {
                            it.copy(
                                status = PageImageStatus.LOADED,
                                file = cached,
                                progress = 1f,
                                totalBytes = media.fileSize,
                                receivedBytes = media.fileSize,
                            )
                        }
                    } else {
                        states.update(key) {
                            it.copy(status = PageImageStatus.FAILED, error = "Not a WebP image (rejected)")
                        }
                    }
                },
                onFailure = { error ->
                    val denied = (error as? RnsException)?.error is RnsError.NomadnetRequestDenied
                    Log.d(TAG, "Image $key ${if (denied) "denied by node" else "failed"}: ${error.message}")
                    states.update(key) {
                        it.copy(
                            status = if (denied) PageImageStatus.DENIED else PageImageStatus.FAILED,
                            error = error.message,
                        )
                    }
                },
            )
            publish()
        }
    }

    private fun publish() {
        _snapshot.value = states.snapshot()
    }
}

/** One image element on a page, reduced to what the loader needs. */
data class ParsedImageRef(
    val url: String,
    val key: String,
    /** Filled by the scan pass from [resolveImageUrl]; null = unresolved. */
    val nodeHash: String? = null,
    val mediaPath: String = "",
)

private fun ParsedImageRef.withResolved(resolved: String): ParsedImageRef {
    val idx = resolved.indexOf(':')
    return copy(
        nodeHash = resolved.substring(0, idx),
        mediaPath = resolved.substring(idx + 1),
    )
}
