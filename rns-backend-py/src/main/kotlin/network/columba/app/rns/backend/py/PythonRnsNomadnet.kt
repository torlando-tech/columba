package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NomadnetLinkStats
import network.columba.app.rns.api.model.NomadnetMediaResult
import network.columba.app.rns.api.model.NomadnetPageResult
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * `RnsNomadnet` over upstream Python RNS, driven through Chaquopy.
 *
 * Mirrors the choreography of `:rns-backend-kt`'s `NativeNomadNetHandler`:
 * resolve the NomadNet node identity (recall, else `Transport.request_path`
 * and poll) -> build the `nomadnetwork.node` `RNS.Destination` -> establish an
 * `RNS.Link` -> `link.request(path, data, ...)` -> await the `RequestReceipt`.
 *
 * Upstream API shape (RNS 1.2.5, stable):
 *  - `RNS.Link(destination, established_callback=, closed_callback=)` —
 *    constructor kicks off the handshake; `link.status` advances PENDING(0) ->
 *    HANDSHAKE(1) -> ACTIVE(2).
 *  - `RNS.Link.request(path, data=None, response_callback=, failed_callback=,
 *    progress_callback=, timeout=)` -> a `RequestReceipt` or `False`.
 *  - `RequestReceipt` carries `.status` (FAILED 0 / SENT 1 / DELIVERED 2 /
 *    RECEIVING 3 / READY 4), `.response` (bytes once READY), `.metadata`
 *    (msgpacked file metadata for `/file/` responses), `.progress` (0.0-1.0).
 *
 * Honest-stub markers: the upstream request callbacks are Python callables
 * invoked on RNS internal threads. Bridging them into Kotlin lambdas via
 * Chaquopy is the kind of cross-language async glue that needs on-device
 * iteration, so this impl instead **polls** the `RequestReceipt` (the same
 * fields the callbacks would expose) on a short cadence — degraded but honest,
 * and entirely deterministic.
 */
