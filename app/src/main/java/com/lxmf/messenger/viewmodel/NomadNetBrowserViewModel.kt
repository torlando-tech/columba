package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.micron.MicronDocument
import com.lxmf.messenger.micron.MicronParser
import com.lxmf.messenger.nomadnet.NomadNetPageCache
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@HiltViewModel
class NomadNetBrowserViewModel
    @Inject
    constructor(
        private val reticulumProtocol: ReticulumProtocol,
        private val pageCache: NomadNetPageCache,
    ) : ViewModel() {
        companion object {
            private const val TAG = "NomadNetBrowserVM"
            private const val DEFAULT_PATH = "/page/index.mu"
            private const val PAGE_TIMEOUT_SECONDS = 60f
        }

        sealed class BrowserState {
            data object Initial : BrowserState()

            data class Loading(
                val statusMessage: String,
            ) : BrowserState()

            data class PageLoaded(
                val document: MicronDocument,
                val path: String,
                val nodeHash: String,
            ) : BrowserState()

            data class Error(
                val message: String,
            ) : BrowserState()
        }

        enum class RenderingMode {
            MONOSPACE_SCROLL,
            MONOSPACE_ZOOM,
            PROPORTIONAL_WRAP,
        }

        private data class HistoryEntry(
            val nodeHash: String,
            val path: String,
            val formFields: Map<String, String>,
            val document: MicronDocument,
        )

        private val _browserState = MutableStateFlow<BrowserState>(BrowserState.Initial)
        val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

        private val _formFields = MutableStateFlow<Map<String, String>>(emptyMap())
        val formFields: StateFlow<Map<String, String>> = _formFields.asStateFlow()

        private val _renderingMode = MutableStateFlow(RenderingMode.MONOSPACE_SCROLL)
        val renderingMode: StateFlow<RenderingMode> = _renderingMode.asStateFlow()

        private val history = mutableListOf<HistoryEntry>()
        private var currentNodeHash = ""

        val canGoBack: Boolean get() = history.isNotEmpty()

        fun loadPage(
            destinationHash: String,
            path: String = DEFAULT_PATH,
        ) {
            currentNodeHash = destinationHash
            _formFields.value = emptyMap()

            // Check cache before showing loading spinner
            val cached = pageCache.get(destinationHash, path)
            if (cached != null) {
                val document = MicronParser.parse(cached)
                _browserState.value =
                    BrowserState.PageLoaded(
                        document = document,
                        path = path,
                        nodeHash = destinationHash,
                    )
                return
            }

            fetchPage(destinationHash, path, cacheResponse = true)
        }

        fun navigateToLink(
            destination: String,
            fieldNames: List<String>,
        ) {
            // Save current page to history (with document for instant back-nav)
            val currentState = _browserState.value
            if (currentState is BrowserState.PageLoaded) {
                history.add(
                    HistoryEntry(
                        nodeHash = currentState.nodeHash,
                        path = currentState.path,
                        formFields = _formFields.value.toMap(),
                        document = currentState.document,
                    ),
                )
            }

            // Collect form field values for submission
            val isFormSubmission = fieldNames.isNotEmpty()
            val formDataJson =
                if (isFormSubmission) {
                    val data = JSONObject()
                    for (fieldName in fieldNames) {
                        val value = _formFields.value[fieldName] ?: ""
                        data.put(fieldName, value)
                    }
                    data.toString()
                } else {
                    null
                }

            // Parse destination per NomadNet URL format:
            //   ":/page/path.mu" = same node (colon with empty hash)
            //   "/page/path.mu" = same node (direct path)
            //   "<32-char-hash>:/page/path.mu" = cross-node by hash
            //   "<32-char-hash>" = cross-node, default path
            //   "type@<hash>:<path>" = cross-type link (e.g., lxmf@hash)
            val nodeHash: String
            val path: String
            if (destination.contains("@")) {
                // Cross-type link: "type@hash:path" or "type@hash/path"
                val afterAt = destination.substringAfter("@")
                val colonIdx = afterAt.indexOf(':')
                if (colonIdx >= 32) {
                    // hash:path format
                    nodeHash = afterAt.substring(0, 32)
                    path = afterAt.substring(colonIdx + 1).ifEmpty { DEFAULT_PATH }
                } else if (afterAt.length >= 32) {
                    val hashPart = afterAt.take(32)
                    val pathPart = afterAt.drop(32)
                    nodeHash = hashPart
                    path = if (pathPart.startsWith(":")) pathPart.drop(1).ifEmpty { DEFAULT_PATH } else pathPart.ifEmpty { DEFAULT_PATH }
                } else {
                    nodeHash = currentNodeHash
                    path = destination
                }
            } else if (destination.startsWith(":")) {
                // Same-node link with colon prefix: ":/page/path.mu"
                nodeHash = currentNodeHash
                path = destination.drop(1).ifEmpty { DEFAULT_PATH }
            } else if (destination.startsWith("/")) {
                // Same-node link with direct path: "/page/path.mu"
                nodeHash = currentNodeHash
                path = destination
            } else if (destination.contains(":")) {
                // Cross-node link: "hash:/page/path.mu" or "hash:path"
                val colonIdx = destination.indexOf(':')
                val hashPart = destination.substring(0, colonIdx)
                val pathPart = destination.substring(colonIdx + 1)
                if (hashPart.length == 32 && hashPart.all { it in '0'..'9' || it in 'a'..'f' }) {
                    nodeHash = hashPart
                    path = pathPart.ifEmpty { DEFAULT_PATH }
                } else {
                    // Not a valid hash, treat as same-node path
                    nodeHash = currentNodeHash
                    path = destination
                }
            } else {
                // Bare hash or unknown format
                nodeHash = currentNodeHash
                path = destination
            }

            _formFields.value = emptyMap()

            // Form submissions always fetch fresh (response depends on submitted data)
            if (isFormSubmission) {
                _browserState.value = BrowserState.Loading("Requesting page...")
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        val protocol = reticulumProtocol as? ServiceReticulumProtocol
                        if (protocol == null) {
                            _browserState.value = BrowserState.Error("Service not available")
                            return@launch
                        }

                        val result =
                            protocol.requestNomadnetPage(
                                destinationHash = nodeHash,
                                path = path,
                                formDataJson = formDataJson,
                                timeoutSeconds = PAGE_TIMEOUT_SECONDS,
                            )

                        result.fold(
                            onSuccess = { pageResult ->
                                currentNodeHash = nodeHash
                                val document = MicronParser.parse(pageResult.content)
                                // Don't cache form responses
                                _browserState.value =
                                    BrowserState.PageLoaded(
                                        document = document,
                                        path = pageResult.path,
                                        nodeHash = nodeHash,
                                    )
                            },
                            onFailure = { error ->
                                _browserState.value =
                                    BrowserState.Error(
                                        error.message ?: "Unknown error",
                                    )
                            },
                        )
                    } catch (e: Exception) {
                        Log.e(TAG, "Error navigating", e)
                        _browserState.value = BrowserState.Error(e.message ?: "Unknown error")
                    }
                }
                return
            }

            // Non-form link: check cache first
            val cached = pageCache.get(nodeHash, path)
            if (cached != null) {
                currentNodeHash = nodeHash
                val document = MicronParser.parse(cached)
                _browserState.value =
                    BrowserState.PageLoaded(
                        document = document,
                        path = path,
                        nodeHash = nodeHash,
                    )
                return
            }

            fetchPage(nodeHash, path, cacheResponse = true)
        }

        fun goBack(): Boolean {
            if (history.isEmpty()) return false

            val entry = history.removeLast()
            currentNodeHash = entry.nodeHash
            _formFields.value = entry.formFields
            // Instant back-navigation using the stored document
            _browserState.value =
                BrowserState.PageLoaded(
                    document = entry.document,
                    path = entry.path,
                    nodeHash = entry.nodeHash,
                )
            return true
        }

        fun refresh() {
            val currentState = _browserState.value
            if (currentState is BrowserState.PageLoaded) {
                // Bypass cache read, but still cache the fresh response
                fetchPage(currentState.nodeHash, currentState.path, cacheResponse = true)
            }
        }

        fun cancelLoading() {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    (reticulumProtocol as? ServiceReticulumProtocol)?.cancelNomadnetPageRequest()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling", e)
                }
            }
            _browserState.value = BrowserState.Error("Cancelled")
        }

        fun updateField(
            name: String,
            value: String,
        ) {
            _formFields.update { it + (name to value) }
        }

        fun setRenderingMode(mode: RenderingMode) {
            _renderingMode.value = mode
        }

        /**
         * Fetch a page from the network, optionally caching the response.
         */
        private fun fetchPage(
            nodeHash: String,
            path: String,
            cacheResponse: Boolean,
        ) {
            _browserState.value = BrowserState.Loading("Requesting page...")

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val protocol = reticulumProtocol as? ServiceReticulumProtocol
                    if (protocol == null) {
                        _browserState.value = BrowserState.Error("Service not available")
                        return@launch
                    }

                    _browserState.value = BrowserState.Loading("Connecting to node...")

                    val result =
                        protocol.requestNomadnetPage(
                            destinationHash = nodeHash,
                            path = path,
                            timeoutSeconds = PAGE_TIMEOUT_SECONDS,
                        )

                    result.fold(
                        onSuccess = { pageResult ->
                            currentNodeHash = nodeHash
                            val document = MicronParser.parse(pageResult.content)
                            if (cacheResponse) {
                                pageCache.put(nodeHash, pageResult.path, pageResult.content, document.cacheTime)
                            }
                            _browserState.value =
                                BrowserState.PageLoaded(
                                    document = document,
                                    path = pageResult.path,
                                    nodeHash = nodeHash,
                                )
                        },
                        onFailure = { error ->
                            _browserState.value =
                                BrowserState.Error(
                                    error.message ?: "Unknown error",
                                )
                        },
                    )
                } catch (e: Exception) {
                    Log.e(TAG, "Error loading page", e)
                    _browserState.value = BrowserState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }
