package network.columba.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import network.columba.app.data.config.InterfaceConfigCodec
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.service.InterfaceImportManager
import javax.inject.Inject

/**
 * Drives the "paste interface config" dialog used by the interface-management
 * screen and by the in-chat config suggestion.
 *
 * Parsing (and duplicate/name resolution) is pure and happens in
 * [InterfaceConfigCodec]. Existing interfaces are loaded once per dialog open
 * so duplicate endpoints start unchecked and name collisions get suffixed.
 */
@HiltViewModel
class InterfaceImportViewModel
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val importManager: InterfaceImportManager,
    ) : ViewModel() {
        companion object {
            /** Overridable in unit tests; mirrors the other ViewModels' pattern. */
            internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        }

        data class ImportItem(
            val config: InterfaceConfig,
            val originalName: String,
            val duplicate: Boolean,
            val warnings: List<String>,
            val selected: Boolean,
        ) {
            /** True when the parsed name had to change to stay unique. */
            val wasRenamed: Boolean get() = config.name != originalName
        }

        data class State(
            val isOpen: Boolean = false,
            val text: String = "",
            val items: List<ImportItem> = emptyList(),
            /** Fatal per-section parse errors (unsupported type, bad endpoint…). */
            val errors: List<String> = emptyList(),
            /** Non-fatal issues from parsed sections (ignored keys, defaults). */
            val warnings: List<String> = emptyList(),
            val isLoadingExisting: Boolean = false,
            val isImporting: Boolean = false,
            val importedCount: Int = 0,
            /** Toast-style result for the host screen to show once. */
            val resultMessage: String? = null,
            /** Set when the backend could not apply live; host shows Apply. */
            val needsManualApply: Boolean = false,
            /** Incremented after every successful import. */
            val importGeneration: Int = 0,
        ) {
            val selectedCount: Int get() = items.count { it.selected }
            val canImport: Boolean get() = selectedCount > 0 && !isImporting && !isLoadingExisting
        }

        private val _state = MutableStateFlow(State())
        val state: StateFlow<State> = _state.asStateFlow()

        private var existingConfigs: List<InterfaceConfig> = emptyList()
        private var loadJob: Job? = null
        private var importJob: Job? = null

        /** Bumped on every open/close so stale jobs cannot update a later session. */
        @Volatile
        private var session: Int = 0

        /** Serialises open() with an import's final state transition. */
        private val stateLock = Any()

        /** Set synchronously before launch and cleared in the job's finally. */
        @Volatile
        private var importRunning = false

        /**
         * Open the dialog. [initialText] prefills the editor (used by the chat
         * suggestion card); the user can still edit before importing.
         */
        fun open(initialText: String = "") {
            synchronized(stateLock) {
                session++
                // A dismissed dialog may still be applying its import. Keep Add
                // disabled for the new dialog until that work finishes.
                _state.value =
                    State(
                        isOpen = true,
                        text = initialText,
                        isLoadingExisting = true,
                        isImporting = importRunning,
                    )
                loadExisting(session)
            }
        }

        fun updateText(text: String) {
            _state.update { it.copy(text = text) }
            reparse()
        }

        fun toggle(index: Int) {
            _state.update { current ->
                current.copy(
                    items =
                        current.items.mapIndexed { itemIndex, item ->
                            if (itemIndex == index) item.copy(selected = !item.selected) else item
                        },
                )
            }
        }

        fun importSelected() {
            synchronized(stateLock) {
                val current = _state.value
                if (current.isLoadingExisting || current.isImporting || importRunning) return
                val selected = current.items.filter { it.selected }
                if (selected.isEmpty()) return
                importRunning = true
                _state.update { it.copy(isImporting = true) }
                importJob?.cancel()
                val jobSession = session
                importJob =
                    viewModelScope.launch(ioDispatcher) {
                        try {
                            val result = importManager.importInterfaces(selected.map { it.config })
                            if (jobSession != session) {
                                _state.update { current ->
                                    current.copy(
                                        isImporting = false,
                                        isLoadingExisting = true,
                                        needsManualApply =
                                            current.needsManualApply ||
                                                (!result.appliedLive && result.insertedIds.isNotEmpty()),
                                        importGeneration = current.importGeneration + 1,
                                    )
                                }
                                loadExisting(session, preserveSelection = true)
                                return@launch
                            }
                            existingConfigs = existingConfigs + selected.map { it.config }
                            _state.update { current ->
                                current.copy(
                                    isImporting = false,
                                    isOpen = false,
                                    importedCount = result.insertedIds.size,
                                    needsManualApply = !result.appliedLive && result.insertedIds.isNotEmpty(),
                                    resultMessage =
                                        buildString {
                                            append("Added ${result.insertedIds.size} interface")
                                            if (result.insertedIds.size != 1) append("s")
                                            if (!result.appliedLive) {
                                                append(" — open Network Interfaces to apply")
                                                result.applyErrorMessage?.let { append(" (live apply failed: $it)") }
                                            }
                                        },
                                    importGeneration = current.importGeneration + 1,
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            if (jobSession == session) {
                                _state.update {
                                    it.copy(
                                        isImporting = false,
                                        resultMessage = "Couldn't add interfaces: ${e.message ?: "unknown error"}",
                                    )
                                }
                            } else {
                                _state.update { it.copy(isImporting = false, isLoadingExisting = true) }
                                loadExisting(session, preserveSelection = true)
                            }
                        } finally {
                            synchronized(stateLock) {
                                importRunning = false
                                _state.update { it.copy(isImporting = false) }
                            }
                        }
                    }
            }
        }

        /** Restart parsing from the current text without reopening. */
        fun reparse(preserveSelection: Boolean = false) {
            val current = _state.value
            val previousSelections =
                if (preserveSelection) {
                    current.items.associate {
                        it.originalName + "|" + InterfaceConfigCodec.identityKey(it.config) to it.selected
                    }
                } else {
                    emptyMap()
                }
            if (current.text.isBlank()) {
                _state.update { it.copy(items = emptyList(), errors = emptyList(), warnings = emptyList()) }
                return
            }
            val parsed = InterfaceConfigCodec.parseSnippet(current.text)
            val resolved = InterfaceConfigCodec.resolveImports(parsed.interfaces, existingConfigs)
            val items =
                resolved.map { entry ->
                    ImportItem(
                        config = entry.config,
                        originalName = entry.originalName,
                        duplicate = entry.duplicate,
                        warnings = entry.warnings,
                        selected =
                            previousSelections[entry.originalName + "|" + InterfaceConfigCodec.identityKey(entry.config)]
                                ?: !entry.duplicate,
                    )
                }
            _state.update {
                it.copy(
                    items = items,
                    errors = parsed.issues.filter { issue -> issue.isError }.map { describe(it) },
                    warnings = parsed.issues.filter { issue -> !issue.isError }.map { describe(it) },
                )
            }
        }

        fun close() {
            // Let an in-flight import finish so already-saved rows still get
            // applied. A later open bumps the session and ignores its result.
            loadJob?.cancel()
            _state.update { it.copy(isOpen = false) }
        }

        /** Clear the one-shot result message after the host shows it. */
        fun consumeResult() {
            _state.update { it.copy(resultMessage = null) }
        }

        private fun loadExisting(
            sessionAtStart: Int,
            preserveSelection: Boolean = false,
        ) {
            loadJob?.cancel()
            loadJob =
                viewModelScope.launch(ioDispatcher) {
                    existingConfigs =
                        runCatching { interfaceRepository.allInterfaces.first() }
                            .getOrDefault(emptyList())
                    if (sessionAtStart != session) return@launch
                    _state.update { it.copy(isLoadingExisting = false) }
                    reparse(preserveSelection)
                }
        }

        private fun describe(issue: InterfaceConfigCodec.Issue): String =
            if (issue.sectionName.isNullOrBlank()) {
                issue.message
            } else {
                "${issue.sectionName}: ${issue.message}"
            }
    }