class PythonRnsNomadnet(
    private val runtime: PythonRnsRuntime,
) : RnsNomadnet {
    private companion object {
        const val TAG = "PythonRnsNomadnet"

        /** `RNS.Link` status constants (RNS/Link.py). */
        const val LINK_ACTIVE = 2L

        /** `RNS.RequestReceipt` status constants (RNS/Link.py). */
        const val RECEIPT_FAILED = 0L
        const val RECEIPT_READY = 4L

        const val DEFAULT_PATH = "/page/index.mu"

        /** Poll cadence for link establishment / receipt progress. */
        const val POLL_INTERVAL_MS = 250L
    }

    private val _nomadnetRequestStatusFlow = MutableStateFlow("idle")
    override val nomadnetRequestStatusFlow: StateFlow<String> =
        _nomadnetRequestStatusFlow.asStateFlow()

    private val _nomadnetDownloadProgressFlow = MutableStateFlow(0f)
    override val nomadnetDownloadProgressFlow: StateFlow<Float> =
        _nomadnetDownloadProgressFlow.asStateFlow()

    /** hex destination hash -> live `RNS.Link` to that NomadNet node. */
    // internal (not private) so the JVM link-lifecycle tests in this module
    // can seed/inspect the cached-link contract with a raw
    // `PyObject.getInstance(...)` handle (no native RNS runtime needed).
    internal val nomadnetLinks = ConcurrentHashMap<String, PyObject>()

    /**
     * Per-node auto-identify flags, mirroring upstream
     * `Directory.should_identify_on_connect`. The backend identifies a
     * node's link at establishment time (the equivalent of upstream's
     * `link_established` callback) when the destination is in this set,
     * so no separate UI identify call is needed for flagged nodes.
     * Updated by the ViewModel via [setIdentifyOnConnectNodes].
     */
    @Volatile
    internal var identifyOnConnectNodes: Set<String> = emptySet()

    override suspend fun setIdentifyOnConnectNodes(nodes: Set<String>) {
        val previous = identifyOnConnectNodes
        identifyOnConnectNodes = nodes
        // Identify newly-flagged nodes whose link is ALREADY active. Covers:
        // (a) the user toggling "Always identify to this node" in Node Details
        // while that node's page is open in the browser (the link was
        // established before the flag was set), and (b) the narrow first-launch
        // window where a flagged node's page was served from cache before the
        // DataStore set arrived. identifyIfFlagged is a no-op for nodes that
        // are not in the set or whose link is not active, and dedups via
        // identifiedLinks, so calling it for every newly-added node is safe.
        (nodes - previous).forEach { hash ->
            nomadnetLinks[hash]?.let { link ->
                if ((testLinkStatus?.invoke(link) ?: linkStatus(link)) == LINK_ACTIVE) {
                    identifyIfFlagged(hash, link)
                }
            }
        }
        // Tear down newly-unflagged nodes' ACTIVE links. An identified link
        // carries its proof for the link's lifetime, so unflagging without
        // tearing it down would keep the user browsing as identified - the
        // natural expectation is that further actions are NOT associated with
        // the identity. Drop the link (and its dedup key) so the next
        // refresh/navigation establishes a fresh, anonymous link. Deliberately
        // does NOT re-establish: the re-establishment happens lazily on the
        // user's next page action, paying a fresh handshake only when needed.
        // This is the inverse of the newly-flagged path above and intentionally
        // goes beyond upstream (whose ident_change is a no-op) for intuitive UX.
        (previous - nodes).forEach { hash ->
            nomadnetLinks[hash]?.let { link ->
                if ((testLinkStatus?.invoke(link) ?: linkStatus(link)) == LINK_ACTIVE) {
                    identifiedLinks.remove(linkIdHex(link, hash, testLinkIdHex))
                    teardownLink(link)
                    nomadnetLinks.remove(hash)
                    Log.i(TAG, "NomadNet: tore down link to $hash on unflag (anonymous on next visit)")
                }
            }
        }
    }

    /**
     * Link identities already identified, keyed by the RNS link's `link_id`
     * hex. Keyed by link (not destination) so a reused link doesn't
     * re-send the proof, but a rebuilt link for the same destination does
     * (a fresh link = fresh handshake = fresh identification). Mirrors the
     * Kotlin backend's `identifiedNomadnetLinks` (also link-id keyed).
     */
    internal val identifiedLinks: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Request-scoped cancellation. Each page/media request claims the next
     * generation ([requestGeneration]); a request is considered cancelled if its
     * generation is no longer the latest (a replacement request began) OR
     * [cancelGeneration] matches it (an explicit [cancelNomadnetPageRequest]
     * targeted the then-current request). This fixes the old shared-Boolean
     * bug where a replacement request resetting the flag would let the still
     * winding-down old request keep running on the retained link, and a cancel
     * cannot leak into a later, unrelated user request.
     */
    private val requestGeneration = java.util.concurrent.atomic.AtomicInteger(0)

    /** The generation an explicit [cancelNomadnetPageRequest] targeted (0 = none). */
    @Volatile
    private var cancelGeneration = 0

    /**
     * Claim the next request generation. Called once per [requestNomadnetPage]
     * / [requestNomadnetMedia]. A brand-new request is never cancelled: it is
     * the latest generation, so the supersession check in [isCancelled] is
     * false for it, and any pending [cancelGeneration] (which targeted an older
     * request) no longer matches. Deliberately does NOT clear [cancelGeneration]
     * - the cancel belongs to the request the user cancelled, not the one that
     * replaces it.
     */
    private fun beginRequest(): Int =
        requestGeneration.incrementAndGet()

    /**
     * Test seam: begin a request generation exactly as [requestNomadnetPage] /
     * [requestNomadnetMedia] do, without a live RNS runtime, so the
     * generation/cancel contract can be probed directly. Mirrors [beginRequest].
     */
    internal fun testBeginRequest(): Int = beginRequest()

    /**
     * Test seam: expose the generation contract without a live RNS runtime.
     * Mirrors [nomadnetLinks] being `internal`. Returns the current latest
     * generation (what the next [beginRequest] will claim is not; this is the
     * last claimed one) and the generation the last cancel targeted.
     */
    internal val testCancelState: Pair<Int, Int>
        get() = requestGeneration.get() to cancelGeneration

    /** Test seam: the [isCancelled] predicate for direct contract tests. */
    internal fun testIsCancelled(generation: Int): Boolean = isCancelled(generation)

    // ==================== Page / file requests ====================

    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<NomadnetPageResult> =
        pyResult {
            runtime.requireRunning()
            val generation = beginRequest()
            _nomadnetRequestStatusFlow.value = "requesting"
            _nomadnetDownloadProgressFlow.value = 0f
            // One request-level deadline shared by every phase (identity,
            // link establishment incl. the route retry, and the response
            // wait). Each phase caps its own budget by the time left, so the
            // whole request is bounded by `timeoutSeconds` - the documented
            // hard round-trip deadline - rather than the sum of per-phase
            // budgets (which a cold-start route retry could otherwise push to
            // ~2.3x `timeoutSeconds`).
            val requestDeadlineMs = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()

            val safePath = if (path.isBlank() || !path.startsWith("/")) DEFAULT_PATH else path
            val destBytes = destinationHash.hexToBytes()

            try {
                val nodeIdentity = resolveNodeIdentity(
                    destinationHash, destBytes, timeoutSeconds, generation, requestDeadlineMs,
                )
                val link = establishLink(
                    destinationHash, nodeIdentity, timeoutSeconds, generation, requestDeadlineMs,
                )
                val handle = sendPageRequest(link, safePath, formDataJson, timeoutSeconds)
                val result = awaitResponse(handle, safePath, timeoutSeconds, generation, requestDeadlineMs)
                _nomadnetRequestStatusFlow.value = "complete"
                _nomadnetDownloadProgressFlow.value = 1f
                result
            } catch (e: Throwable) {
                _nomadnetRequestStatusFlow.value = if (isCancelled(generation)) "idle" else "failed"
                throw e
            }
        }

    /**
     * Recall the NomadNet node's `RNS.Identity`; if unknown, ask Transport for
     * a path and poll the identity cache until it resolves or the lookup
     * window closes.
     */
    private suspend fun resolveNodeIdentity(
        destinationHash: String,
        destBytes: ByteArray,
        timeoutSeconds: Float,
        generation: Int,
        requestDeadlineMs: Long,
    ): PyObject {
        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        identityClass.callAttr("recall", destBytes.toPyBytes())?.let { return it }

        Log.i(TAG, "NomadNet: identity for $destinationHash unknown, requesting path")
        _nomadnetRequestStatusFlow.value = "requesting"
        transport().callAttr("request_path", destBytes.toPyBytes())

        // Path lookup gets up to a third of the budget (min 15s), mirroring
        // NativeNomadNetHandler, and is further capped by the request-level
        // deadline so the whole request stays within `timeoutSeconds`.
        val deadline = (System.currentTimeMillis() +
            (timeoutSeconds * 1000 / 3).toLong().coerceAtLeast(15_000L))
            .coerceAtMost(requestDeadlineMs)
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled(generation)
            delay(POLL_INTERVAL_MS)
            identityClass.callAttr("recall", destBytes.toPyBytes())?.let { return it }
        }
        throw RnsException(
            RnsError.Generic(
                "NomadNet node ${destinationHash.take(16)} not found after path request — node may be offline",
                null,
            ),
        )
    }

    /**
     * Reuse a cached ACTIVE link, else build the `nomadnetwork.node`
     * destination, construct an `RNS.Link`, and poll until it goes ACTIVE.
     *
     * If the first link budget exhausts without the link going ACTIVE, the
     * destination may not have a route in `RNS.Transport.path_table` yet
     * (cold start: the identity is known from the stored destinations, so
     * `resolveNodeIdentity` returned without requesting a path). The route
     * will arrive via peer announces over the backbone, but only after the
     * link budget has expired. This mirrors the kotlin backend's
     * `NativeNomadNetHandler.retryLinkEstablishment`: expire the (absent)
     * path, request a fresh one, poll `Transport.has_path`, then rebuild
     * the link.
     */
    internal suspend fun establishLink(
        destinationHash: String,
        nodeIdentity: PyObject,
        timeoutSeconds: Float,
        generation: Int,
        requestDeadlineMs: Long,
    ): PyObject {
        nomadnetLinks[destinationHash]?.let { existing ->
            if ((testLinkStatus?.invoke(existing) ?: linkStatus(existing)) == LINK_ACTIVE) {
                Log.i(TAG, "NomadNet: reusing active link to $destinationHash")
                identifyIfFlagged(destinationHash, existing)
                return existing
            }
            nomadnetLinks.remove(destinationHash)
        }

        _nomadnetRequestStatusFlow.value = "requesting"
        val destBytes = destinationHash.hexToBytes()
        // All link-establishment phases (first attempt, route wait, retry) cap
        // their budgets by the time left against the request-level deadline,
        // so the route retry can never extend the request past
        // `timeoutSeconds` even after identity resolution consumed time.
        val linkBudgetMs = testLinkBudgetMs?.toLong()
            ?: (timeoutSeconds * 1000 / 3).toLong().coerceAtLeast(5_000L)
        val routeWaitMs = testPathWaitBudgetMs?.toLong() ?: 20_000L

        // --- first attempt ---
        val firstLink = createNodeLink(nodeIdentity)
        val firstResult = awaitLinkActive(
            firstLink,
            linkBudgetMs.coerceAtMost(requestDeadlineMs - System.currentTimeMillis()),
            generation,
        )
        if (firstResult != null) {
            nomadnetLinks[destinationHash] = firstResult
            Log.i(TAG, "NomadNet: link established to $destinationHash")
            identifyIfFlagged(destinationHash, firstResult)
            return firstResult
        }

        // --- retry: wait for the route to appear, then rebuild the link ---
        Log.i(TAG, "NomadNet: expiring stale path, requesting fresh path and retrying...")
        val routeAppeared = waitForRouteAppears(
            destBytes,
            routeWaitMs.coerceAtMost(requestDeadlineMs - System.currentTimeMillis()),
            generation,
        )
        val failure = RnsException(
            RnsError.Generic("Failed to establish link to NomadNet node ${destinationHash.take(16)}", null),
        )
        if (!routeAppeared) {
            throw failure
        }

        val retryLink = createNodeLink(nodeIdentity)
        val retryResult = awaitLinkActive(
            retryLink,
            linkBudgetMs.coerceAtMost(requestDeadlineMs - System.currentTimeMillis()),
            generation,
        )
        if (retryResult != null) {
            nomadnetLinks[destinationHash] = retryResult
            Log.i(TAG, "NomadNet: link established on retry to $destinationHash")
            identifyIfFlagged(destinationHash, retryResult)
            return retryResult
        }
        throw failure
    }

    /**
     * Build the `nomadnetwork.node` destination and construct an `RNS.Link`.
     * Overridable via [testCreateNodeLink] for unit tests.
     */
    private fun createNodeLink(nodeIdentity: PyObject): PyObject {
        testCreateNodeLink?.let { return it(nodeIdentity) }
        val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
        val nodeDest = runtime.rnsModule.callAttr(
            "Destination",
            nodeIdentity,
            destClass["OUT"] ?: error("Destination.OUT missing"),
            destClass["SINGLE"] ?: error("Destination.SINGLE missing"),
            "nomadnetwork",
            "node",
        )
        return runtime.rnsModule.callAttr("Link", nodeDest)
    }

    /** Tear down a link via the test seam or the real `RNS.Link.teardown`. */
    private fun teardownLink(link: PyObject) {
        val seam = testTeardownLink
        if (seam != null) seam(link) else runCatching { link.callAttr("teardown") }
    }

    /**
     * Poll `RNS.Transport.has_path(dest)` until it returns true or the
     * budget expires. Sends `expire_path` + `request_path` first to kick
     * off route discovery.
     *
     * Mirrors `NativeNomadNetHandler.retryLinkEstablishment`'s path-wait.
     * The transport calls and the `has_path` read are individually
     * overridable via seams so unit tests can verify the real ordering
     * (expire, request, poll) and a delayed `has_path` transition without a
     * native RNS runtime.
     */
    private suspend fun waitForRouteAppears(
        destBytes: ByteArray,
        budgetMs: Long,
        generation: Int,
    ): Boolean {
        val invoke = { op: String ->
            val seam = testTransportInvoke
            if (seam != null) seam(op, destBytes)
            else runCatching { transport().callAttr(op, destBytes.toPyBytes()) }
        }
        val pathKnown = {
            val seam = testHasPath
            seam?.invoke(destBytes) ?: runCatching {
                transport().callAttr("has_path", destBytes.toPyBytes())
                    ?.toJava(Boolean::class.javaObjectType)
            }.getOrNull() ?: false
        }
        invoke("expire_path")
        invoke("request_path")
        val deadline = System.currentTimeMillis() + budgetMs
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled(generation)
            if (pathKnown()) return true
            delay(POLL_INTERVAL_MS)
        }
        return pathKnown()
    }

    /**
     * Poll a link until it goes ACTIVE or the budget expires.
     * Returns the link if ACTIVE; otherwise tears the link down and returns
     * null. The `finally` tears the link down on every non-success exit -
     * budget exhaustion, a generation `RnsException`, and a coroutine
     * `CancellationException` thrown out of `delay` when the owning scope is
     * cancelled (the ViewModel-cleared path) - so no orphaned link keeps
     * handshaking after the request has ended.
     */
    private suspend fun awaitLinkActive(
        link: PyObject,
        budgetMs: Long,
        generation: Int,
    ): PyObject? {
        var established: PyObject? = null
        try {
            val deadline = System.currentTimeMillis() + budgetMs
            while (System.currentTimeMillis() < deadline) {
                throwIfCancelled(generation)
                if ((testLinkStatus?.invoke(link) ?: linkStatus(link)) == LINK_ACTIVE) {
                    established = link
                    return link
                }
                delay(POLL_INTERVAL_MS)
            }
            return null
        } finally {
            if (established === null) teardownLink(link)
        }
    }

    // Test seams: override in unit tests to avoid the native RNS runtime.
    // Each is `null` in production (the real RNS code path is used).
    internal var testLinkBudgetMs: Int? = null
    internal var testPathWaitBudgetMs: Int? = null
    internal var testCreateNodeLink: ((PyObject) -> PyObject)? = null
    internal var testLinkStatus: ((PyObject) -> Long?)? = null
    internal var testTransportInvoke: ((String, ByteArray) -> Unit)? = null
    internal var testHasPath: ((ByteArray) -> Boolean)? = null
    internal var testTeardownLink: ((PyObject) -> Unit)? = null
    // Seam for the at-establishment auto-identify (upstream link_established).
    // When set, identifyIfFlagged calls it instead of the real
    // link.callAttr("identify", identity) so the flag/dedup contract can be
    // probed without a native RNS runtime.
    internal var testIdentifyOnLink: ((String, PyObject) -> Unit)? = null
    // Seam for the link-identity dedup key. linkIdHex reads link.link_id via a
    // native call (unavailable on the JVM unit-test classpath, where a raw
    // handle throws UnsatisfiedLinkError - an Error, not an Exception, so
    // runCatching wouldn't catch it). When set, it returns a stable key derived
    // from the handle's identityHashCode so the link-id-keyed dedup can be
    // probed: same link -> same key (no re-identify), distinct links -> distinct
    // keys (re-identify), exactly like a real link_id.
    internal var testLinkIdHex: ((PyObject) -> String)? = null

    /**
     * Issue `link.request(path, data, ...)` and wire a Python-side capture
     * for the response. `data` is the optional form map for POST-style page
     * requests; NomadNet expects `field_*` / `var_*` keys.
     *
     * The returned [PageRequestHandle] bundles the upstream `RequestReceipt`
     * (for status polling) with the [event_bridge.NomadnetResponseCapture]
     * that snapshots `receipt.response` inside the response callback.
     * Capture is necessary because upstream `Resource.assemble` synchronously
     * closes + unlinks the temp file holding a file-response body the moment
     * the response callback returns — a delayed read from Kotlin's poll loop
     * sees `ValueError: I/O operation on closed file`.
     */
    private fun sendPageRequest(
        link: PyObject,
        safePath: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): PageRequestHandle {
        val requestData = parseFormData(formDataJson)
        _nomadnetRequestStatusFlow.value = "requesting"

        val capture = runtime.eventBridge.callAttr("make_nomadnet_response_capture")
        val responseCallback = capture["on_response"]
        val failedCallback = capture["on_failed"]

        // request(path, data, response_callback, failed_callback,
        // progress_callback, timeout). Both response and failed callbacks are
        // Python bound methods on the capture object — they run on RNS's
        // resource-assembly thread while the response file is still open.
        val receipt = link.callAttr(
            "request",
            safePath,
            requestData,
            responseCallback,
            failedCallback,
            null,
            timeoutSeconds.toDouble(),
        )
        if (receipt == null || receipt.toString() == "False") {
            throw RnsException(
                RnsError.Generic("NomadNet request for $safePath could not be sent", null),
            )
        }
        return PageRequestHandle(receipt, capture)
    }

    /** Pairs the `RequestReceipt` (for status polling) with the Python-side
     *  response capture that snapshots bytes before upstream closes them. */
    private data class PageRequestHandle(
        val receipt: PyObject,
        val capture: PyObject,
    )

    /**
     * Poll the [receipt] until it reaches READY (or FAILED / timeout),
     * threading `.progress` through [nomadnetDownloadProgressFlow], then build
     * the [NomadnetPageResult].
     */
    // Each failure mode (cancelled / timeout / FAILED status) throws a distinct
    // typed RnsException — collapsing them would lose the failure distinction.
    @Suppress("ThrowsCount")
    private suspend fun awaitResponse(
        handle: PageRequestHandle,
        safePath: String,
        timeoutSeconds: Float,
        generation: Int,
        requestDeadlineMs: Long,
    ): NomadnetPageResult {
        val (receipt, capture) = handle
        _nomadnetRequestStatusFlow.value = "receiving"
        val deadline = (System.currentTimeMillis() + (timeoutSeconds * 1000).toLong())
            .coerceAtMost(requestDeadlineMs)
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled(generation)

            val progress = receipt["progress"]?.toJava(Float::class.javaObjectType) ?: 0f
            _nomadnetDownloadProgressFlow.value = progress.coerceIn(0f, 1f)

            // The capture flips `done=True` from inside the Python-side
            // response (or failed) callback — earlier than the receipt
            // status field is observable here, and crucially *before*
            // upstream `Resource.assemble` closes the temp file backing
            // `receipt.response`.
            if (capture["done"]?.toJava(Boolean::class.javaObjectType) == true) {
                capture["error"]?.toString()?.takeIf { it.isNotEmpty() && it != "None" }?.let {
                    throw RnsException(
                        RnsError.Generic("NomadNet request for $safePath failed: $it", null),
                    )
                }
                val response = capture["response_bytes"]?.toJava(ByteArray::class.java)
                    ?: throw RnsException(
                        RnsError.Generic("NomadNet response READY but body was null", null),
                    )
                // Two values surfaced by the Python-side capture
                // (`event_bridge.make_nomadnet_response_capture`) instead of
                // the prior msgpack-roundtripped metadata blob:
                //   - hasMetadata: did upstream `RequestReceipt.metadata`
                //     come back non-None? This is the "/file/ response"
                //     discriminator — NomadNet only sets metadata on file
                //     responses, never on `/page/` micron responses.
                //   - metadataNameBytes: bytes for the metadata dict's
                //     `"name"` key (utf-8). Empty when the key is absent or
                //     this is a `/page/` response.
                // Upstream RNS lazy-unpacks `RequestReceipt.metadata` at the
                // property accessor, so the prior repack-then-decode loop
                // was pure overhead with no payoff.
                val hasMetadata = capture["has_metadata"]
                    ?.toJava(Boolean::class.javaObjectType) == true
                val metadataNameBytes = capture["metadata_name_bytes"]
                    ?.toJava(ByteArray::class.java)
                return buildPageResult(response, hasMetadata, metadataNameBytes, safePath)
            }

            // Defensive fallback: surface upstream-reported FAILED even if
            // the capture's `on_failed` somehow didn't fire (e.g. upstream
            // marked the receipt FAILED via a non-callback code path).
            if (receipt["status"]?.toJava(Long::class.javaObjectType) == RECEIPT_FAILED) {
                throw RnsException(
                    RnsError.Generic("NomadNet request for $safePath failed", null),
                )
            }
            delay(POLL_INTERVAL_MS)
        }
        throw RnsException(
            RnsError.Generic("NomadNet request for $safePath timed out", null),
        )
    }

    /**
     * A `/file/` response carries metadata (a dict with `{name, ...}`
     * unpacked Python-side by `event_bridge.make_nomadnet_response_capture`,
     * which forwards the [hasMetadata] discriminator and just the `"name"`
     * bytes via [metadataNameBytes]); the body is written to a download file.
     * A `/page/` response has no metadata ([hasMetadata] == false) and the
     * body is raw micron text.
     *
     * v0.10.x reference: `python/rns_api.py:_save_file_response` — name
     * defaults to `b"download"` when `"name"` is absent / empty / decodes
     * to blank; bytes decoded utf-8/errors="replace"; `os.path.basename` to
     * sanitize.
     */
    private fun buildPageResult(
        data: ByteArray,
        hasMetadata: Boolean,
        metadataNameBytes: ByteArray?,
        safePath: String,
    ): NomadnetPageResult {
        if (hasMetadata) {
            // `String(_, Charsets.UTF_8)` silently substitutes the Unicode
            // replacement char for malformed sequences — matches v0.10.x's
            // `errors="replace"` contract (python/rns_api.py:425).
            val advertised = if (metadataNameBytes != null && metadataNameBytes.isNotEmpty()) {
                String(metadataNameBytes, Charsets.UTF_8)
            } else {
                ""
            }
            // Strip any directory components from the server-advertised name
            // (path-traversal sanitize, Kotlin equivalent of
            // `os.path.basename`). Fall through to "download" when blank.
            val fileName = File(advertised).name.ifBlank { "download" }
            val downloadDir = File(
                System.getProperty("java.io.tmpdir") ?: "/tmp",
                "nomadnet_downloads",
            ).apply { mkdirs() }
            val outFile = File(downloadDir, fileName)
            check(
                outFile.canonicalPath.startsWith(downloadDir.canonicalPath + File.separator),
            ) { "Rejected path traversal in NomadNet download: $advertised" }
            outFile.writeBytes(data)
            Log.i(TAG, "NomadNet: file response $fileName (${data.size} bytes)")
            return NomadnetPageResult(
                content = "",
                path = safePath,
                type = "file",
                filePath = outFile.absolutePath,
                fileName = fileName,
                fileSize = data.size.toLong(),
            )
        }
        return NomadnetPageResult(
            content = String(data, Charsets.UTF_8),
            path = safePath,
            type = "page",
        )
    }

    /**
     * Parse the optional form-data JSON into a Python dict. NomadNet expects
     * field keys prefixed `field_` / `var_`; bare keys get a `field_` prefix.
     * Returns null (no request data) for null/blank/malformed input.
     */
    private fun parseFormData(formDataJson: String?): PyObject? {
        if (formDataJson.isNullOrBlank()) return null
        return runCatching {
            val json = JSONObject(formDataJson)
            val map = HashMap<String, String>()
            json.keys().forEach { key ->
                val fieldKey =
                    if (!key.startsWith("field_") && !key.startsWith("var_")) "field_$key" else key
                map[fieldKey] = json.getString(key)
            }
            // Build a real Python dict via __setitem__ — `builtins.dict(map)`
            // tries to iterate the HashMap proxy and Chaquopy fails with
            // `'HashMap' object is not iterable`.
            map.toPyDict()
        }.getOrElse {
            Log.w(TAG, "NomadNet: failed to parse form data", it)
            null
        }
    }

    // ==================== Cancellation & status ====================

    /**
     * Fetch a `/media/` object (page image), mirroring
     * [requestNomadnetPage]'s choreography. The capture's `denied` flag
     * (event_bridge) carries upstream's explicit `False` deny signal for
     * gated media, surfaced as [RnsError.NomadnetRequestDenied].
     */
    override suspend fun requestNomadnetMedia(
        destinationHash: String,
        path: String,
        timeoutSeconds: Float,
        maxBytes: Long,
    ): Result<NomadnetMediaResult> =
        pyResult {
            runtime.requireRunning()
            val generation = beginRequest()
            _nomadnetRequestStatusFlow.value = "requesting"
            _nomadnetDownloadProgressFlow.value = 0f
            // One request-level deadline shared by every phase, mirroring
            // requestNomadnetPage - bounds the whole request by
            // `timeoutSeconds` rather than the sum of per-phase budgets.
            val requestDeadlineMs = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()

            try {
                val destBytes = destinationHash.hexToBytes()
                val nodeIdentity = resolveNodeIdentity(
                    destinationHash, destBytes, timeoutSeconds, generation, requestDeadlineMs,
                )
                val link = establishLink(
                    destinationHash, nodeIdentity, timeoutSeconds, generation, requestDeadlineMs,
                )

                // Upstream serve_media requires {"path", "key"}; "path"
                // carries the full "/media/..." path, "key" is Python None
                // (toPyDict's __setitem__ passthrough maps Kotlin null).
                val requestData = mapOf("path" to path, "key" to null).toPyDict()
                val response = sendMediaRequest(
                    link, requestData, timeoutSeconds, destinationHash, generation, requestDeadlineMs,
                )

                // Enforce the transfer cap at the response boundary, before the
                // payload is written to the staging file. RNS/LXMF deliver the
                // full body to the receipt callback at once (no streaming), so
                // this is the earliest point the Kotlin layer can act after
                // delivery; failing here keeps an oversized payload off disk and
                // out of the image cache and surfaces a typed error instead of
                // the caller-side size check in PageImageLoader.fetchOne.
                if (response.size.toLong() > maxBytes) {
                    Log.w(TAG, "NomadNet: media response too large for $path (${response.size} > $maxBytes)")
                    throw RnsException(
                        RnsError.NomadnetResponseTooLarge(
                            destinationHash, path, response.size.toLong(), maxBytes,
                        ),
                    )
                }

                _nomadnetRequestStatusFlow.value = "complete"
                _nomadnetDownloadProgressFlow.value = 1f

                // Unique per-fetch file name so same-named images on one page
                // never clobber each other in flight.
                val rawName = java.io.File(path).name.ifBlank { "media" }
                val mediaDir = File(
                    System.getProperty("java.io.tmpdir") ?: "/tmp",
                    "nomadnet_media",
                ).apply { mkdirs() }
                val outFile = File(mediaDir, "${System.nanoTime()}.$rawName")
                check(
                    outFile.canonicalPath.startsWith(mediaDir.canonicalPath + File.separator),
                ) { "Rejected path traversal in NomadNet media: $rawName" }
                outFile.writeBytes(response)
                Log.i(TAG, "NomadNet: media fetched $path (${response.size} bytes)")
                NomadnetMediaResult(
                    filePath = outFile.absolutePath,
                    fileName = rawName,
                    fileSize = response.size.toLong(),
                    path = path,
                )
            } catch (e: Throwable) {
                _nomadnetRequestStatusFlow.value = if (isCancelled(generation)) "idle" else "failed"
                throw e
            }
        }

    /**
     * Issue `link.request("/media", {"path": ..., "key": None}, ...)` and
     * poll the capture to completion (same pattern as [sendPageRequest] /
     * [awaitResponse]: file-response bodies are snapshot Python-side before
     * upstream closes the backing temp file).
     *
     * Each failure mode (denied / error / null body / FAILED status /
     * timeout) throws a distinct typed RnsException — collapsing them would
     * lose the failure distinction (see [awaitResponse]).
     */
    @Suppress("ThrowsCount")
    private suspend fun sendMediaRequest(
        link: PyObject,
        requestData: PyObject,
        timeoutSeconds: Float,
        destinationHash: String,
        generation: Int,
        requestDeadlineMs: Long,
    ): ByteArray {
        val capture = runtime.eventBridge.callAttr("make_nomadnet_response_capture")
        val receipt = link.callAttr(
            "request",
            "/media",
            requestData,
            capture["on_response"],
            capture["on_failed"],
            null,
            timeoutSeconds.toDouble(),
        )
        if (receipt == null || receipt.toString() == "False") {
            throw RnsException(
                RnsError.Generic("NomadNet media request could not be sent", null),
            )
        }

        _nomadnetRequestStatusFlow.value = "receiving"
        val deadline = (System.currentTimeMillis() + (timeoutSeconds * 1000).toLong())
            .coerceAtMost(requestDeadlineMs)
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled(generation)
            val progress = receipt["progress"]?.toJava(Float::class.javaObjectType) ?: 0f
            _nomadnetDownloadProgressFlow.value = progress.coerceIn(0f, 1f)

            if (capture["done"]?.toJava(Boolean::class.javaObjectType) == true) {
                if (capture["denied"]?.toJava(Boolean::class.javaObjectType) == true) {
                    throw RnsException(RnsError.NomadnetRequestDenied(destinationHash, "/media"))
                }
                capture["error"]?.toString()?.takeIf { it.isNotEmpty() && it != "None" }?.let {
                    throw RnsException(
                        RnsError.Generic("NomadNet media request failed: $it", null),
                    )
                }
                return capture["response_bytes"]?.toJava(ByteArray::class.java)
                    ?: throw RnsException(
                        RnsError.Generic("NomadNet media response READY but body was null", null),
                    )
            }
            if (receipt["status"]?.toJava(Long::class.javaObjectType) == RECEIPT_FAILED) {
                throw RnsException(RnsError.Generic("NomadNet media request failed", null))
            }
            delay(POLL_INTERVAL_MS)
        }
        throw RnsException(RnsError.Generic("NomadNet media request timed out", null))
    }

    override suspend fun cancelNomadnetPageRequest() {
        // Signal only: in-flight page/media poll loops observe the generation
        // (via throwIfCancelled) and unwind on their next tick.
        //
        // Deliberately does NOT tear down the cached links. The upstream python
        // browser (reticulum nomadnet Browser.py `__load`) keeps its
        // per-destination link across requests, and the kotlin backend's
        // cancelNomadnetPageRequest mirrors that (it only sets the cancel flag,
        // never touches its link map). Tearing links down here forced a cold
        // re-establishment whenever a ViewModel was torn down (e.g. leaving the
        // NomadNet tab), which is what surfaced as "failed to establish link to
        // nomadnet node". Keeping the ACTIVE links lets a re-entry reuse the
        // warm link. A cached link that has since gone inactive is dropped on
        // reuse by establishLink() (it only reuses LINK_ACTIVE entries).
        //
        // Target the CURRENT generation: the cancel applies to the request that
        // is in flight right now. A request begun *after* this cancel gets a
        // newer generation and is unaffected, so a stale cancel can never leak
        // into the next user action.
        cancelGeneration = requestGeneration.get()
        _nomadnetRequestStatusFlow.value = "idle"
        _nomadnetDownloadProgressFlow.value = 0f
        Log.i(TAG, "NomadNet: request cancelled")
    }

    override suspend fun getNomadnetRequestStatus(): String =
        _nomadnetRequestStatusFlow.value

    override suspend fun getNomadnetDownloadProgress(): Float =
        _nomadnetDownloadProgressFlow.value

    // ==================== Identified links ====================

    override suspend fun identifyNomadnetLink(destinationHash: String): Result<Boolean> =
        pyResult {
            runtime.requireRunning()
            // Identification requires an ACTIVE link — reuse the one a prior
            // page request established (NativeNomadNetHandler has the same
            // "load a page first" precondition).
            val link = nomadnetLinks[destinationHash]
                ?: throw RnsException(
                    RnsError.Generic("No active link to $destinationHash — load a page first", null),
                )
            if ((testLinkStatus?.invoke(link) ?: linkStatus(link)) != LINK_ACTIVE) {
                nomadnetLinks.remove(destinationHash)
                throw RnsException(
                    RnsError.Generic("Link to $destinationHash is not active — load a page first", null),
                )
            }
            val identity = runtime.localIdentity
                ?: throw RnsException(RnsError.BackendNotReady)
            // RNS.Link.identify(identity) sends the identification proof over
            // the encrypted link. It has no return / ack — the host accepting
            // the proof can only be confirmed by a subsequent gated request,
            // so we report false ("proof sent, not yet confirmed"), matching
            // the kotlin backend's first-identify return.
            //
            // add() is the atomic check-then-act: if the at-establishment
            // auto-identify (or a concurrent caller) already sent the proof
            // on this link, add() returns false and we report already-identified
            // instead of double-sending. The link-id key matches identifyIfFlagged.
            val dedupKey = linkIdHex(link, destinationHash, testLinkIdHex)
            if (!identifiedLinks.add(dedupKey)) {
                return@pyResult true
            }
            // Remove the key if the proof send fails, so a failed proof doesn't
            // poison the shared dedup set: a retry on the active link (or the
            // at-establishment auto-identify path, which shares this set) must
            // be able to send the proof again instead of being told it is
            // already identified.
            try {
                // testIdentifyOnLink stands in for the native callAttr in JVM
                // unit tests (a raw handle can't call native methods); it is
                // null in production, which takes the real path.
                testIdentifyOnLink?.invoke(destinationHash, link)
                    ?: link.callAttr("identify", identity)
            } catch (e: Exception) {
                identifiedLinks.remove(dedupKey)
                throw e
            }
            Log.i(TAG, "NomadNet: sent identify proof on link to $destinationHash")
            false
        }

    /**
     * Auto-identify a flagged node's link at establishment time — the
     * Python equivalent of upstream `Browser.link_established` calling
     * `self.link.identify(...)` when `should_identify_on_connect(hash)`.
     * Best-effort: a missing local identity or a link that closed just before
     * we got here must not fail the page load, so failures are logged and
     * swallowed. `identifiedLinks` (link-id keyed) dedups so a reused link
     * doesn't resend the proof, but a rebuilt link re-identifies. Internal (not
     * private) so the at-establishment contract can be probed directly in unit
     * tests without driving a full native link establishment.
     */
    internal fun identifyIfFlagged(destinationHash: String, link: PyObject) {
        if (destinationHash !in identifyOnConnectNodes) return
        val linkId = linkIdHex(link, destinationHash, testLinkIdHex)
        if (!identifiedLinks.add(linkId)) return
        runCatching {
            val identity = runtime.localIdentity
            if (identity == null) {
                identifiedLinks.remove(linkId)
                Log.w(TAG, "NomadNet: auto-identify skipped, no local identity")
                return
            }
            testIdentifyOnLink?.invoke(destinationHash, link)
                ?: link.callAttr("identify", identity)
            Log.i(TAG, "NomadNet: auto-identified to $destinationHash on link establishment")
        }.onFailure {
            identifiedLinks.remove(linkId)
            Log.w(TAG, "NomadNet: auto-identify to $destinationHash failed: ${it.message}")
        }
    }

    /** Live stats of the cached link, mirroring the kotlin backend's gate data. */
    override suspend fun getNomadnetLinkStats(destinationHash: String): NomadnetLinkStats? {
        val link = nomadnetLinks[destinationHash] ?: return null
        if (linkStatus(link) != LINK_ACTIVE) return null
        return NomadnetLinkStats(
            // Python RNS exposes link.rtt in SECONDS (reticulum-kt uses millis).
            rttSeconds = link["rtt"]?.toJava(Double::class.javaObjectType)
                ?: link["rtt"]?.toJava(Float::class.javaObjectType)?.toDouble(),
            expectedRateBps = runCatching {
                link.callAttr("get_expected_rate")?.toJava(Float::class.javaObjectType)?.toLong()
            }.getOrNull(),
        )
    }

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    /** Read `link.status` as a Long (RNS Link status constants). */
    private fun linkStatus(link: PyObject): Long? =
        link["status"]?.toJava(Long::class.javaObjectType)

    /**
     * True if the request owning [generation] has been cancelled. A request is
     * cancelled only when an explicit [cancelNomadnetPageRequest] happened at or
     * after it began: [cancelGeneration] records the latest generation at the
     * last cancel, so any request begun at or before that moment (generation <=
     * [cancelGeneration]) is cancelled, while a request begun *after* the cancel
     * (a higher generation) is unaffected.
     *
     * This deliberately has NO "a newer request superseded me" term: the browser
     * runs partial-page and image requests CONCURRENTLY (PartialManager +
     * PageImageLoader), so a sibling request starting must never cancel a
     * legitimate in-flight request. Cancellation is driven only by an explicit
     * cancel, and a replacement request never clears it - so a cancelled request
     * cannot be "revived" by a new one, and a stale cancel can never leak into a
     * later, unrelated request (which owns a higher generation).
     */
    private fun isCancelled(generation: Int): Boolean =
        generation <= cancelGeneration

    /** [isCancelled] as a throw, called from each in-flight poll loop. */
    private fun throwIfCancelled(generation: Int) {
        if (isCancelled(generation)) {
            throw RnsException(RnsError.Generic("NomadNet request cancelled", null))
        }
    }
}

/**
 * Stable id of an RNS link for the auto-identify dedup set. Reads the
 * link's `link_id` bytes (set by RNS on establishment, the same value the
 * Kotlin backend reads as `link.linkId`). A rebuilt link gets a new
 * link_id so it re-identifies; a reused link keeps its link_id so it
 * doesn't. Falls back to the destination hash when the attribute is
 * unreadable - stable across calls, so the common case (identify once,
 * don't double-send on reuse) still holds. File-level (not a member) so the
 * class stays under detekt's TooManyFunctions threshold.
 */
private fun linkIdHex(link: PyObject, destinationHash: String, seam: ((PyObject) -> String)?): String =
    seam?.invoke(link)
        ?: runCatching { link["link_id"]?.toJava(ByteArray::class.java)?.toHex() }
            .getOrNull()
        ?: destinationHash
