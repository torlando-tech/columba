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
 * Size cap ([NomadNetImagePolicy.MAX_IMAGE_BYTES]) is applied by the backend
 * media request itself (the staged body is bounded by the link's response
 * handling); a client-side pre-abort needs receipt-size plumbing on the seam
 * and is a deliberate follow-up — see plan doc.
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
                states.put(
                    ref.key,
                    PageImageState(ref = ref, error = "Malformed image URL: ${ref.url}"),
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
            for ((key, state) in states.snapshot()) {
                val ref = state.ref ?: continue
                val resolved = resolveImageUrl(ref.url, currentNodeHash()) ?: continue
                cache.remove(resolved)
                states.put(key, PageImageState(ref = ref.withResolved(resolved)))
            }
            publish()
        }
        startQueueForExplicitLoad(epoch)
    }

    /** Retry one failed/denied/placeholder image (per-image reload affordance). */
    fun retryImage(key: String) {
        if (imageLoadingMode() == ImageLoadingMode.NEVER) return
        val state = states.get(key) ?: return
        val ref = state.ref ?: return
        states.put(key, PageImageState(ref = ref))
        publish()
        startQueueForExplicitLoad(epoch)
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

    private suspend fun gatePasses(): Boolean {
        val hash = currentNodeHash()
        val stats = runCatching { nomadnet.getNomadnetLinkStats(hash) }.getOrNull()
        return NomadNetImagePolicy.shouldLoadAutomatically(
            mode = imageLoadingMode(),
            // No loopback nodes in Columba's browsing model.
            isLoopback = false,
            rttSeconds = stats?.rttSeconds,
            expectedRateBps = stats?.expectedRateBps,
            measuredResponseSpeedBps = lastResponseSpeedBps(),
        )
    }

    private fun startQueue(myEpoch: Int) = startQueue(myEpoch, respectModeAndGate = true)

    /** Explicit (user-triggered) queue run: skips the mode/gate check but still honors NEVER. */
    private fun startQueueForExplicitLoad(myEpoch: Int) = startQueue(myEpoch, respectModeAndGate = false)

    private fun startQueue(
        myEpoch: Int,
        respectModeAndGate: Boolean,
    ) {
        queueJob?.cancel()
        queueJob =
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                // Mode + auto-gate decision happens inside the coroutine
                // (the gate needs a suspend call). Explicit user loads skip
                // the gate but NEVER mode still blocks everything.
                if (imageLoadingMode() == ImageLoadingMode.NEVER) return@launch
                if (respectModeAndGate) {
                    when (imageLoadingMode()) {
                        ImageLoadingMode.MANUAL -> return@launch
                        ImageLoadingMode.ALWAYS -> Unit
                        ImageLoadingMode.AUTO ->
                            if (!gatePasses()) return@launch
                        ImageLoadingMode.NEVER -> return@launch
                    }
                }
                // Snapshot keys once; a new scan bumps the epoch and restarts.
                val keys = states.snapshot().keys.toList()
                for (key in keys) {
                    if (epoch != myEpoch) return@launch
                    val state = states.get(key) ?: continue
                    if (state.status != PageImageStatus.PLACEHOLDER) continue
                    fetchOne(key, myEpoch)
                }
            }
    }

    private suspend fun fetchOne(
        key: String,
        myEpoch: Int,
    ) {
        val state = states.get(key) ?: return
        val ref = state.ref ?: return
        val hash = ref.nodeHash ?: return
        states.update(key) { it.copy(status = PageImageStatus.LOADING) }
        publish()

        val result =
            runCatching {
                nomadnet.requestNomadnetMedia(hash, ref.mediaPath, MEDIA_TIMEOUT_SECONDS).getOrThrow()
            }

        if (epoch != myEpoch) {
            // Stale: drop the staged file if any.
            result.getOrNull()?.let { runCatching { File(it.filePath).delete() } }
            return
        }

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
