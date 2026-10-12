package network.columba.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.launch
import network.columba.app.ui.components.PropagationNodeReorderList
import network.columba.app.util.DestinationHashValidator
import network.columba.app.viewmodel.PropagationNodesViewModel

/**
 * Dedicated M3 screen for managing the preferred propagation-node list (ticket 05).
 *
 * Centralizes all propagation-node selection: the ordered, transport-aware priority
 * list (drag to reorder, per-node transport chip, remove, set-active-now), the
 * currently-active relay, and the two add affordances (from live announces / by
 * destination hash). Reached from the "Message Delivery & Retrieval" settings card.
 *
 * The screen is a pure render of [PropagationNodesViewModel.state]; the transport
 * switcher, list, and active indicator are all derived from the policy writer that
 * ticket 03 wired, so this screen only *sets* intent — it never owns the relay.
 *
 * @param onBackClick Pop the nav back stack (return to Settings).
 * @param viewModel Injected Hilt view model (overridable in tests).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PropagationNodesScreen(
    onBackClick: () -> Unit = {},
    viewModel: PropagationNodesViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Propagation Nodes") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                modifier = Modifier.testTag("prop_node_add_fab"),
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add propagation node",
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ActiveRelayHeader(
                activeName = state.activeName,
                activeHash = state.activeHash,
            )

            if (state.list.isEmpty()) {
                EmptyPropagationsState(
                    canImportCurrent = state.canImportCurrent,
                    onImportCurrent = { viewModel.importCurrentRelay() },
                )
            } else {
                // The list owns the remaining vertical space and scrolls, so lower rows
                // (and their actions) stay reachable once the list exceeds the screen.
                // The active-relay header above stays pinned (it is outside this scroll
                // region). Drag-to-reorder still works: the per-row pointerInput consumes
                // vertical motion once a drag is active, and the handle is small enough
                // that the surrounding scroll only engages for non-drag vertical drags.
                Column(
                    modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                ) {
                    PropagationNodeReorderList(
                        items = state.list,
                        onReorder = { orderedKeys -> viewModel.reorder(orderedKeys) },
                        onRestrictionChange = { hash, newRestriction ->
                            viewModel.setRestriction(hash, newRestriction)
                        },
                        onRemove = { hash -> viewModel.remove(hash) },
                        onSetActiveNow = { hash -> viewModel.setActiveNow(hash) },
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddPropagationNodeDialog(
            existingHashes = state.list.map { it.destinationHash }.toSet(),
            onAddFromAnnounce = { announce ->
                viewModel.addFromAnnounce(announce)
            },
            onAddByHash = { hash, nickname -> viewModel.addByHash(hash, nickname) },
            onDismiss = {
                showAddDialog = false
            },
            scope = scope,
            viewModel = viewModel,
        )
    }
}

@Composable
private fun ActiveRelayHeader(
    activeName: String?,
    activeHash: String?,
) {
    Card(
        modifier = Modifier.fillMaxWidth().testTag("prop_node_active_header"),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (activeHash != null) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.Public,
                contentDescription = null,
                tint =
                    if (activeHash != null) {
                        MaterialTheme.colorScheme.onPrimaryContainer
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
            Column {
                Text(
                    text = "Currently active",
                    style = MaterialTheme.typography.labelMedium,
                    color =
                        if (activeHash != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                Text(
                    text = activeName ?: "None — auto-selecting",
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.testTag("prop_node_active_name"),
                    color =
                        if (activeHash != null) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
                if (activeHash != null) {
                    Text(
                        text = activeHash.take(12),
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun EmptyPropagationsState(
    canImportCurrent: Boolean,
    onImportCurrent: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = Icons.Filled.Public,
            contentDescription = null,
            modifier = Modifier.size(56.dp).testTag("prop_node_empty_icon"),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "No preferred propagation nodes",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text =
                "Add relays to build a transport-aware priority list. The highest-priority " +
                    "node that matches the current transport is used; with none matching, " +
                    "the nearest announced relay is used.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (canImportCurrent) {
            Spacer(modifier = Modifier.height(8.dp))
            TextButton(
                onClick = onImportCurrent,
                modifier = Modifier.testTag("prop_node_import_current"),
            ) {
                Text("Import your current relay")
            }
        }
    }
}

@Composable
private fun AddPropagationNodeDialog(
    existingHashes: Set<String>,
    onAddFromAnnounce: (network.columba.app.data.repository.Announce) -> Unit,
    onAddByHash: (destinationHash: String, nickname: String?) -> Unit,
    onDismiss: () -> Unit,
    scope: kotlinx.coroutines.CoroutineScope,
    viewModel: PropagationNodesViewModel,
) {
    var tab by remember { mutableStateOf(0) } // 0 = saved relays, 1 = announces, 2 = by hash
    var savedCandidates by remember { mutableStateOf<List<network.columba.app.data.repository.Announce>?>(null) }
    var candidates by remember { mutableStateOf<List<network.columba.app.data.repository.Announce>?>(null) }
    var hashInput by remember { mutableStateOf("") }
    var nicknameInput by remember { mutableStateOf("") }

    // Load candidates once on first show. Saved relays is the default tab (the user's own
    // starred relays), so load it eagerly; announces is loaded lazily when that tab is opened.
    remember {
        scope.launch {
            savedCandidates = viewModel.loadSavedRelayCandidates()
        }
    }
    fun ensureAnnouncesLoaded() {
        if (candidates == null) {
            scope.launch { candidates = viewModel.loadTopCandidates() }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add propagation node") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = tab == 0,
                        onClick = { tab = 0 },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3),
                    ) {
                        Text("Saved relays")
                    }
                    SegmentedButton(
                        selected = tab == 1,
                        onClick = { tab = 1; ensureAnnouncesLoaded() },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3),
                    ) {
                        Text("From announces")
                    }
                    SegmentedButton(
                        selected = tab == 2,
                        onClick = { tab = 2 },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3),
                    ) {
                        Text("By destination hash")
                    }
                }

                if (tab == 0) {
                    CandidateList(
                        list = savedCandidates,
                        emptyMessage = "No saved propagation nodes yet. Star a relay (Save peer) to see it here.",
                        alreadyAdded = { hash -> hash in existingHashes },
                        onPick = { candidate ->
                            onAddFromAnnounce(candidate)
                            onDismiss()
                        },
                    )
                } else if (tab == 1) {
                    CandidateList(
                        list = candidates,
                        emptyMessage = "No propagation-node announces on the network yet.",
                        alreadyAdded = { hash -> hash in existingHashes },
                        onPick = { candidate ->
                            onAddFromAnnounce(candidate)
                            onDismiss()
                        },
                    )
                } else {
                    // Validate the destination hash with the same validator the rest of the
                    // app uses, and surface its error inline. The stored value is the
                    // normalized (lowercase) hash from the validator, so a mixed-case entry
                    // cannot create a contact that the relay-search path can't find, and a
                    // non-hex entry can never reach hexToByteArray() and kill the sync.
                    val hashValidation = DestinationHashValidator.validate(hashInput)
                    OutlinedTextField(
                        value = hashInput,
                        onValueChange = { hashInput = it },
                        label = { Text("Destination hash (hex)") },
                        supportingText = {
                            Text(
                                text =
                                    if (hashValidation is DestinationHashValidator.ValidationResult.Error) {
                                        hashValidation.message
                                    } else {
                                        DestinationHashValidator.getCharacterCount(hashInput)
                                    },
                                color =
                                    if (hashValidation is DestinationHashValidator.ValidationResult.Error) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = nicknameInput,
                        onValueChange = { nicknameInput = it },
                        label = { Text("Nickname (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            val byHash = tab == 2
            val validation = DestinationHashValidator.validate(hashInput)
            val isValid = validation is DestinationHashValidator.ValidationResult.Valid
            val canConfirm =
                if (byHash) {
                    isValid && validation.normalizedHash !in existingHashes
                } else {
                    false // announces add on row tap
                }
            TextButton(
                onClick = {
                    if (canConfirm) {
                        onAddByHash((validation as DestinationHashValidator.ValidationResult.Valid).normalizedHash, nicknameInput.trim().ifEmpty { null })
                        onDismiss()
                    }
                },
                enabled = canConfirm,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Renders a candidate list (shared by the "Saved relays" and "From announces" tabs). Shows a
 * spinner while [list] is null, [emptyMessage] when it is empty, otherwise one tappable row per
 * candidate; rows already in [existingHashes] are dimmed and non-interactive.
 */
@Composable
private fun CandidateList(
    list: List<network.columba.app.data.repository.Announce>?,
    emptyMessage: String,
    alreadyAdded: (String) -> Boolean,
    onPick: (network.columba.app.data.repository.Announce) -> Unit,
) {
    if (list == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(18.dp))
            Text("Loading…", style = MaterialTheme.typography.bodyMedium)
        }
    } else if (list.isEmpty()) {
        Text(
            emptyMessage,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .height(240.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            list.forEach { candidate ->
                val added = alreadyAdded(candidate.destinationHash)
                Surface(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .testTag("prop_node_candidate_${candidate.destinationHash}")
                            .then(
                                if (added) {
                                    Modifier
                                } else {
                                    Modifier.clickable { onPick(candidate) }
                                },
                            ),
                    color =
                        if (added) {
                            MaterialTheme.colorScheme.surfaceVariant
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text =
                                    candidate.peerName.ifEmpty { candidate.destinationHash.take(12) },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text =
                                    candidate.destinationHash.take(8) + " · " + candidate.hops + " hops",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                        if (added) {
                            Text(
                                "Added",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}
