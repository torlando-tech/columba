package network.columba.app.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.columba.app.micron.MicronDocument
import network.columba.app.micron.MicronElement
import network.columba.app.micron.MicronParser
import network.columba.app.nomadnet.ImageLoadingMode
import network.columba.app.nomadnet.NomadNetImageCache
import network.columba.app.nomadnet.PageImageLoader
import network.columba.app.nomadnet.PageImageState
import network.columba.app.nomadnet.ParsedImageRef
import network.columba.app.nomadnet.NomadNetPageCache
import network.columba.app.nomadnet.PartialManager
import network.columba.app.nomadnet.buildNomadNetPersistPath
import network.columba.app.nomadnet.buildNomadNetRequestData
import network.columba.app.nomadnet.pageImageKey
import network.columba.app.nomadnet.splitNomadNetPathFields
import network.columba.app.repository.SettingsRepository
import network.columba.app.rns.api.RnsCore
import network.columba.app.rns.api.RnsNomadnet
import network.columba.app.rns.api.model.NetworkStatus
import javax.inject.Inject

@Suppress("TooManyFunctions") // ViewModel has 15 UI-interaction methods at the threshold
@HiltViewModel
class NomadNetBrowserViewModel
    @Inject
    constructor(
        private val nomadnet: RnsNomadnet,
        private val pageCache: NomadNetPageCache,
        private val imageCache: NomadNetImageCache,
        private val settingsRepository: SettingsRepository,
        private val rnsCore: RnsCore,
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
                val fieldTokens: List<String> = emptyList(),
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
            ;

            companion object {
                /** Maps a persisted enum name back to a RenderingMode, defaulting when absent/unknown. */
                fun fromName(name: String?): RenderingMode = entries.firstOrNull { it.name == name } ?: MONOSPACE_SCROLL
            }
        }

        private data class HistoryEntry(
            val nodeHash: String,
            val path: String,
            val formFields: Map<String, String>,
            val document: MicronDocument,
            val fieldTokens: List<String> = emptyList(),
        )

        private val _browserState = MutableStateFlow<BrowserState>(BrowserState.Initial)
        val browserState: StateFlow<BrowserState> = _browserState.asStateFlow()

        private val _formFields = MutableStateFlow<Map<String, String>>(emptyMap())
        val formFields: StateFlow<Map<String, String>> = _formFields.asStateFlow()

        private val _renderingMode = MutableStateFlow(RenderingMode.MONOSPACE_SCROLL)
        val renderingMode: StateFlow<RenderingMode> = _renderingMode.asStateFlow()

        // Guards the async startup restore from clobbering a selection the user
        // makes before the persisted value has been read back.
        @Volatile
        private var renderingModeUserSelected = false

        init {
            // Restore the user's last-selected rendering mode so it persists across sessions.
            viewModelScope.launch {
                val restored =
                    try {
                        RenderingMode.fromName(settingsRepository.nomadNetRenderingModeFlow.first())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        // Normal ViewModel teardown cancels the read; preserve the cancellation contract.
                        throw e
                    } catch (e: Exception) {
                        // DataStore I/O failure or corruption: fall back to the default rather than crash.
                        Log.w(TAG, "Failed to read persisted rendering mode; using default", e)
                        RenderingMode.MONOSPACE_SCROLL
                    }
                if (!renderingModeUserSelected) {
                    _renderingMode.value = restored
                }
            }
            // NOTE: the auto-identify set observation lives in its own init
            // block below (after the _autoIdentifyNodes declaration) so the
            // property is initialized before the collect writes to it; the
            // image-loading-mode restore likewise lives next to its field.
        }

        private val _isIdentified = MutableStateFlow(false)
        val isIdentified: StateFlow<Boolean> = _isIdentified.asStateFlow()

        // Destination hashes of nodes the user opted into auto-identification
        // for ("Always identify to this node"). Restored from DataStore in init;
        // the browser auto-identifies to these nodes on every page load.
        private val _autoIdentifyNodes = MutableStateFlow<Set<String>>(emptySet())
        val autoIdentifyNodes: StateFlow<Set<String>> = _autoIdentifyNodes.asStateFlow()

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
        /** Link-field tokens (backtick block) for the last fetch, for persist-path reconstruction. */
        private var lastFetchFieldTokens: List<String> = emptyList()

        init {
            // Observe the auto-identify node set reactively. Two responsibilities:
            //
            // 1. Mirror it into the UI state flow so the browser dialog and the
            //    Node Details card stay in sync with toggles made elsewhere.
            //    DataStore is the source of truth.
            //
            // 2. Push the set to the RNS backend so it can auto-identify a
            //    flagged node's link AT LINK-ESTABLISHMENT TIME - the Python
            //    equivalent of upstream Browser.link_established calling
            //    link.identify(...) when should_identify_on_connect(hash). The
            //    backend owns the timing, so there is no "identify before the
            //    link exists" window (the old ViewModel-side trigger raced the
            //    link and surfaced "No active link to this node" as a snackbar).
            //
            // The collector's first emission delivers the current DataStore
            // value, so the backend is synced before any page load for a
            // flagged node - even a fast cached first page (which is why the
            // sync lives here and not just in loadPage).
            //
            // When a node is newly flagged, re-fetch its page for identified
            // content, but ONLY as a safe GET:
            //
            //  - LOADED page: re-fetch via refresh() when the page's own field
            //    tokens do NOT resolve to request data (a plain page). If the
            //    tokens DO resolve to data, the page is a form/var-bearing page
            //    and refresh() would re-POST it (double-firing its side
            //    effects) - skip it. The form's own fetch already established
            //    the link and identified at establishment. Keying off the
            //    page's own field tokens (not stale lastFetch* state) makes
            //    this correct for Back-restored pages at any path.
            //  - LOADING page: arm pendingIdentifyRefreshFor so emitPageLoaded
            //    re-fetches once it lands - unless the in-flight request is a
            //    form submission (accurately signalled by lastFetch* while
            //    loading), which already identifies at establishment.
            //
            // This matches the upstream model: the backend identifies the link
            // (Browser.link_established), and the page content is fetched GET
            // to reflect identification - it is never re-POSTed.
            viewModelScope.launch {
                var previous: Set<String> = emptySet()
                settingsRepository.nomadNetAutoIdentifyNodesFlow.collect { nodes ->
                    _autoIdentifyNodes.value = nodes
                    runCatching {
                        nomadnet.setIdentifyOnConnectNodes(nodes)
                    }.onFailure {
                        Log.w(TAG, "Failed to sync auto-identify set to backend", it)
                    }
                    val newlyFlagged = nodes - previous
                    if (newlyFlagged.isNotEmpty() && currentNodeHash in newlyFlagged) {
                        val displayedPage = _browserState.value
                        if (displayedPage is BrowserState.PageLoaded) {
                            // Re-fetch as a safe GET for identified content.
                            // identifyRefresh skips form/var-bearing pages (a
                            // re-fetch would re-POST them) and re-fetches plain
                            // pages; it keys off the page's own field tokens, so
                            // it's correct for Back-restored pages at any path.
                            identifyRefresh()
                        } else {
                            // Still loading: arm the post-load re-fetch. The
                            // actual re-fetch happens in emitPageLoaded via
                            // identifyRefresh, which token-checks the
                            // just-loaded page (definitive there) and skips
                            // form pages, so arming unconditionally is safe.
                            pendingIdentifyRefreshFor = currentNodeHash
                        }
                    }
                    previous = nodes
                }
            }

            // Re-push the auto-identify set to the backend whenever RNS (re)becomes
            // READY. The :rns-host service can restart (e.g. the RNS transport-drop
            // regression, or a process restart); a fresh backend starts with an
            // EMPTY identifyOnConnectNodes set, and the DataStore collector above
            // only re-emits when the set CHANGES. So without this, a restart while
            // on a flagged node's page would lose the flag until the user
            // toggles it (or reloads) - the backend would no longer identify at
            // link establishment. Re-pushing the current set on READY is cheap
            // (the backend is idempotent: identifyIfFlagged dedups by link id) and
            // covers the restart without depending on a DataStore change.
            viewModelScope.launch {
                var wasReady = rnsCore.networkStatus.value is NetworkStatus.READY
                rnsCore.networkStatus.collect { status ->
                    val isReady = status is NetworkStatus.READY
                    if (isReady && !wasReady) {
                        runCatching {
                            nomadnet.setIdentifyOnConnectNodes(_autoIdentifyNodes.value)
                        }.onFailure {
                            Log.w(TAG, "Failed to re-sync auto-identify set after READY", it)
                        }
                    }
                    wasReady = isReady
                }
            }
        }

        @Volatile
        private var fetchEpoch = 0

        // Set when identification succeeds for a node whose page has not yet
        // reached PageLoaded (still loading), so emitPageLoaded can re-fetch
        // the post-identification content once it lands. Cleared by loadPage
        // (navigation) and consumed by emitPageLoaded.
        @Volatile
        private var pendingIdentifyRefreshFor: String? = null

        @Volatile
        private var statusCollectionJob: kotlinx.coroutines.Job? = null

        @Volatile
        private var progressCollectionJob: kotlinx.coroutines.Job? = null

        private val partialManager: PartialManager by lazy {
            PartialManager(
                protocol = nomadnet,
                scope = viewModelScope,
                currentNodeHash = { currentNodeHash },
                formFields = { _formFields.value },
            )
        }

        val partialStates: StateFlow<Map<String, PartialManager.PartialState>>
            get() = partialManager.states

        // ==================== Page images ====================

        private val _imageLoadingMode = MutableStateFlow(ImageLoadingMode.AUTO)

        /** Current image loading mode (upstream `image_loading` mirror). */
        val imageLoadingMode: StateFlow<ImageLoadingMode> = _imageLoadingMode.asStateFlow()

        @Volatile
        private var imageLoadingModeUserSelected = false

        // Restore the user's persisted image-loading mode so it survives a
        // ViewModel recreation (process death / config change). Mirrors the
        // rendering-mode restore in the init block above: a user who selected
        // never/manual/always must not silently fall back to AUTO. Without this
        // _imageLoadingMode always started at AUTO and the saved choice was
        // dropped (issue 4). Lives here (not the init block) because Kotlin
        // initializes properties in declaration order and this field must exist
        // before we write to it.
        init {
            viewModelScope.launch {
                val restored =
                    try {
                        ImageLoadingMode.fromName(settingsRepository.nomadNetImageLoadingModeFlow.first())
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to read persisted image loading mode; using default", e)
                        ImageLoadingMode.AUTO
                    }
                if (!imageLoadingModeUserSelected) {
                    _imageLoadingMode.value = restored
                }
            }
        }

        /** Wall-clock timing of the last successful page fetch (bits/s EDR fallback). */
        @Volatile
        private var lastPageFetchSeconds: Double? = null

        @Volatile
        private var lastPageFetchBytes: Long = 0L

        private val pageImageLoader: PageImageLoader by lazy {
            PageImageLoader(
                nomadnet = nomadnet,
                cache = imageCache,
                scope = viewModelScope,
                currentNodeHash = { currentNodeHash },
                imageLoadingMode = { _imageLoadingMode.value },
                lastResponseSpeedBps = {
                    val seconds = lastPageFetchSeconds
                    if (seconds != null && seconds > 0 && lastPageFetchBytes > 0) {
                        lastPageFetchBytes * 8 / seconds
                    } else {
                        null
                    }
                },
            )
        }

        /** Per-image fetch states keyed by [pageImageKey]. */
        val imageStates: StateFlow<Map<String, PageImageState>>
            get() = pageImageLoader.imageStates

        /** Explicit load of all pending images (upstream Ctrl+L). */
        fun loadPageImages(forceReload: Boolean = false) {
            pageImageLoader.loadImages(forceReload)
        }

        /**
         * Clear the on-disk NomadNet image cache entirely and reset the
         * in-flight image states back to placeholders, so the user can re-load
         * them on demand. Upstream's `clear`-and-renavigate, collapsed onto the
         * mobile "Clear image cache" action.
         *
         * After clearing, re-scan the current document (if any) so its image
         * references are re-registered as placeholders. [PageImageLoader.clear]
         * on its own would wipe the registered states and leave the displayed
         * page with unfetchable placeholders: retryImage would no-op (the key
         * no longer exists) and the bulk-load menu would vanish because
         * imageStates is empty (issue 3).
         */
        fun clearImageCache() {
            imageCache.clear()
            val current = browserState.value as? BrowserState.PageLoaded
            if (current != null) {
                pageImageLoader.scan(imageRefsFor(current.document), forceReload = true)
            } else {
                pageImageLoader.clear()
            }
        }

        /** Retry/load one image (placeholder tap, sheet Reload). */
        fun retryPageImage(key: String) {
            pageImageLoader.retryImage(key)
        }

        fun setImageLoadingMode(mode: ImageLoadingMode) {
            imageLoadingModeUserSelected = true
            _imageLoadingMode.value = mode
            // Apply the transition to the active loader so it takes effect on
            // the currently-displayed page, not only on the next navigation
            // (issue 5): ALWAYS starts loading pending images, NEVER cancels a
            // running queue, AUTO re-evaluates the gate.
            pageImageLoader.applyMode(mode)
            viewModelScope.launch { settingsRepository.saveNomadNetImageLoadingMode(mode.name) }
        }

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
            // A pending post-identification refresh is scoped to a node; drop it
            // on a new navigation so it can't trigger a spurious re-fetch later.
            pendingIdentifyRefreshFor = null
            _formFields.value = emptyMap()

            // A path reaching here (from a nomadnetwork:// deep link or the URL
            // bar) may carry a trailing link-field block after a backtick, e.g.
            // "/page/forum/thread.mu`cat=help|thread=how-to-rngit". Those are
            // request variables to submit as data so the node's dynamic page
            // sees them — split them off rather than letting them corrupt the
            // requested path, and route them through the same submission path
            // an in-page link tap uses.
            val (parsedPath, fieldNames) = splitNomadNetPathFields(path)
            val requestPath = parsedPath.ifEmpty { DEFAULT_PATH }
            val formDataJson = buildNomadNetRequestData(fieldNames, _formFields.value)
            if (formDataJson != null) {
                // Variable submissions always fetch fresh (response depends on data).
                submitFormAndNavigate(destinationHash, requestPath, formDataJson, fieldNames)
                return
            }

            // Check cache before showing loading spinner.
            //
            // Flagged nodes ("Always identify to this node") bypass the cache:
            // the cached content may be anonymous (from a prior unflagged
            // visit), and "always identify" means the node should always see
            // the user's identity. A fresh fetch establishes the link (or
            // reuses it) so the backend identifies at link establishment time
            // - the Python equivalent of upstream Browser.link_established -
            // and the node serves identified content.
            //
            // The set is synced from DataStore via the reactive collector's
            // first emission. In the narrow first-launch window where loadPage
            // runs before that emission, _autoIdentifyNodes is still empty and
            // the cache is served (possibly anonymous); the backend's
            // identifyIfFlagged fires on the next fetch (any navigation or
            // refresh), which is immediate for a user interacting with the page.
            val bypassCache = destinationHash in _autoIdentifyNodes.value
            val cached = if (bypassCache) null else pageCache.get(destinationHash, requestPath)
            if (cached != null) {
                fetchEpoch++ // Invalidate any in-flight request
                stopStatusCollection()
                stopProgressCollection()
                val document = MicronParser.parse(cached)
                emitPageLoaded(document, requestPath, destinationHash)
                return
            }

            fetchPage(destinationHash, requestPath, cacheResponse = true)
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

            // Collect form field values for submission. Shared with loadPage's
            // deep-link / URL-bar path so both honour the same field semantics
            // ("fieldname" → form value, "key=value" → var_key, "*" → all).
            // Null means nothing to submit (no fields, or only ones resolving to
            // no data) — treated as a plain navigation below.
            val formDataJson = buildNomadNetRequestData(fieldNames, _formFields.value)

            // Resolve destination URL using shared utility
            val (nodeHash, path) = PartialManager.resolveNomadNetUrl(destination, currentNodeHash)

            if (nodeHash != currentNodeHash) {
                _isIdentified.value = false
                // New node: drop a pending post-identification refresh scoped to
                // the previous node so it can't cause a spurious re-fetch.
                pendingIdentifyRefreshFor = null
            }
            _formFields.value = emptyMap()

            // Form submissions always fetch fresh (response depends on submitted data)
            if (path.startsWith("/file/")) {
                downloadFile(nodeHash, path)
            } else if (formDataJson != null) {
                submitFormAndNavigate(nodeHash, path, formDataJson, fieldNames)
            } else {
                // Non-form link: check cache first. A flagged target node
                // ("Always identify to this node") bypasses the cache, for the
                // same reason as loadPage: the cached content may be anonymous,
                // and the fresh fetch establishes/reuses the link so the backend
                // identifies at link establishment and the node serves identified
                // content.
                val bypassCache = nodeHash in _autoIdentifyNodes.value
                val cached = if (bypassCache) null else pageCache.get(nodeHash, path)
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
            fieldTokens: List<String> = emptyList(),
        ) {
            val epoch = ++fetchEpoch
            // Same single-flight hygiene as fetchPage: a form submission is a
            // new page request over the shared NomadNet link, so cancel any
            // in-flight page-image queue first to keep stale image traffic off
            // the link. The target page's images are re-scanned in
            // emitPageLoaded once the fetch completes.
            pageImageLoader.cancelAll()
            stopProgressCollection()
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
            lastFetchFormDataJson = formDataJson
            lastFetchFieldTokens = fieldTokens
            _browserState.value = BrowserState.Loading("Requesting page...")
            startStatusCollection(epoch)
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        nomadnet.requestNomadnetPage(
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
                            emitPageLoaded(document, pageResult.path, nodeHash, lastFetchFieldTokens)
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
                        nomadnet.requestNomadnetPage(
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
                    nomadnet.cancelNomadnetPageRequest()
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
            // Reset identification when crossing back to a different node,
            // matching loadPage/navigateToLink: a stale "identified" flag from
            // the previous node would otherwise short-circuit the auto-trigger
            // and make the dialog open in manage mode for a node we never
            // actually identified to.
            if (entry.nodeHash != currentNodeHash) {
                _isIdentified.value = false
                pendingIdentifyRefreshFor = null
            }
            currentNodeHash = entry.nodeHash
            _formFields.value = entry.formFields
            // Instant back-navigation using the stored document
            emitPageLoaded(entry.document, entry.path, entry.nodeHash, entry.fieldTokens)
            // A flagged node must never display content fetched while unflagged
            // (that content is anonymous). The stored history document can be
            // exactly that: a page visited before the node was flagged, then
            // pushed to history. Re-fetch it identified - the same reasoning as
            // loadPage/navigateToLink's flagged-node cache bypass, which this
            // otherwise-bypass-free path must also uphold. identifyRefresh does
            // a safe GET and skips form/var-bearing pages (a re-fetch would
            // re-submit them); unflagged nodes keep the instant-back path.
            if (entry.nodeHash in _autoIdentifyNodes.value) {
                identifyRefresh()
            }
            return true
        }

        /**
         * Close the browsed site: forget the persisted last-node hash (so the
         * bottom-nav NomadNet tab reopens at the address-entry prompt instead
         * of this site), drop in-memory history, and reset the view state.
         */
        fun closeSite() {
            // Invalidate any in-flight request so its result doesn't repopulate
            // the view after we reset to Initial.
            fetchEpoch++
            stopStatusCollection()
            stopProgressCollection()
            partialManager.clear()
            pageImageLoader.clear()
            history.clear()
            _canGoBack.value = false
            _formFields.value = emptyMap()
            _browserState.value = BrowserState.Initial
            viewModelScope.launch { settingsRepository.clearNomadNetLastNodeHash() }
        }

        fun refresh() {
            val currentState = _browserState.value
            if (currentState is BrowserState.PageLoaded) {
                _isPullRefreshing.value = true
                partialManager.clear()
                // A var-bearing page (loaded via request data) must be re-fetched
                // with that same data - a bare fetch drops the request variables
                // and the node rejects the page ("Invalid thread"). Rebuild the
                // request data from the DISPLAYED page's own field tokens (not
                // lastFetch*), so back-navigation to an earlier same-node/same-
                //path page refreshes with that page's vars rather than a later
                // page's vars still lingering in lastFetch* state.
                val tokenData = buildNomadNetRequestData(
                    currentState.fieldTokens,
                    _formFields.value,
                )
                if (tokenData != null) {
                    submitFormAndNavigate(
                        currentState.nodeHash,
                        currentState.path,
                        tokenData,
                        currentState.fieldTokens,
                    )
                    return
                }
                // No link-field tokens on the displayed page: it was either a
                // plain page (fetchPage) or a form submission triggered from a
                // tokenless page (submitFormAndNavigate with no tokens). In the
                // latter case the request data is still in lastFetch* and the
                // displayed page is exactly the one we last submitted.
                val formData = lastFetchFormDataJson
                if (currentState.nodeHash == lastFetchNodeHash &&
                    currentState.path == lastFetchPath &&
                    formData != null
                ) {
                    submitFormAndNavigate(lastFetchNodeHash, lastFetchPath, formData, lastFetchFieldTokens)
                } else {
                    // Bypass cache read, but still cache the fresh response.
                    fetchPage(currentState.nodeHash, currentState.path, cacheResponse = true)
                }
            }
        }

        /**
         * Re-fetch the displayed page as a safe GET to pick up
         * post-identification content (used when a node is flagged, "always
         * identify"). NEVER re-submits a form: if the page's own field tokens
         * resolve to request data, the page is a form/var-bearing page and a
         * re-fetch would re-submit it (double-firing its side effects), so it
         * is skipped - its own fetch already established the link and
         * identified at establishment. A plain page (no resolvable tokens) is
         * re-fetched via fetchPage, which does a bare GET and never re-POSTs
         * (unlike [refresh], which has re-POST paths driven by lastFetch*).
         * Keying off the page's OWN field tokens (not stale lastFetch* state)
         * makes this correct for Back-restored pages at any path.
         */
        private fun identifyRefresh() {
            val currentState = _browserState.value
            if (currentState !is BrowserState.PageLoaded) return
            if (buildNomadNetRequestData(currentState.fieldTokens, _formFields.value) != null) {
                // Form/var-bearing page: a re-fetch would re-submit it. Skip.
                return
            }
            _isPullRefreshing.value = true
            partialManager.clear()
            fetchPage(currentState.nodeHash, currentState.path, cacheResponse = true)
        }

        /** Retry the last failed network fetch, preserving form data if applicable. */
        fun retry() {
            if (lastFetchNodeHash.isNotEmpty()) {
                val formData = lastFetchFormDataJson
                if (formData != null) {
                    // Carry the link-field tokens so a recovered var-bearing page
                    // re-submits the same request variables and re-persists the
                    // full backtick path - not a bare path the node would reject.
                    submitFormAndNavigate(lastFetchNodeHash, lastFetchPath, formData, lastFetchFieldTokens)
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
                        nomadnet.cancelNomadnetPageRequest()
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
            renderingModeUserSelected = true
            _renderingMode.value = mode
            // Persist so the choice is remembered for future sessions and other sites.
            viewModelScope.launch { settingsRepository.saveNomadNetRenderingMode(mode.name) }
        }

        /**
         * Identify to the current node: trigger the identification request and,
         * on success, mark the node identified and refresh its page.
         *
         * This deliberately does NOT write the "always identify" opt-in set:
         * that persisted preference is owned solely by the toggle
         * (NomadNetAutoIdentifyViewModel.setAutoIdentifyForNode). Writing it here
         * from the dialog's Confirm button raced a just-made toggle-off (the
         * DataStore write is async, so the Compose snapshot Confirm read was
         * stale) and could silently restore a node the user had turned off.
         */
        fun identifyToNode(targetNodeHash: String? = null) {
            if (_identifyInProgress.value || _isIdentified.value) return
            // An explicit target (passed by the stale-request retry below) is used
            // verbatim; otherwise the current node. Capturing the target here -
            // rather than re-reading currentNodeHash later - closes the
            // cross-dispatcher race where navigation between the eligibility
            // check and the request start would identify the wrong node.
            val nodeHash = targetNodeHash ?: currentNodeHash
            if (nodeHash.isEmpty()) return

            _identifyInProgress.value = true
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    nomadnet.identifyNomadnetLink(nodeHash).fold(
                        onSuccess = { alreadyIdentified ->
                            // The request targets the node captured as [nodeHash].
                            // If the user has since navigated to a different node,
                            // this outcome is stale: applying it would mark the new
                            // node as identified (suppressing its auto-identify and
                            // showing a false "identified" state).
                            if (currentNodeHash != nodeHash) return@fold
                            _isIdentified.value = true
                            if (alreadyIdentified) return@fold
                            if (_browserState.value is BrowserState.PageLoaded) {
                                // Page is displayed; refresh shows identified content.
                                refresh()
                            } else {
                                // The page for this node is still loading; the
                                // in-flight pre-identification fetch will complete
                                // and be shown. Flag it so emitPageLoaded re-fetches
                                // post-identification content once it lands.
                                pendingIdentifyRefreshFor = nodeHash
                            }
                        },
                        onFailure = {
                            // Same staleness guard: a failure for a node the user has
                            // already left must not surface as an error for the
                            // current node.
                            if (currentNodeHash != nodeHash) return@fold
                            _identifyError.value = it.message ?: "Unknown error"
                        },
                    )
                } catch (e: Exception) {
                    if (currentNodeHash == nodeHash) {
                        _identifyError.value = e.message ?: "Unknown error"
                    }
                } finally {
                    _identifyInProgress.value = false
                }
            }
        }

        override fun onCleared() {
            super.onCleared()
            stopStatusCollection()
            stopProgressCollection()
            pageImageLoader.cancelAll()
            // Cancel any in-flight Python page request so the IO thread isn't blocked
            // for up to PAGE_TIMEOUT_SECONDS after the user navigates away.
            // Use NonCancellable because viewModelScope is already cancelled at this point.
            CoroutineScope(Dispatchers.IO + NonCancellable).launch {
                try {
                    nomadnet.cancelNomadnetPageRequest()
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
                        fieldTokens = currentState.fieldTokens,
                    ),
                )
                _canGoBack.value = true
            }
        }

        /**
         * Emit a [BrowserState.PageLoaded] and trigger partial detection.
         *
         * Also seeds [_formFields] with parser-declared defaults for any text fields
         * the user has not already populated. Required so unmodified pre-filled fields
         * (e.g. a "display name" pre-filled with the user's nickname) are actually
         * submitted instead of being sent as empty strings.
         */
        private fun emitPageLoaded(
            document: MicronDocument,
            path: String,
            nodeHash: String,
            fieldTokens: List<String> = emptyList(),
        ) {
            _isPullRefreshing.value = false
            _formFields.update { current -> seedFieldDefaults(document, current) }
            // A flagged node ("Always identify to this node") is identified by
            // the backend at link establishment, so reflect that in the
            // dialog's mode: manage/turn-off view, not the "identify to this
            // node?" confirm view.
            //
            // Tradeoff (Greptile round 1 + round 2 tension): the flag is not
            // proof the proof-send succeeded (that send is best-effort and can
            // swallow errors). Claiming "identified" misrepresents the rare
            // failed-proof case. But NOT claiming it misrepresents the COMMON
            // success case and presents a node the user explicitly saved as
            // "always identify" as unidentified - the more confusing outcome.
            // The failed-proof case is recoverable either way: the dialog's
            // "Always identify" toggle is always visible, and toggling it off
            // then on re-syncs the set to the backend, which re-identifies the
            // active link (setIdentifyOnConnectNodes handles newly-active
            // links). So claiming identified does not strand the user.
            if (nodeHash in _autoIdentifyNodes.value) {
                _isIdentified.value = true
            }
            _browserState.value =
                BrowserState.PageLoaded(
                    document = document,
                    path = path,
                    nodeHash = nodeHash,
                    fieldTokens = fieldTokens,
                )
            // Remember where the user is so the bottom-nav NomadNet tab can
            // reopen the exact page the user left on (node + deep path) instead
            // of a cold default. Guarded: if the user hit Close Site while this
            // page was in flight, state is no longer this page and the save must
            // not resurrect the closed binding behind closeSite's clear.
            //
            // Persist the FULL path including the backtick field block (if any)
            // so that restoring it via loadPage re-submits the same request
            // variables. Without this, a forum thread (or any var-bearing page)
            // reopens as a bare-path fetch and the node rejects it.
            val persistPath = buildNomadNetPersistPath(path, fieldTokens)
            viewModelScope.launch {
                settingsRepository.saveNomadNetLastNodeHash(nodeHash, persistPath) {
                    val current = _browserState.value
                    current is BrowserState.PageLoaded && current.nodeHash == nodeHash
                }
            }
            partialManager.detectAndLoad(document)
            pageImageLoader.scan(imageRefsFor(document))
            // If identification for this node completed while its page was still
            // loading, the just-displayed page is pre-identification content.
            // Re-fetch it now (identified) so an access-gated service doesn't
            // show anonymous/denied content on the first visit. identifyRefresh
            // does a safe GET and skips form/var-bearing pages (a re-fetch would
            // re-submit them); _browserState is already the just-loaded page
            // above, so its field tokens are definitive.
            if (pendingIdentifyRefreshFor == nodeHash) {
                pendingIdentifyRefreshFor = null
                identifyRefresh()
            }
        }

        /** All image elements in a document, reduced to loader refs. */
        private fun imageRefsFor(document: MicronDocument): List<ParsedImageRef> =
            document.lines
                .flatMap { it.elements }
                .filterIsInstance<MicronElement.Image>()
                .map { img ->
                    ParsedImageRef(
                        url = img.url,
                        key = pageImageKey(img.url, img.width, img.height),
                    )
                }

        /**
         * Return a copy of [current] with parser-declared text-field defaults filled
         * in for any field name the user has not already typed into. User-typed values
         * (including empty strings the user explicitly cleared) win over defaults.
         */
        private fun seedFieldDefaults(
            document: MicronDocument,
            current: Map<String, String>,
        ): Map<String, String> {
            val seeded = current.toMutableMap()
            for (line in document.lines) {
                for (element in line.elements) {
                    if (element is MicronElement.Field && element.name !in seeded) {
                        seeded[element.name] = element.defaultValue
                    }
                }
            }
            return seeded
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
                        nomadnet.nomadnetRequestStatusFlow.collect { status ->
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
                        nomadnet.nomadnetDownloadProgressFlow.collect { progress ->
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
            // Navigation starts a new page request over the shared NomadNet
            // link. Cancel any in-flight page-image queue first so stale image
            // traffic doesn't compete with the page request this single-flight
            // design prioritizes (issue 7). The new page's own images are
            // re-scanned (and their queue started) in emitPageLoaded when the
            // fetch completes. Without this, an image fetch could sit in a
            // long backend await for the whole link timeout after the user
            // already navigated away.
            pageImageLoader.cancelAll()
            stopProgressCollection()
            lastFetchNodeHash = nodeHash
            lastFetchPath = path
            lastFetchFormDataJson = null
            lastFetchFieldTokens = emptyList()
            _browserState.value = BrowserState.Loading("Requesting page...")
            startStatusCollection(epoch)

            // Wall-clock fetch timing feeds the image auto-gate's fallback
            // EDR (upstream last_response_speed()).
            val fetchStartedAt = System.nanoTime()
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val result =
                        nomadnet.requestNomadnetPage(
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
                            lastPageFetchSeconds = (System.nanoTime() - fetchStartedAt) / 1_000_000_000.0
                            lastPageFetchBytes =
                                if (pageResult.type == "file") pageResult.fileSize else pageResult.content.length.toLong()
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
