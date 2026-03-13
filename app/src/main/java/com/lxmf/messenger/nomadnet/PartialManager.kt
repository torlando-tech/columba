package com.lxmf.messenger.nomadnet

import android.util.Log
import com.lxmf.messenger.micron.MicronDocument
import com.lxmf.messenger.micron.MicronElement
import com.lxmf.messenger.micron.MicronParser
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import com.lxmf.messenger.util.DestinationHashValidator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Manages async loading and auto-refresh of Micron partials.
 *
 * Not a ViewModel — owned by [NomadNetBrowserViewModel] and scoped to its lifecycle.
 * Each partial is fetched from the network, parsed, and its state exposed via [states].
 */
class PartialManager(
    private val protocol: ServiceReticulumProtocol,
    private val scope: CoroutineScope,
    private val currentNodeHash: () -> String,
    private val formFields: () -> Map<String, String>,
) {
    data class PartialState(
        val url: String,
        val partialId: String?,
        val status: Status,
        val document: MicronDocument?,
        val refreshInterval: Int?,
    ) {
        enum class Status { LOADING, LOADED, ERROR }
    }

    companion object {
        private const val TAG = "PartialManager"
        private const val PARTIAL_TIMEOUT_SECONDS = 30f
        private const val MAX_CONCURRENT_FETCHES = 2
        private const val DEFAULT_PATH = "/page/index.mu"

        /**
         * Resolve a NomadNet URL into (nodeHash, path).
         * Shared between PartialManager and NomadNetBrowserViewModel.
         */
        fun resolveNomadNetUrl(
            destination: String,
            currentNodeHash: String,
        ): Pair<String, String> {
            if (destination.contains("@")) {
                return resolveAtSyntax(destination, currentNodeHash)
            }
            return when {
                destination.startsWith(":") ->
                    currentNodeHash to destination.drop(1).ifEmpty { DEFAULT_PATH }
                destination.startsWith("/") ->
                    currentNodeHash to destination
                destination.contains(":") ->
                    resolveColonSyntax(destination, currentNodeHash)
                DestinationHashValidator.isValid(destination) ->
                    destination.lowercase() to DEFAULT_PATH
                else ->
                    currentNodeHash to destination
            }
        }

        private fun resolveAtSyntax(
            destination: String,
            currentNodeHash: String,
        ): Pair<String, String> {
            val afterAt = destination.substringAfter("@")
            val colonIdx = afterAt.indexOf(':')
            return if (colonIdx >= 32) {
                afterAt.substring(0, 32) to afterAt.substring(colonIdx + 1).ifEmpty { DEFAULT_PATH }
            } else if (afterAt.length >= 32) {
                val hashPart = afterAt.take(32)
                val pathPart = afterAt.drop(32)
                val path =
                    if (pathPart.startsWith(":")) {
                        pathPart.drop(1).ifEmpty { DEFAULT_PATH }
                    } else {
                        pathPart.ifEmpty { DEFAULT_PATH }
                    }
                hashPart to path
            } else {
                currentNodeHash to destination
            }
        }

        private fun resolveColonSyntax(
            destination: String,
            currentNodeHash: String,
        ): Pair<String, String> {
            val colonIdx = destination.indexOf(':')
            val hashPart = destination.substring(0, colonIdx)
            val pathPart = destination.substring(colonIdx + 1)
            return if (DestinationHashValidator.isValid(hashPart)) {
                hashPart.lowercase() to pathPart.ifEmpty { DEFAULT_PATH }
            } else {
                currentNodeHash to destination
            }
        }
    }

    private val _states = MutableStateFlow<Map<String, PartialState>>(emptyMap())
    val states: StateFlow<Map<String, PartialState>> = _states.asStateFlow()

    private val jobs = mutableMapOf<String, Job>()
    private val fetchSemaphore = Semaphore(MAX_CONCURRENT_FETCHES)

    /**
     * Scan a document for [MicronElement.Partial] elements and begin loading each.
     */
    fun detectAndLoad(document: MicronDocument) {
        for ((lineIndex, line) in document.lines.withIndex()) {
            val partial = line.elements.firstOrNull() as? MicronElement.Partial ?: continue
            val key = partial.partialId?.let { "pid:$it" } ?: "pos:$lineIndex"

            // Don't re-launch if already tracked
            if (!jobs.containsKey(key)) {
                _states.update {
                    it + (
                        key to
                            PartialState(
                                url = partial.url,
                                partialId = partial.partialId,
                                status = PartialState.Status.LOADING,
                                document = null,
                                refreshInterval = partial.refreshInterval,
                            )
                    )
                }

                jobs[key] =
                    scope.launch(Dispatchers.IO) {
                        loadPartial(key, partial)
                    }
            }
        }
    }

    /**
     * Reload a specific partial by its pid (triggered by `p:<pid>` links).
     */
    fun reloadPartial(partialId: String) {
        val key = "pid:$partialId"
        val existing = _states.value[key] ?: return

        // Cancel existing job if running
        jobs[key]?.cancel()

        _states.update {
            it + (
                key to
                    existing.copy(
                        status = PartialState.Status.LOADING,
                    )
            )
        }

        jobs[key] =
            scope.launch(Dispatchers.IO) {
                fetchAndUpdate(key, existing.url, existing.refreshInterval)
            }
    }

    /**
     * Cancel all active partial jobs and clear state.
     */
    fun clear() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        _states.value = emptyMap()
    }

    private suspend fun loadPartial(
        key: String,
        partial: MicronElement.Partial,
    ) {
        fetchAndUpdate(key, partial.url, partial.refreshInterval)
    }

    private suspend fun fetchAndUpdate(
        key: String,
        url: String,
        refreshInterval: Int?,
    ) {
        try {
            while (true) {
                fetchSemaphore.withPermit {
                    val (nodeHash, path) = resolveNomadNetUrl(url, currentNodeHash())

                    val formDataJson = buildFormDataJson()

                    val result =
                        protocol.requestNomadnetPage(
                            destinationHash = nodeHash,
                            path = path,
                            formDataJson = formDataJson,
                            timeoutSeconds = PARTIAL_TIMEOUT_SECONDS,
                        )

                    result.fold(
                        onSuccess = { pageResult ->
                            val doc = MicronParser.parse(pageResult.content)
                            _states.update {
                                it + (
                                    key to
                                        PartialState(
                                            url = url,
                                            partialId = if (key.startsWith("pid:")) key.substringAfter("pid:") else null,
                                            status = PartialState.Status.LOADED,
                                            document = doc,
                                            refreshInterval = refreshInterval,
                                        )
                                )
                            }
                        },
                        onFailure = { error ->
                            Log.w(TAG, "Partial fetch failed for $url: ${error.message}")
                            _states.update {
                                it + (
                                    key to
                                        PartialState(
                                            url = url,
                                            partialId = if (key.startsWith("pid:")) key.substringAfter("pid:") else null,
                                            status = PartialState.Status.ERROR,
                                            document = null,
                                            refreshInterval = refreshInterval,
                                        )
                                )
                            }
                        },
                    )
                }

                // If no refresh interval, we're done
                if (refreshInterval == null) return
                delay(refreshInterval * 1000L)
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            // Normal cancellation from clear() or navigation
        } catch (e: Exception) {
            Log.e(TAG, "Partial load error for $url", e)
            _states.update {
                it + (
                    key to
                        PartialState(
                            url = url,
                            partialId = if (key.startsWith("pid:")) key.substringAfter("pid:") else null,
                            status = PartialState.Status.ERROR,
                            document = null,
                            refreshInterval = refreshInterval,
                        )
                )
            }
        }
    }

    private fun buildFormDataJson(): String? {
        val fields = formFields()
        if (fields.isEmpty()) return null
        val json = JSONObject()
        for ((k, v) in fields) {
            json.put(k, v)
        }
        return json.toString()
    }
}
