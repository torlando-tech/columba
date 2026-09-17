package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import network.columba.app.rns.api.util.hexToBytes
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

            val safePath = if (path.isBlank() || !path.startsWith("/")) DEFAULT_PATH else path
            val destBytes = destinationHash.hexToBytes()

            try {
                val nodeIdentity = resolveNodeIdentity(destinationHash, destBytes, timeoutSeconds, generation)
                val link = establishLink(destinationHash, nodeIdentity, timeoutSeconds, generation)
                val handle = sendPageRequest(link, safePath, formDataJson, timeoutSeconds)
                val result = awaitResponse(handle, safePath, timeoutSeconds, generation)
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
    ): PyObject {
        val identityClass = runtime.rnsModule["Identity"] ?: error("RNS.Identity missing")
        identityClass.callAttr("recall", destBytes.toPyBytes())?.let { return it }

        Log.i(TAG, "NomadNet: identity for $destinationHash unknown, requesting path")
        _nomadnetRequestStatusFlow.value = "requesting"
        transport().callAttr("request_path", destBytes.toPyBytes())

        // Path lookup gets up to a third of the budget (min 15s), mirroring
        // NativeNomadNetHandler.
        val deadline = System.currentTimeMillis() +
            (timeoutSeconds * 1000 / 3).toLong().coerceAtLeast(15_000L)
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
     */
    private suspend fun establishLink(
        destinationHash: String,
        nodeIdentity: PyObject,
        timeoutSeconds: Float,
        generation: Int,
    ): PyObject {
        nomadnetLinks[destinationHash]?.let { existing ->
            if (linkStatus(existing) == LINK_ACTIVE) {
                Log.i(TAG, "NomadNet: reusing active link to $destinationHash")
                return existing
            }
            nomadnetLinks.remove(destinationHash)
        }

        _nomadnetRequestStatusFlow.value = "requesting"
        val destClass = runtime.rnsModule["Destination"] ?: error("RNS.Destination missing")
        // RNS.Destination(identity, OUT, SINGLE, "nomadnetwork", "node")
        val nodeDest = runtime.rnsModule.callAttr(
            "Destination",
            nodeIdentity,
            destClass["OUT"] ?: error("Destination.OUT missing"),
            destClass["SINGLE"] ?: error("Destination.SINGLE missing"),
            "nomadnetwork",
            "node",
        )

        val link = runtime.rnsModule.callAttr("Link", nodeDest)

        // Link establishment gets up to a third of the budget (min 5s).
        val deadline = System.currentTimeMillis() +
            (timeoutSeconds * 1000 / 3).toLong().coerceAtLeast(5_000L)
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled(generation)
            if (linkStatus(link) == LINK_ACTIVE) {
                nomadnetLinks[destinationHash] = link
                Log.i(TAG, "NomadNet: link established to $destinationHash")
                return link
            }
            delay(POLL_INTERVAL_MS)
        }
        runCatching { link.callAttr("teardown") }
        throw RnsException(
            RnsError.Generic("Failed to establish link to NomadNet node ${destinationHash.take(16)}", null),
        )
    }

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
    ): NomadnetPageResult {
        val (receipt, capture) = handle
        _nomadnetRequestStatusFlow.value = "receiving"
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
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

            try {
                val destBytes = destinationHash.hexToBytes()
                val nodeIdentity = resolveNodeIdentity(destinationHash, destBytes, timeoutSeconds, generation)
                val link = establishLink(destinationHash, nodeIdentity, timeoutSeconds, generation)

                // Upstream serve_media requires {"path", "key"}; "path"
                // carries the full "/media/..." path, "key" is Python None
                // (toPyDict's __setitem__ passthrough maps Kotlin null).
                val requestData = mapOf("path" to path, "key" to null).toPyDict()
                val response = sendMediaRequest(link, requestData, timeoutSeconds, destinationHash, generation)

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
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
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
            if (linkStatus(link) != LINK_ACTIVE) {
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
            link.callAttr("identify", identity)
            Log.i(TAG, "NomadNet: sent identify proof on link to $destinationHash")
            false
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
