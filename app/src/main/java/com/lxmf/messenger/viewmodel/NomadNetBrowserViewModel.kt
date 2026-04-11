package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.micron.MicronDocument
import com.lxmf.messenger.micron.MicronParser
import com.lxmf.messenger.nomadnet.NomadNetPageCache
import com.lxmf.messenger.nomadnet.PartialManager
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
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
            private const val MAX_HISTORY_SIZE = 50
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

        private val _isPullRefreshing = MutableStateFlow(false)
        val isPullRefreshing: StateFlow<Boolean> = _isPullRefreshing.asStateFlow()

        data class DownloadState(
            val isActive: Boolean = false,
            val progress: Float = 0f,
            val fileName: String = "",
            val filePath: String? = null,
            val fileSize: Long = 0L,
            val error: String? = null,
        )

        private val _downloadState = MutableStateFlow(DownloadState())
        val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()

        private val _navigationEvent = MutableSharedFlow<NavigationEvent>()
        val navigationEvent: SharedFlow<NavigationEvent> = _navigationEvent

        fun clearIdentifyError() {
            _identifyError.value = null
        }

        private val history = mutableListOf<HistoryEntry>()
        private val _canGoBack = MutableStateFlow(false)
        val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

        @Volatile
        private var currentNodeHash = ""
        private var lastFetchNodeHash = ""
        private var lastFetchPath = DEFAULT_PATH
        private var lastFetchFormDataJson: String? = null

        @Volatile
        private var fetchEpoch = 0

        @Volatile
        private var statusCollectionJob: kotlinx.coroutines.Job? = null

        @Volatile
        private var progressCollectionJob: kotlinx.coroutines.Job? = null

        private val partialManager: PartialManager by lazy {
            PartialManager(
                protocol = reticulumProtocol,
                scope = viewModelScope,
                currentNodeHash = { currentNodeHash },
                formFields = { _formFields.value },
            )
        }

        val partialStates: StateFlow<Map<String, PartialManager.PartialState>>
            get() = partialManager.states

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

            // Push current page to history so back button works after URL bar navigation
            pushCurrentPageToHistory()
            loadPage(nodeHash.lowercase(), path)
        }

        fun loadPage(
            destinationHash: String,
            path: String = DEFAULT_PATH,
        ) {
            partialManager.clear()
            if (destinationHash != currentNodeHash) {
                _isIdentified.value = false
            }
            currentNodeHash = destinationHash
            _formFields.value = emptyMap()

            // Check cache before showing loading spinner
            val cached = pageCache.get(destinationHash, path)
            if (cached != null) {
                fetchEpoch++ // Invalidate any in-flight request
                stopStatusCollection()
                stopProgressCollection()
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
                pids.forEach { partialManager.reloadPartial(it) }
                return
            }

            // Handle lxmf@ links — emit navigation event instead of page navigation
            if (destination.startsWith("lxmf@")) {
                val hash = destination.removePrefix("lxmf@")
                viewModelScope.launch { _navigationEvent.emit(NavigationEvent.OpenConversation(hash)) }
                return
            }

            // Save current page to history (with document for instant back-nav)
            pushCurrentPageToHistory()

            partialManager.clear()

            // Collect form field values for submission.
            // NomadNet link fields can be:
            //   - "fieldname" → look up value from form fields
            //   - "key=value" → inline variable (sent as "var_key")
            //   - "*" → submit all form fields
            val isFormSubmission = fieldNames.isNotEmpty()
            val formDataJson =
                if (isFormSubmission) {
                    val data = JSONObject()
                    val submitAll = "*" in fieldNames
                    for (fieldEntry in fieldNames) {
                        if (fieldEntry == "*") continue
                        if ("=" in fieldEntry) {
                            // Inline variable: "key=value" → sent as "var_key"
                            val eqIdx = fieldEntry.indexOf('=')
                            val key = fieldEntry.substring(0, eqIdx)
                            val value = fieldEntry.substring(eqIdx + 1)
                            data.put("var_$key", value)
                        } else {
                            // Form field reference: look up value from form state
                            val value = _formFields.value[fieldEntry] ?: ""
                            data.put(fieldEntry, value)
                        }
                    }
                    if (submitAll) {
                        for ((key, value) in _formFields.value) {
                            if (!data.has(key)) {
                                data.put(key, value)
                            }
                        }
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
            if (path.startsWith("/file/")) {
                downloadFile(nodeHash, path)
            } else if (isFormSubmission) {
                submitFormAndNavigate(nodeHash, path, formDataJson!!)
            } else {
                // Non-form link: check cache first
                val cached = pageCache.get(nodeHash, path)
                if (cached != null) {
                    fetchEpoch++ // Invalidate any in-flight request
                    stopStatusCollection()
                    stopProgressCollection()
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
            val epoch = ++fetchEpoch
            stopProgressCollection()
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
            lastFetchFormDataJson = formDataJson
            _browserState.value = BrowserState.Loading("Requesting page...")
            startStatusCollection(epoch)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        reticulumProtocol.requestNomadnetPage(
                            destinationHash = nodeHash,
                            path = path,
                            formDataJson = formDataJson,
                            timeoutSeconds = PAGE_TIMEOUT_SECONDS,
                        )

                    stopStatusCollection(epoch)

                    if (fetchEpoch != epoch) return@launch

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
                    stopStatusCollection(epoch)
                    if (fetchEpoch != epoch) return@launch
                    Log.e(TAG, "Error navigating", e)
                    _browserState.value = BrowserState.Error(e.message ?: "Unknown error")
                }
            }
        }

        private fun downloadFile(
            nodeHash: String,
            path: String,
        ) {
            val downloadEpoch = ++fetchEpoch
            stopStatusCollection()
            startProgressCollection(downloadEpoch)
            _downloadState.value = DownloadState(isActive = true, fileName = path.substringAfterLast("/"))
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        reticulumProtocol.requestNomadnetPage(
                            destinationHash = nodeHash,
                            path = path,
                            timeoutSeconds = PAGE_TIMEOUT_SECONDS * 2,
                        )

                    stopProgressCollection(downloadEpoch)

                    // If user cancelled while download was in progress, don't update state
                    if (fetchEpoch != downloadEpoch) return@launch

                    result.fold(
                        onSuccess = { pageResult ->
                            if (pageResult.type == "file") {
                                _downloadState.value =
                                    DownloadState(
                                        isActive = false,
                                        progress = 1f,
                                        fileName = pageResult.fileName ?: path.substringAfterLast("/"),
                                        filePath = pageResult.filePath,
                                        fileSize = pageResult.fileSize,
                                    )
                            } else {
                                // Unexpected page response for /file/ path — show the page
                                _downloadState.value = DownloadState()
                                currentNodeHash = nodeHash
                                val document = MicronParser.parse(pageResult.content)
                                emitPageLoaded(document, pageResult.path, nodeHash)
                            }
                        },
                        onFailure = { error ->
                            _downloadState.update {
                                it.copy(isActive = false, error = error.message ?: "Download failed")
                            }
                        },
                    )
                } catch (e: Exception) {
                    stopProgressCollection(downloadEpoch)
                    Log.e(TAG, "Error downloading file", e)
                    _downloadState.update {
                        it.copy(isActive = false, error = e.message ?: "Download failed")
                    }
                }
            }
        }

        fun cancelDownload() {
            fetchEpoch++
            stopProgressCollection()
            _downloadState.value = DownloadState()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    reticulumProtocol.cancelNomadnetPageRequest()
                } catch (e: Exception) {
                    Log.e(TAG, "Error cancelling download", e)
                }
            }
        }

        fun clearDownload() {
            _downloadState.value = DownloadState()
        }

        fun goBack(): Boolean {
            if (history.isEmpty()) return false

            // Invalidate any in-flight request so its result doesn't overwrite the page we're navigating back to
            fetchEpoch++
            stopStatusCollection()
            stopProgressCollection()

            partialManager.clear()
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
                _isPullRefreshing.value = true
                partialManager.clear()
                // Bypass cache read, but still cache the fresh response
                fetchPage(currentState.nodeHash, currentState.path, cacheResponse = true)
            }
        }

        /** Retry the last failed network fetch, preserving form data if applicable. */
        fun retry() {
            if (lastFetchNodeHash.isNotEmpty()) {
                val formData = lastFetchFormDataJson
                if (formData != null) {
                    submitFormAndNavigate(lastFetchNodeHash, lastFetchPath, formData)
                } else {
                    loadPage(lastFetchNodeHash, lastFetchPath)
                }
            }
        }

        fun cancelLoading() {
            val epoch = ++fetchEpoch
            stopStatusCollection()
            stopProgressCollection()
            _browserState.value = BrowserState.Error("Cancelled")
            _isPullRefreshing.value = false
            viewModelScope.launch(Dispatchers.IO) {
                // Only send cancel if no new fetch has started since we were called
                if (fetchEpoch == epoch) {
                    try {
                        reticulumProtocol.cancelNomadnetPageRequest()
                    } catch (e: Exception) {
                        Log.e(TAG, "Error cancelling", e)
                    }
                }
            }
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
                    reticulumProtocol.identifyNomadnetLink(nodeHash).fold(
                        onSuccess = { alreadyIdentified ->
                            _isIdentified.value = true
                            if (!alreadyIdentified) refresh()
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

        override fun onCleared() {
            super.onCleared()
            stopStatusCollection()
            stopProgressCollection()
            // Cancel any in-flight Python page request so the IO thread isn't blocked
            // for up to PAGE_TIMEOUT_SECONDS after the user navigates away.
            // Use NonCancellable because viewModelScope is already cancelled at this point.
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                try {
                    reticulumProtocol.cancelNomadnetPageRequest()
                } catch (_: Exception) {
                    // Best-effort cancellation — service may already be unbound
                }
            }
        }

        /** Push the current page onto the history stack for back-navigation. */
        private fun pushCurrentPageToHistory() {
            val currentState = _browserState.value
            if (currentState is BrowserState.PageLoaded) {
                // Evict oldest entry if at capacity to bound memory usage
                if (history.size >= MAX_HISTORY_SIZE) {
                    history.removeAt(0)
                }
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
        }

        /**
         * Emit a [BrowserState.PageLoaded] and trigger partial detection.
         */
        private fun emitPageLoaded(
            document: MicronDocument,
            path: String,
            nodeHash: String,
        ) {
            _isPullRefreshing.value = false
            _browserState.value =
                BrowserState.PageLoaded(
                    document = document,
                    path = path,
                    nodeHash = nodeHash,
                )
            partialManager.detectAndLoad(document)
        }

        private fun formatNomadnetStatus(status: String): String =
            when (status) {
                "idle" -> "Requesting page..."
                "connecting" -> "Connecting..."
                "looking up path" -> "Looking up path..."
                "establishing link" -> "Establishing link..."
                "requesting page" -> "Requesting page..."
                "downloading" -> "Downloading..."
                "complete" -> "Finishing..."
                "failed" -> "Request failed"
                "cancelled" -> "Cancelled"
                else -> status.replaceFirstChar { it.uppercase() }
            }

        /**
         * Start collecting NomadNet request status updates from the protocol.
         */
        private fun startStatusCollection(epoch: Int) {
            statusCollectionJob?.cancel()
            statusCollectionJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        reticulumProtocol.nomadnetRequestStatusFlow.collect { status ->
                            if (fetchEpoch != epoch) return@collect
                            if (status.isNotEmpty()) {
                                _browserState.value = BrowserState.Loading(formatNomadnetStatus(status))
                            }
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Normal shutdown
                    } catch (e: Exception) {
                        Log.d(TAG, "Status collection stopped: ${e.message}")
                    }
                }
        }

        private fun stopStatusCollection(epoch: Int? = null) {
            if (epoch != null && fetchEpoch != epoch) return
            statusCollectionJob?.cancel()
            statusCollectionJob = null
        }

        private fun startProgressCollection(epoch: Int) {
            progressCollectionJob?.cancel()
            progressCollectionJob =
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        reticulumProtocol.nomadnetDownloadProgressFlow.collect { progress ->
                            if (fetchEpoch != epoch) return@collect
                            _downloadState.update { it.copy(progress = progress) }
                        }
                    } catch (_: kotlinx.coroutines.CancellationException) {
                        // Normal shutdown
                    } catch (e: Exception) {
                        Log.d(TAG, "Progress collection stopped: ${e.message}")
                    }
                }
        }

        private fun stopProgressCollection(epoch: Int? = null) {
            if (epoch != null && fetchEpoch != epoch) return
            progressCollectionJob?.cancel()
            progressCollectionJob = null
        }

        /**
         * Fetch a page from the network, optionally caching the response.
         */
        private fun fetchPage(
            nodeHash: String,
            path: String,
            cacheResponse: Boolean,
        ) {
            val epoch = ++fetchEpoch
            stopProgressCollection()
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
            lastFetchFormDataJson = null
            _browserState.value = BrowserState.Loading("Requesting page...")
            startStatusCollection(epoch)

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        reticulumProtocol.requestNomadnetPage(
                            destinationHash = nodeHash,
                            path = path,
                            timeoutSeconds = PAGE_TIMEOUT_SECONDS,
                        )

                    stopStatusCollection(epoch)

                    // If user navigated away (back, new link) while we were loading,
                    // discard this stale result to avoid overwriting the current page
                    if (fetchEpoch != epoch) return@launch

                    result.fold(
                        onSuccess = { pageResult ->
                            if (pageResult.type == "file") {
                                // Unexpected file response on a page path —
                                // clear loading state so screen doesn't get stuck
                                _isPullRefreshing.value = false
                                _browserState.value =
                                    BrowserState.Error("Server returned a file instead of a page")
                                _downloadState.value =
                                    DownloadState(
                                        isActive = false,
                                        progress = 1f,
                                        fileName = pageResult.fileName ?: path.substringAfterLast("/"),
                                        filePath = pageResult.filePath,
                                        fileSize = pageResult.fileSize,
                                    )
                            } else {
                                currentNodeHash = nodeHash
                                val document = MicronParser.parse(pageResult.content)
                                if (cacheResponse) {
                                    pageCache.put(nodeHash, pageResult.path, pageResult.content, document.cacheTime)
                                }
                                emitPageLoaded(document, pageResult.path, nodeHash)
                            }
                        },
                        onFailure = { error ->
                            _isPullRefreshing.value = false
                            _browserState.value =
                                BrowserState.Error(
                                    error.message ?: "Unknown error",
                                )
                        },
                    )
                } catch (e: Exception) {
                    stopStatusCollection(epoch)
                    if (fetchEpoch != epoch) return@launch
                    _isPullRefreshing.value = false
                    Log.e(TAG, "Error loading page", e)
                    _browserState.value = BrowserState.Error(e.message ?: "Unknown error")
                }
            }
        }
    }
