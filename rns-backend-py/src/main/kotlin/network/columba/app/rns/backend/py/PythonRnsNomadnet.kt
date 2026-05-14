package network.columba.app.rns.backend.py

import android.util.Log
import com.chaquo.python.PyObject
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import network.columba.app.rns.api.RnsError
import network.columba.app.rns.api.RnsException
import network.columba.app.rns.api.RnsNomadnet
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
    private val nomadnetLinks = ConcurrentHashMap<String, PyObject>()

    /** Cooperative-cancel flag, flipped by [cancelNomadnetPageRequest]. */
    @Volatile
    private var cancelled = false

    // ==================== Page / file requests ====================

    override suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<NomadnetPageResult> =
        pyResult {
            runtime.requireRunning()
            cancelled = false
            _nomadnetRequestStatusFlow.value = "requesting"
            _nomadnetDownloadProgressFlow.value = 0f

            val safePath = if (path.isBlank() || !path.startsWith("/")) DEFAULT_PATH else path
            val destBytes = destinationHash.hexToBytes()

            try {
                val nodeIdentity = resolveNodeIdentity(destinationHash, destBytes, timeoutSeconds)
                val link = establishLink(destinationHash, nodeIdentity, timeoutSeconds)
                val receipt = sendPageRequest(link, safePath, formDataJson, timeoutSeconds)
                val result = awaitResponse(receipt, safePath, timeoutSeconds)
                _nomadnetRequestStatusFlow.value = "complete"
                _nomadnetDownloadProgressFlow.value = 1f
                result
            } catch (e: Throwable) {
                _nomadnetRequestStatusFlow.value = if (cancelled) "idle" else "failed"
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
            throwIfCancelled()
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
            throwIfCancelled()
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
     * Issue `link.request(path, data, ...)`. `data` is the optional form map
     * for POST-style page requests; NomadNet expects `field_*` / `var_*` keys.
     */
    private fun sendPageRequest(
        link: PyObject,
        safePath: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): PyObject {
        val requestData = parseFormData(formDataJson)
        _nomadnetRequestStatusFlow.value = "requesting"
        // request(path, data, response_callback, failed_callback,
        // progress_callback, timeout). Callbacks are left null — see the class
        // KDoc: we poll the returned RequestReceipt instead of bridging Python
        // callables back into Kotlin lambdas. TODO(on-device): if callback
        // bridging proves reliable through Chaquopy, prefer it over polling.
        val receipt = link.callAttr(
            "request",
            safePath,
            requestData,
            null,
            null,
            null,
            timeoutSeconds.toDouble(),
        )
        // RNS.Link.request returns False (not a receipt) when the request
        // packet couldn't even be sent.
        if (receipt == null || receipt.toString() == "False") {
            throw RnsException(
                RnsError.Generic("NomadNet request for $safePath could not be sent", null),
            )
        }
        return receipt
    }

    /**
     * Poll the [receipt] until it reaches READY (or FAILED / timeout),
     * threading `.progress` through [nomadnetDownloadProgressFlow], then build
     * the [NomadnetPageResult].
     */
    // Each failure mode (cancelled / timeout / FAILED status) throws a distinct
    // typed RnsException — collapsing them would lose the failure distinction.
    @Suppress("ThrowsCount")
    private suspend fun awaitResponse(
        receipt: PyObject,
        safePath: String,
        timeoutSeconds: Float,
    ): NomadnetPageResult {
        _nomadnetRequestStatusFlow.value = "receiving"
        val deadline = System.currentTimeMillis() + (timeoutSeconds * 1000).toLong()
        while (System.currentTimeMillis() < deadline) {
            throwIfCancelled()

            val progress = receipt["progress"]?.toJava(Float::class.javaObjectType) ?: 0f
            _nomadnetDownloadProgressFlow.value = progress.coerceIn(0f, 1f)

            when (receipt["status"]?.toJava(Long::class.javaObjectType)) {
                RECEIPT_READY -> {
                    val response = receipt["response"]?.toJava(ByteArray::class.java)
                        ?: throw RnsException(
                            RnsError.Generic("NomadNet response READY but body was null", null),
                        )
                    val metadata = receipt["metadata"]?.toJava(ByteArray::class.java)
                    return buildPageResult(response, metadata, safePath)
                }
                RECEIPT_FAILED -> throw RnsException(
                    RnsError.Generic("NomadNet request for $safePath failed", null),
                )
                else -> Unit // SENT / DELIVERED / RECEIVING — keep polling.
            }
            delay(POLL_INTERVAL_MS)
        }
        throw RnsException(
            RnsError.Generic("NomadNet request for $safePath timed out", null),
        )
    }

    /**
     * A `/file/` response carries msgpacked metadata (`{name, ...}`) and the
     * body is written to a download file; a `/page/` response is raw micron
     * text. Metadata parsing reuses the upstream-shaped contract — full
     * msgpack decode of the metadata blob is on-device follow-up, so for now
     * a non-null metadata blob is treated as "file" with a path-safe name
     * derived from the request path.
     */
    private fun buildPageResult(
        data: ByteArray,
        metadata: ByteArray?,
        safePath: String,
    ): NomadnetPageResult {
        if (metadata != null && metadata.isNotEmpty()) {
            // TODO(on-device): decode the msgpacked metadata map to recover the
            // server-advertised file name (NativeNomadNetHandler unpacks it via
            // org.msgpack). Until that decode path is verified on-device, fall
            // back to the request-path basename — still path-traversal-safe.
            val rawName = safePath.substringAfterLast('/').ifBlank { "download" }
            val fileName = File(rawName).name.ifBlank { "download" }
            val downloadDir = File(
                System.getProperty("java.io.tmpdir") ?: "/tmp",
                "nomadnet_downloads",
            ).apply { mkdirs() }
            val outFile = File(downloadDir, fileName)
            check(
                outFile.canonicalPath.startsWith(downloadDir.canonicalPath + File.separator),
            ) { "Rejected path traversal in NomadNet download: $rawName" }
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
            // dict(**map) via builtins — Chaquopy maps a Kotlin Map to a Python
            // dict for the kwargs expansion.
            runtime.python.builtins.callAttr("dict", map)
        }.getOrElse {
            Log.w(TAG, "NomadNet: failed to parse form data", it)
            null
        }
    }

    // ==================== Cancellation & status ====================

    override suspend fun cancelNomadnetPageRequest() {
        cancelled = true
        // Tear down every in-flight link; the polling loops observe `cancelled`
        // and unwind on their next tick.
        nomadnetLinks.values.forEach { link ->
            runCatching { link.callAttr("teardown") }
                .onFailure { Log.w(TAG, "NomadNet: link teardown on cancel failed", it) }
        }
        nomadnetLinks.clear()
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

    // ==================== Internal helpers ====================

    /** `RNS.Transport` — used statically by upstream RNS. */
    private fun transport(): PyObject =
        runtime.rnsModule["Transport"] ?: error("RNS.Transport not resolvable")

    /** Read `link.status` as a Long (RNS Link status constants). */
    private fun linkStatus(link: PyObject): Long? =
        link["status"]?.toJava(Long::class.javaObjectType)

    private fun throwIfCancelled() {
        if (cancelled) {
            throw RnsException(RnsError.Generic("NomadNet request cancelled", null))
        }
    }
}
