package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.micron.MicronDocument
import com.lxmf.messenger.micron.MicronParser
import com.lxmf.messenger.nomadnet.NomadNetPageCache
import com.lxmf.messenger.nomadnet.PartialManager
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONObject
import javax.inject.Inject

@Suppress("TooManyFunctions") // ViewModel has 15 UI-interaction methods at the threshold
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

        sealed class NavigationEvent {
            data class OpenConversation(
                val destinationHash: String,
            ) : NavigationEvent()
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

        private val _isIdentified = MutableStateFlow(false)
        val isIdentified: StateFlow<Boolean> = _isIdentified.asStateFlow()

        private val _identifyInProgress = MutableStateFlow(false)
        val identifyInProgress: StateFlow<Boolean> = _identifyInProgress.asStateFlow()

        private val _identifyError = MutableStateFlow<String?>(null)
        val identifyError: StateFlow<String?> = _identifyError.asStateFlow()

        private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
        val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent

        fun clearIdentifyError() {
            _identifyError.value = null
        }

        private val history = mutableListOf<HistoryEntry>()
        private val _canGoBack = MutableStateFlow(false)
        val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()
        private var currentNodeHash = ""
        private var lastFetchNodeHash = ""
        private var lastFetchPath = DEFAULT_PATH

        private val partialManager: PartialManager? by lazy {
            (reticulumProtocol as? ServiceReticulumProtocol)?.let { protocol ->
                PartialManager(
                    protocol = protocol,
                    scope = viewModelScope,
                    currentNodeHash = { currentNodeHash },
                    formFields = { _formFields.value },
                )
            }
        }

        private val emptyPartialStates: StateFlow<Map<String, PartialManager.PartialState>> =
            MutableStateFlow(emptyMap())

        val partialStates: StateFlow<Map<String, PartialManager.PartialState>>
            get() = partialManager?.states ?: emptyPartialStates

        /** Returns "nodeHash:/path" format for display in the URL bar. */
        fun getCurrentUrl(): String? {
            val state = browserState.value as? BrowserState.PageLoaded ?: return null
            return "${state.nodeHash}:${state.path}"
        }

        /** Returns "nomadnetwork://nodeHash:/path" for sharing. */
        fun getShareableUrl(): String? = getCurrentUrl()?.let { "nomadnetwork://$it" }

        /**
         * Parse user-edited URL and navigate. Supports:
         * - "hash:/path" or "hash" (navigate to page)
         * - "nomadnetwork://hash:/path" (strip scheme, navigate)
         * - "lxmf@hash" (emit OpenConversation event)
         */
        fun navigateToUrl(input: String) {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return

            // Strip nomadnetwork:// scheme if present
            val raw =
                if (trimmed.startsWith("nomadnetwork://")) {
                    trimmed.removePrefix("nomadnetwork://")
                } else {
                    trimmed
                }

            // Handle lxmf@ links
            if (raw.startsWith("lxmf@")) {
                val hash = raw.removePrefix("lxmf@")
                viewModelScope.launch { _navigationEvent.emit(NavigationEvent.OpenConversation(hash)) }
                return
            }

            // Split on first colon: "hash:/path" or just "hash"
            val colonIdx = raw.indexOf(':')
            val (nodeHash, path) =
                if (colonIdx > 0) {
                    raw.substring(0, colonIdx) to raw.substring(colonIdx + 1)
                } else {
                    raw to DEFAULT_PATH
                }

            loadPage(nodeHash.lowercase(), path)
        }

        fun loadPage(
            destinationHash: String,
            path: String = DEFAULT_PATH,
        ) {
            partialManager?.clear()
            if (destinationHash != currentNodeHash) {
                _isIdentified.value = false
            }
            currentNodeHash = destinationHash
            _formFields.value = emptyMap()

            // Check cache before showing loading spinner
            val cached = pageCache.get(destinationHash, path)
            if (cached != null) {
                val document = MicronParser.parse(cached)
                emitPageLoaded(document, path, destinationHash)
                return
            }

            fetchPage(destinationHash, path, cacheResponse = true)
        }

        fun navigateToLink(
            destination: String,
            fieldNames: List<String>,
        ) {
            // Handle partial reload links: p:<pid> or p:<pid1>|<pid2>
            if (destination.startsWith("p:")) {
                val pids = destination.substringAfter("p:").split("|")
                pids.forEach { partialManager?.reloadPartial(it) }
                return
            }

            // Handle lxmf@ links — emit navigation event instead of page navigation
            if (destination.startsWith("lxmf@")) {
                val hash = destination.removePrefix("lxmf@")
                viewModelScope.launch { _navigationEvent.emit(NavigationEvent.OpenConversation(hash)) }
                return
            }

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
                _canGoBack.value = true
            }

            partialManager?.clear()

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

            // Resolve destination URL using shared utility
            val (nodeHash, path) = PartialManager.resolveNomadNetUrl(destination, currentNodeHash)

            if (nodeHash != currentNodeHash) {
                _isIdentified.value = false
            }
            _formFields.value = emptyMap()

            // Form submissions always fetch fresh (response depends on submitted data)
            if (isFormSubmission) {
                submitFormAndNavigate(nodeHash, path, formDataJson!!)
            } else {
                // Non-form link: check cache first
                val cached = pageCache.get(nodeHash, path)
                if (cached != null) {
                    currentNodeHash = nodeHash
                    val document = MicronParser.parse(cached)
                    emitPageLoaded(document, path, nodeHash)
                } else {
                    fetchPage(nodeHash, path, cacheResponse = true)
                }
            }
        }

        private fun submitFormAndNavigate(
            nodeHash: String,
            path: String,
            formDataJson: String,
        ) {
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
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
                            emitPageLoaded(document, pageResult.path, nodeHash)
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
        }

        fun goBack(): Boolean {
            if (history.isEmpty()) return false

            partialManager?.clear()
            val entry = history.removeAt(history.lastIndex)
            _canGoBack.value = history.isNotEmpty()
            currentNodeHash = entry.nodeHash
            _formFields.value = entry.formFields
            // Instant back-navigation using the stored document
            emitPageLoaded(entry.document, entry.path, entry.nodeHash)
            return true
        }

        fun refresh() {
            val currentState = _browserState.value
            if (currentState is BrowserState.PageLoaded) {
                partialManager?.clear()
                // Bypass cache read, but still cache the fresh response
                fetchPage(currentState.nodeHash, currentState.path, cacheResponse = true)
            }
        }

        /** Retry the last failed network fetch. */
        fun retry() {
            if (lastFetchNodeHash.isNotEmpty()) {
                loadPage(lastFetchNodeHash, lastFetchPath)
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

        fun identifyToNode() {
            if (_identifyInProgress.value || _isIdentified.value) return
            val nodeHash = currentNodeHash
            if (nodeHash.isEmpty()) return

            _identifyInProgress.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val protocol =
                        reticulumProtocol as? ServiceReticulumProtocol
                            ?: error("Service not available")
                    protocol.identifyNomadnetLink(nodeHash).fold(
                        onSuccess = {
                            _isIdentified.value = true
                            refresh()
                        },
                        onFailure = { _identifyError.value = it.message ?: "Unknown error" },
                    )
                } catch (e: Exception) {
                    _identifyError.value = e.message ?: "Unknown error"
                } finally {
                    _identifyInProgress.value = false
                }
            }
        }

        /**
         * Emit a [BrowserState.PageLoaded] and trigger partial detection.
         */
        private fun emitPageLoaded(
            document: MicronDocument,
            path: String,
            nodeHash: String,
        ) {
            _browserState.value =
                BrowserState.PageLoaded(
                    document = document,
                    path = path,
                    nodeHash = nodeHash,
                )
            partialManager?.detectAndLoad(document)
        }

        /**
         * Fetch a page from the network, optionally caching the response.
         */
        private fun fetchPage(
            nodeHash: String,
            path: String,
            cacheResponse: Boolean,
        ) {
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
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
                            emitPageLoaded(document, pageResult.path, nodeHash)
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
