package network.columba.app.reticulum.protocol

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
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
    val identifiedNomadnetLinks: MutableSet<String> =
        java.util.concurrent.ConcurrentHashMap
            .newKeySet()

    @Volatile var nomadnetCancelled = false

    val requestStatusFlow = MutableStateFlow("idle")
    val downloadProgressFlow = MutableStateFlow(0f)

    suspend fun requestNomadnetPage(
        destinationHash: String,
        path: String,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): Result<ReticulumProtocol.NomadnetPageResult> =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                nomadnetCancelled = false
                requestStatusFlow.value = "connecting"
                downloadProgressFlow.value = 0f

                val destBytes = destinationHash.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
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
        return Pair(link, reusedActiveLink)
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

    private fun sendPageRequest(
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
        responseLatch.await(requestTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)
        Log.i(TAG, "NomadNet: latch returned gotResponse=${responseBytes != null}, responseBytes=${responseBytes?.size}, error=$responseError")

        return PageResponse(responseBytes, responseMetadata, responseError)
    }

    private fun buildPageResult(
        data: ByteArray,
        metadata: ByteArray?,
        safePath: String,
    ): ReticulumProtocol.NomadnetPageResult {
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
            return ReticulumProtocol.NomadnetPageResult(
                content = "",
                path = safePath,
                type = "file",
                filePath = outFile.absolutePath,
                fileName = fileName,
                fileSize = data.size.toLong(),
            )
        }
        return ReticulumProtocol.NomadnetPageResult(
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

            val linkIdHex = link.linkId.joinToString("") { "%02x".format(it) }
            // add() returns false if the link was already identified — use it as an
            // atomic check-then-act so concurrent callers can't both pass the guard.
            if (!identifiedNomadnetLinks.add(linkIdHex)) {
                return@runCatching true
            }

            link.identify(identity)
            false
        }
}
