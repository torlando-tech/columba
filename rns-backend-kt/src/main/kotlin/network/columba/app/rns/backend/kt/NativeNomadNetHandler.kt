package network.columba.app.rns.backend.kt

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.job
import kotlinx.coroutines.flow.MutableStateFlow
import network.columba.app.rns.api.util.hexToBytes
import network.columba.app.rns.api.util.toHex
import network.reticulum.common.DestinationDirection
import network.reticulum.transport.Transport
import org.msgpack.core.MessagePack

internal class NativeNomadNetHandler(
    private val appContext: android.content.Context?,
    private val deliveryIdentityProvider: () -> NativeIdentity?,
) {
    companion object {
        private const val TAG = "NativeReticulumProtocol"
    }

    val nomadnetLinks = java.util.concurrent.ConcurrentHashMap<String, network.reticulum.link.Link>()

    // Concurrent callers can race through identifyNomadnetLink; use a set backed by a
    // ConcurrentHashMap and atomic add() so we never double-identify the same link.
    // Also dedups the at-establishment auto-identify (a rebuilt/reused link for an
    // already-identified destination must not resend the proof).
    val identifiedNomadnetLinks: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    /**
     * Per-node auto-identify flags, mirroring upstream
     * `Directory.should_identify_on_connect` and the Python backend's
     * [PythonRnsNomadnet.identifyOnConnectNodes]. The handler identifies a
     * node's link at establishment time when the destination is in this set.
     * Updated by the ViewModel via [setIdentifyOnConnectNodes].
     */
    @Volatile
    internal var identifyOnConnectNodes: Set<String> = emptySet()

    /**
     * Serializes flag-set updates so the read `previous` -> write
     * `identifyOnConnectNodes` -> reconcile-links (identify/teardown) window is
     * atomic. Two callers can overlap: the DataStore collector (sequential
     * emissions) and the RNS-READY re-sync (a separate coroutine that re-pushes
     * the current set). Without the lock, an unflag's `previous` snapshot can be
     * read before a concurrent flag update lands, so the unflag tears down (or
     * fails to tear down) the wrong link set and an identified link can survive
     * an unflag. The lock body has no suspension points (identifyIfFlagged and
     * teardown are non-suspend here), so a JVM monitor is the right tool and the
     * last writer's reconcile always runs against a consistent snapshot.
     */
    private val identifySetLock = Any()

    suspend fun setIdentifyOnConnectNodes(nodes: Set<String>) {
        synchronized(identifySetLock) {
            val previous = identifyOnConnectNodes
            identifyOnConnectNodes = nodes
            // Identify newly-flagged nodes whose link is ALREADY active. Covers:
            // (a) the user toggling "Always identify to this node" in Node
            // Details while that node's page is open in the browser (the link
            // was established before the flag was set), and (b) the narrow
            // first-launch window where a flagged node's page was served from
            // cache before the DataStore set arrived. identifyIfFlagged is a
            // no-op for nodes that are not in the set or whose link is not
            // active, and dedups via identifiedNomadnetLinks, so calling it for
            // every newly-added node is safe.
            (nodes - previous).forEach { hash ->
                nomadnetLinks[hash]?.let { link ->
                    if (link.status == network.reticulum.link.LinkConstants.ACTIVE) {
                        identifyIfFlaggedLocked(hash, link)
                    }
                }
            }
            // Tear down newly-unflagged nodes' ACTIVE links. An identified link
            // carries its proof for the link's lifetime, so unflagging without
            // tearing it down would keep the user browsing as identified - the
            // natural expectation is that further actions are NOT associated
            // with the identity. Drop the link (and its dedup key) so the next
            // refresh/navigation establishes a fresh, anonymous link. Only
            // flagged->unflagged links are touched (previous - nodes): an
            // anonymous link that was never flagged is not in `previous`, so it
            // is never torn down by a change to a different node's flag.
            // Deliberately does NOT re-establish: the re-establishment happens
            // lazily on the user's next page action, paying a fresh handshake
            // only when needed. This is the inverse of the newly-flagged path
            // above and intentionally goes beyond upstream (whose ident_change
            // is a no-op) for intuitive UX.
            (previous - nodes).forEach { hash ->
                nomadnetLinks[hash]?.let { link ->
                    if (link.status == network.reticulum.link.LinkConstants.ACTIVE) {
                        identifiedNomadnetLinks.remove(
                            runCatching { link.linkId.toHex() }.getOrDefault(hash)
                        )
                        link.teardown()
                        nomadnetLinks.remove(hash)
                        Log.i(TAG, "NomadNet: tore down link to $hash on unflag (anonymous on next visit)")
                    }
                }
            }
        }
    }

    @Volatile var nomadnetCancelled = false

    val requestStatusFlow = MutableStateFlow("idle")
    val downloadProgressFlow = MutableStateFlow(0f)

    suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<network.columba.app.rns.api.model.NomadnetPageResult> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                nomadnetCancelled = false
                requestStatusFlow.value = "connecting"
                downloadProgressFlow.value = 0f

                val destBytes = destinationHash.hexToBytes()
                val safePath = if (path.isBlank() || !path.startsWith("/")) "/page/index.mu" else path

                val nodeIdentity = resolveNodeIdentity(destinationHash, destBytes)

                val linkResult = resolveOrEstablishLink(destinationHash, nodeIdentity, destBytes, timeoutSeconds)
                val link = linkResult.first
                val reusedActiveLink = linkResult.second

                val requestData = parseFormData(formDataJson)

                requestStatusFlow.value = "requesting page"
                Log.i(TAG, "NomadNet: sending page request for $safePath on link status=${link.status}")
                val response = sendPageRequest(link, safePath, requestData, timeoutSeconds)

                if (nomadnetCancelled) throw java.util.concurrent.CancellationException("Cancelled")

                if (response.error != null || response.bytes == null) {
                    nomadnetLinks.remove(destinationHash)
                    link.teardown()
                    if (reusedActiveLink) {
                        Log.i(TAG, "NomadNet: request failed on cached link, retrying with fresh link")
                        return@runCatching requestNomadnetPage(destinationHash, path, formDataJson, timeoutSeconds / 2)
                            .getOrThrow()
                    }
                    error(response.error ?: "Page request timed out")
                }
                val data = response.bytes

                requestStatusFlow.value = "complete"
                downloadProgressFlow.value = 1f

                buildPageResult(data, response.metadata, safePath)
            }.onFailure {
                if (nomadnetCancelled) {
                    requestStatusFlow.value = "cancelled"
                } else {
                    requestStatusFlow.value = "failed"
                }
            }
        }

    /**
     * Fetch a `/media/` object (page image) from a NomadNet node.
     *
     * Mirrors upstream Browser.py `__load_image`: request `/media` with data
     * `{"path": <full media path>, "key": None}` on the (reused, else fresh)
     * node link. A file response arrives with metadata and the raw body in
     * `receipt.response`; a server-side denial (Node.py `serve_media`
     * returning `False`, commit 3028301) arrives as a msgpack `false` body
     * (single byte 0xC0 after reticulum-kt re-serialises the scalar) and is
     * surfaced as [network.columba.app.rns.api.RnsError.NomadnetRequestDenied].
     */
    suspend fun requestNomadnetMedia(
        destinationHash: String,
        path: String,
        timeoutSeconds: Float,
        maxBytes: Long = Long.MAX_VALUE,
    ): Result<network.columba.app.rns.api.model.NomadnetMediaResult> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                nomadnetCancelled = false
                requestStatusFlow.value = "requesting media"
                downloadProgressFlow.value = 0f

                val destBytes = destinationHash.hexToBytes()
                val nodeIdentity = resolveNodeIdentity(destinationHash, destBytes)
                val (link, _) = resolveOrEstablishLink(destinationHash, nodeIdentity, destBytes, timeoutSeconds)

                // Upstream serve_media requires both keys; "path" carries the
                // full "/media/..." path (it strips the prefix itself).
                val requestData: Map<String, Any?> = mapOf("path" to path, "key" to null)
                val response = sendPageRequest(link, "/media", requestData, timeoutSeconds)

                if (nomadnetCancelled) throw java.util.concurrent.CancellationException("Cancelled")
                val data = response.bytes
                    ?: error(response.error ?: "Media request timed out")
                if (isMsgpackFalse(data)) {
                    Log.i(TAG, "NomadNet: media request denied by node for $path")
                    throw network.columba.app.rns.api.RnsException(
                        network.columba.app.rns.api.RnsError.NomadnetRequestDenied(destinationHash, path),
                    )
                }
                // Enforce the transfer cap at the response boundary, before the
                // payload is written to temporary storage or handed back. The RNS
                // Link API delivers the full body to the callback at once (no
                // streaming), so this is the earliest point after delivery; failing
                // here keeps an oversized payload off disk and out of the image
                // cache, and surfaces a typed error instead of a generic one.
                if (data.size.toLong() > maxBytes) {
                    Log.w(TAG, "NomadNet: media response too large for $path (${data.size} > $maxBytes)")
                    throw network.columba.app.rns.api.RnsException(
                        network.columba.app.rns.api.RnsError.NomadnetResponseTooLarge(
                            destinationHash, path, data.size.toLong(), maxBytes,
                        ),
                    )
                }
                if (data.isEmpty()) error("Empty media response for $path")

                // Unique per-fetch destination file so concurrent media loads
                // of same-named files never clobber each other; the image
                // cache layer moves this into its URL-keyed cache.
                val rawName = java.io.File(path).name.ifBlank { "media" }
                val mediaDir =
                    appContext?.cacheDir?.resolve("nomadnet_media")
                        ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nomadnet_media")
                mediaDir.mkdirs()
                val outFile = mediaDir.resolve("${System.nanoTime()}.$rawName")
                check(outFile.canonicalPath.startsWith(mediaDir.canonicalPath + java.io.File.separator)) {
                    "Rejected path traversal attempt in NomadNet media: $rawName"
                }
                outFile.writeBytes(data)

                requestStatusFlow.value = "complete"
                downloadProgressFlow.value = 1f
                Log.i(TAG, "NomadNet: media fetched $path (${data.size} bytes)")
                network.columba.app.rns.api.model.NomadnetMediaResult(
                    filePath = outFile.absolutePath,
                    fileName = rawName,
                    fileSize = data.size.toLong(),
                    path = path,
                )
            }.onFailure {
                requestStatusFlow.value = if (nomadnetCancelled) "cancelled" else "failed"
            }
        }

    /** True when [bytes] is exactly the msgpack encoding of `false` (0xC0),
     *  the deny signal upstream NomadNet nodes send for gated media. */
    internal fun isMsgpackFalse(bytes: ByteArray): Boolean =
        bytes.size == 1 && bytes[0] == 0xC0.toByte()

    /** Live stats of the active NomadNet link, for the image auto-load gate
     *  (upstream Browser.py reads `link.rtt` / `link.get_expected_rate()`). */
    fun getLinkStats(destinationHash: String): network.columba.app.rns.api.model.NomadnetLinkStats? {
        val link = nomadnetLinks[destinationHash] ?: return null
        if (link.status != network.reticulum.link.LinkConstants.ACTIVE) return null
        return network.columba.app.rns.api.model.NomadnetLinkStats(
            rttSeconds = link.rtt?.let { it / 1000.0 },
            expectedRateBps = link.getExpectedRate()?.let { it.toLong() },
        )
    }

    private suspend fun resolveNodeIdentity(
        destinationHash: String,
        destBytes: ByteArray,
    ): NativeIdentity {
        Log.i(TAG, "NomadNet: recalling identity for $destinationHash (${destBytes.size} bytes), known=${NativeIdentity.knownDestinationCount()}")
        var nodeIdentity = NativeIdentity.recall(destBytes)
        if (nodeIdentity == null) {
            Log.i(TAG, "NomadNet: identity not found, requesting path...")
            requestStatusFlow.value = "looking up path"
            Transport.requestPath(destBytes)
            val pathDeadline = System.currentTimeMillis() + 15_000
            while (nodeIdentity == null && System.currentTimeMillis() < pathDeadline) {
                if (nomadnetCancelled) throw java.util.concurrent.CancellationException("Cancelled")
                kotlinx.coroutines.delay(250)
                nodeIdentity = NativeIdentity.recall(destBytes)
            }
            if (nodeIdentity == null) {
                Log.w(
                    TAG,
                    "NomadNet: identity STILL not found after path request. known=${NativeIdentity.knownDestinationCount()}, hasPath=${Transport.hasPath(
                        destBytes,
                    )}",
                )
                error("Node not found after path request: ${destinationHash.take(16)}. Node may be offline.")
            }
            Log.i(TAG, "NomadNet: identity found after path request")
        }
        return nodeIdentity
    }

    private suspend fun resolveOrEstablishLink(
        destinationHash: String,
        nodeIdentity: NativeIdentity,
        destBytes: ByteArray,
        timeoutSeconds: Float,
    ): Pair<network.reticulum.link.Link, Boolean> {
        var link = nomadnetLinks[destinationHash]
        val reusedActiveLink = link != null && link.status == network.reticulum.link.LinkConstants.ACTIVE
        Log.i(TAG, "NomadNet: cached link=${link != null}, status=${link?.status}, ACTIVE=${network.reticulum.link.LinkConstants.ACTIVE}")

        if (link == null || link.status != network.reticulum.link.LinkConstants.ACTIVE) {
            nomadnetLinks.remove(destinationHash)
            requestStatusFlow.value = "establishing link"
            Log.i(TAG, "NomadNet: establishing new link to $destinationHash")
            link = establishNomadnetLink(nodeIdentity, destBytes, timeoutSeconds)
            nomadnetLinks[destinationHash] = link
            Log.i(TAG, "NomadNet: link established, RTT=${link.rtt}ms")
        }
        // Auto-identify flagged nodes the moment the link is active - mirrors
        // upstream Browser.link_established and the Python backend's
        // identifyIfFlagged. The link is ACTIVE by construction here, so there
        // is no "no active link" window. Idempotent via the link-id-keyed
        // identifiedNomadnetLinks dedup (a reused link doesn't re-send, a
        // rebuilt link re-identifies, a flag set after establishment is
        // honoured on the next reuse).
        identifyIfFlagged(destinationHash, link)
        return Pair(link, reusedActiveLink)
    }

    /**
     * Auto-identify a flagged node's link at establishment time, using the
     * local identity. Best-effort: a missing local identity must not fail the
     * page load, so failures are logged and swallowed. The link-id-keyed
     * [identifiedNomadnetLinks] set dedups (a reused link doesn't re-send, a
     * rebuilt link re-identifies). Suspend wrapper for the establishLink call
     * sites; the actual work is non-suspending ([identifyIfFlaggedLocked]) so
     * [setIdentifyOnConnectNodes] can run it inside its flag-set lock.
     */
    private suspend fun identifyIfFlagged(destinationHash: String, link: network.reticulum.link.Link) {
        identifyIfFlaggedLocked(destinationHash, link)
    }

    private fun identifyIfFlaggedLocked(destinationHash: String, link: network.reticulum.link.Link) {
        if (destinationHash !in identifyOnConnectNodes) return
        val dedupKey =
            runCatching { link.linkId.toHex() }.getOrDefault(destinationHash)
        if (!identifiedNomadnetLinks.add(dedupKey)) return
        runCatching {
            val identity =
                deliveryIdentityProvider()
                    ?: error("no local identity")
            link.identify(identity)
            Log.i(TAG, "NomadNet: auto-identified to $destinationHash on link establishment")
        }.onFailure {
            identifiedNomadnetLinks.remove(dedupKey)
            Log.w(TAG, "NomadNet: auto-identify to $destinationHash failed: ${it.message}")
        }
    }

    private suspend fun establishNomadnetLink(
        nodeIdentity: NativeIdentity,
        destBytes: ByteArray,
        timeoutSeconds: Float,
    ): network.reticulum.link.Link {
        val nodeDest =
            NativeDestination.create(
                nodeIdentity,
                DestinationDirection.OUT,
                NativeDestinationType.SINGLE,
                "nomadnetwork",
                "node",
            )

        val linkTimeout = (timeoutSeconds * 1000 / 3).toLong().coerceAtLeast(5000)
        val latch = java.util.concurrent.CountDownLatch(1)
        var established: network.reticulum.link.Link? = null
        val newLink =
            network.reticulum.link.Link.create(
                destination = nodeDest,
                establishedCallback = { l ->
                    established = l
                    latch.countDown()
                },
                closedCallback = { _ -> latch.countDown() },
            )

        latch.await(linkTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)

        if (nomadnetCancelled) throw java.util.concurrent.CancellationException("Cancelled")
        if (established == null || newLink.status != network.reticulum.link.LinkConstants.ACTIVE) {
            Log.w(TAG, "NomadNet: link establishment failed. status=${newLink.status}, established=${established != null}")
            established = retryLinkEstablishment(nodeDest, destBytes, linkTimeout, timeoutSeconds)
        }

        return established ?: error("Failed to establish link to node")
    }

    private suspend fun retryLinkEstablishment(
        nodeDest: NativeDestination,
        destBytes: ByteArray,
        linkTimeout: Long,
        timeoutSeconds: Float,
    ): network.reticulum.link.Link? {
        Log.i(TAG, "NomadNet: expiring stale path, requesting fresh path and retrying...")
        Transport.expirePath(destBytes)
        Transport.requestPath(destBytes)
        val retryDeadline = System.currentTimeMillis() + maxOf(20_000L, (timeoutSeconds * 1000).toLong())
        while (!Transport.hasPath(destBytes) && System.currentTimeMillis() < retryDeadline) {
            if (nomadnetCancelled) throw java.util.concurrent.CancellationException("Cancelled")
            kotlinx.coroutines.delay(250)
        }
        if (!Transport.hasPath(destBytes)) return null

        val retryLatch = java.util.concurrent.CountDownLatch(1)
        var retryEstablished: network.reticulum.link.Link? = null
        val retryLink =
            network.reticulum.link.Link.create(
                destination = nodeDest,
                establishedCallback = { l ->
                    retryEstablished = l
                    retryLatch.countDown()
                },
                closedCallback = { _ -> retryLatch.countDown() },
            )
        retryLatch.await(linkTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        if (retryEstablished != null && retryLink.status == network.reticulum.link.LinkConstants.ACTIVE) {
            Log.i(TAG, "NomadNet: link established on retry")
        }
        return retryEstablished
    }

    private fun parseFormData(formDataJson: String?): Any? {
        if (formDataJson == null) return null
        return try {
            val json = org.json.JSONObject(formDataJson)
            val map = mutableMapOf<String, String>()
            json.keys().forEach { key ->
                val fieldKey = if (!key.startsWith("field_") && !key.startsWith("var_")) "field_$key" else key
                map[fieldKey] = json.getString(key)
            }
            map
        } catch (e: Exception) {
            Log.w(TAG, "NomadNet: failed to parse form data", e)
            null
        }
    }

    private data class PageResponse(
        val bytes: ByteArray?,
        val metadata: ByteArray?,
        val error: String?,
    )

    private suspend fun sendPageRequest(
        link: network.reticulum.link.Link,
        path: String,
        requestData: Any?,
        timeoutSeconds: Float,
    ): PageResponse {
        val responseLatch = java.util.concurrent.CountDownLatch(1)
        var responseBytes: ByteArray? = null
        var responseMetadata: ByteArray? = null
        var responseError: String? = null

        link.request(
            path = path,
            data = requestData,
            responseCallback = { receipt ->
                Log.i(TAG, "NomadNet: page response received, ${receipt.response?.size ?: 0} bytes, metadata=${receipt.metadata?.size ?: 0} bytes")
                responseBytes = receipt.response
                responseMetadata = receipt.metadata
                requestStatusFlow.value = if (receipt.progress > 0f) "downloading" else "complete"
                downloadProgressFlow.value = receipt.progress
                responseLatch.countDown()
            },
            failedCallback = { _ ->
                Log.w(TAG, "NomadNet: page request FAILED callback")
                requestStatusFlow.value = "failed"
                responseError = "Page request failed"
                responseLatch.countDown()
            },
            progressCallback = { receipt ->
                if (receipt.progress > 0f) {
                    requestStatusFlow.value = "downloading"
                }
                downloadProgressFlow.value = receipt.progress
            },
        )

        val requestTimeout = (timeoutSeconds * 1000 * 2 / 3).toLong().coerceAtLeast(10000)
        // Poll the latch in short increments instead of one blocking
        // `await(timeout)` so a cancelled caller (the page-image loader queue
        // unwinding on navigation, or cancelNomadnetPageRequest) releases the
        // wait promptly. A non-cancellable full-timeout await left the native
        // backend blocked for up to the whole deadline even after the request's
        // coroutine was cancelled, so stale image traffic kept competing with
        // the next page request over the shared NomadNet link. ensureActive()
        // throws CancellationException on the next tick, which the caller's
        // withContext/runCatching surfaces as a cancelled fetch.
        val deadline = System.currentTimeMillis() + requestTimeout
        var finished = false
        while (!finished) {
            // Cancellation check: if the caller's job was cancelled (page-image
            // loader queue unwinding on navigation, or cancelNomadnetPageRequest),
            // stop waiting and surface a CancellationException so the stale fetch
            // is dropped instead of holding the link for the whole deadline.
            if (currentCoroutineContext().job?.isActive == false) {
                throw java.util.concurrent.CancellationException("Cancelled")
            }
            val remaining = deadline - System.currentTimeMillis()
            finished = remaining <= 0L ||
                responseLatch.await(minOf(100L, remaining), java.util.concurrent.TimeUnit.MILLISECONDS)
        }
        Log.i(TAG, "NomadNet: latch returned gotResponse=${responseBytes != null}, responseBytes=${responseBytes?.size}, error=$responseError")

        return PageResponse(responseBytes, responseMetadata, responseError)
    }

    private fun buildPageResult(
        data: ByteArray,
        metadata: ByteArray?,
        safePath: String,
    ): network.columba.app.rns.api.model.NomadnetPageResult {
        val fileMeta = metadata?.let { parseNomadnetFileMetadata(it) }
        if (fileMeta != null) {
            val rawName = fileMeta["name"] as? String ?: safePath.substringAfterLast("/")
            // Strip any directory components so a malicious server can't escape the
            // download dir via "../" segments and clobber app files.
            val fileName =
                java.io
                    .File(rawName)
                    .name
                    .ifBlank { "download" }
            Log.i(TAG, "NomadNet: response is a file download: $fileName (${data.size} bytes)")
            val downloadDir =
                appContext?.cacheDir?.resolve("nomadnet_downloads")
                    ?: java.io.File(System.getProperty("java.io.tmpdir") ?: "/tmp", "nomadnet_downloads")
            downloadDir.mkdirs()
            val outFile = downloadDir.resolve(fileName)
            check(outFile.canonicalPath.startsWith(downloadDir.canonicalPath + java.io.File.separator)) {
                "Rejected path traversal attempt in NomadNet download: $rawName"
            }
            outFile.writeBytes(data)
            return network.columba.app.rns.api.model.NomadnetPageResult(
                content = "",
                path = safePath,
                type = "file",
                filePath = outFile.absolutePath,
                fileName = fileName,
                fileSize = data.size.toLong(),
            )
        }
        return network.columba.app.rns.api.model.NomadnetPageResult(
            content = String(data, Charsets.UTF_8),
            path = safePath,
            type = "page",
        )
    }

    fun parseNomadnetFileMetadata(bytes: ByteArray): Map<String, Any?>? =
        try {
            val unpacker = MessagePack.newDefaultUnpacker(bytes)
            val format = unpacker.nextFormat
            if (!format.valueType.isMapType) {
                null
            } else {
                val size = unpacker.unpackMapHeader()
                val map = mutableMapOf<String, Any?>()
                repeat(size) {
                    val key = unpacker.unpackString()
                    map[key] =
                        when {
                            unpacker.nextFormat.valueType.isStringType -> unpacker.unpackString()
                            unpacker.nextFormat.valueType.isIntegerType -> unpacker.unpackLong()
                            else -> {
                                unpacker.skipValue()
                                null
                            }
                        }
                }
                if (map.containsKey("name")) map else null
            }
        } catch (e: Exception) {
            Log.d(TAG, "NomadNet: metadata parse failed (not a file response): ${e.message}")
            null
        }

    fun identifyNomadnetLink(destinationHash: String): Result<Boolean> =
        runCatching {
            val link = nomadnetLinks[destinationHash]
            if (link == null || link.status != network.reticulum.link.LinkConstants.ACTIVE) {
                error("No active link to this node. Load a page first.")
            }
            val identity =
                deliveryIdentityProvider()
                    ?: error("No local identity available")

            val linkIdHex = link.linkId.toHex()
            // add() returns false if the link was already identified — use it as an
            // atomic check-then-act so concurrent callers can't both pass the guard.
            if (!identifiedNomadnetLinks.add(linkIdHex)) {
                return@runCatching true
            }

            // Remove the key if the proof send fails, so a failed proof doesn't
            // poison the shared dedup set (same rationale as the python backend):
            // a retry or the at-establishment auto-identify must be able to send
            // the proof again instead of being told it is already identified.
            try {
                link.identify(identity)
            } catch (e: Exception) {
                identifiedNomadnetLinks.remove(linkIdHex)
                throw e
            }
            false
        }
}
