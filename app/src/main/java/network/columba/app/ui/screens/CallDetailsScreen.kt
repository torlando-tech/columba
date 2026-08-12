package network.columba.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import network.columba.app.R
import network.columba.app.data.model.CallHistoryRecord
import network.columba.app.ui.components.CodecSelectionDialog
import network.columba.app.ui.components.ProfileIcon
import network.columba.app.ui.model.CodecProfile
import network.columba.app.viewmodel.BlockLookupState
import network.columba.app.viewmodel.CallDetailsState
import network.columba.app.viewmodel.CallDetailsViewModel
import network.columba.app.viewmodel.CallInitiationFailure

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallDetailsScreen(
    onBack: () -> Unit,
    onCallAgain: (remoteIdentityHash: String, profileCode: Int, localIdentityHash: String) -> Unit,
    onViewPeer: (destinationHash: String) -> Unit,
    viewModel: CallDetailsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val callAgainFailureName by viewModel.callAgainFailure.collectAsState()
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showBlockDialog by remember { mutableStateOf(false) }
    var alsoBlackhole by remember { mutableStateOf(false) }
    var showCodecDialog by remember { mutableStateOf(false) }
    var callActionPending by remember { mutableStateOf(false) }
    var callAgainFailure by remember { mutableStateOf<CallInitiationFailure?>(null) }
    var recommendedProfile by remember { mutableStateOf<CodecProfile?>(null) }
    var codecProbeInProgress by remember { mutableStateOf(false) }

    LaunchedEffect(showCodecDialog, state.record?.remoteIdentityHash) {
        if (showCodecDialog) {
            recommendedProfile = null
            codecProbeInProgress = true
            state.record?.remoteIdentityHash?.let {
                recommendedProfile = viewModel.getRecommendedCodecProfile(it)
            }
            codecProbeInProgress = false
        } else {
            codecProbeInProgress = false
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.deleted.collect { onBack() }
    }

    LaunchedEffect(callAgainFailureName) {
        val failure = callAgainFailureName?.let { runCatching { CallInitiationFailure.valueOf(it) }.getOrNull() }
        if (failure != null) {
            callActionPending = false
            callAgainFailure = failure
            viewModel.consumeCallAgainFailure()
        }
    }

    CallDetailsContent(
        state = if (showCodecDialog || callActionPending) state.copy(canStartCall = false) else state,
        onBack = onBack,
        onCallAgain = { _, _ -> showCodecDialog = true },
        onViewPeer = { viewModel.resolveCurrentPeerDestination(onViewPeer) },
        onToggleBlocked = { showBlockDialog = true },
        onDelete = { showDeleteDialog = true },
    )

    callAgainFailure?.let { failure ->
        AlertDialog(
            onDismissRequest = { callAgainFailure = null },
            title = { Text(stringResource(R.string.call_details_call_again_failed_title)) },
            text = { Text(stringResource(failure.messageResource())) },
            confirmButton = {
                TextButton(onClick = { callAgainFailure = null }) {
                    Text(stringResource(android.R.string.ok))
                }
            },
        )
    }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text(stringResource(R.string.call_details_delete_title)) },
            text = {
                val record = state.record
                Text(
                    stringResource(
                        R.string.call_details_delete_message,
                        confirmationPeerName(record),
                        record?.attemptedAt?.let { DateFormat.getDateTimeInstance().format(Date(it)) }.orEmpty(),
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        viewModel.deleteRecord()
                    },
                ) { Text(stringResource(R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showBlockDialog) {
        val isBlocked = state.blockState == BlockLookupState.BLOCKED
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = {
                Text(
                    stringResource(
                        if (isBlocked) R.string.call_details_unblock_title else R.string.call_details_block_title,
                    ),
                )
            },
            text = {
                val record = state.record
                Column {
                    Text(
                        stringResource(
                            if (isBlocked) {
                                R.string.call_details_unblock_confirmation
                            } else {
                                R.string.call_details_block_confirmation
                            },
                            confirmationPeerName(record),
                            record?.remoteIdentityHash?.take(8)?.uppercase(Locale.ROOT).orEmpty(),
                        ),
                    )
                    if (!isBlocked) {
                        Row(
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .toggleable(
                                        value = alsoBlackhole,
                                        role = Role.Checkbox,
                                        onValueChange = { alsoBlackhole = it },
                                    ),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = alsoBlackhole, onCheckedChange = null)
                            Text(stringResource(R.string.call_details_also_blackhole))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showBlockDialog = false
                        viewModel.toggleBlocked(alsoBlackhole)
                        alsoBlackhole = false
                    },
                ) {
                    Text(stringResource(if (isBlocked) R.string.call_details_unblock else R.string.call_details_block))
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlockDialog = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
    if (showCodecDialog) {
        val record = state.record
        val recordedProfile =
            record?.codecProfileCode?.let(CodecProfile::fromCode)
                ?: recommendedProfile
                ?: CodecProfile.DEFAULT
        CodecSelectionDialog(
            recommendedProfile = recommendedProfile ?: recordedProfile,
            isProbing = codecProbeInProgress,
            onDismiss = { showCodecDialog = false },
            onProfileSelected = { selected ->
                if (record != null && !callActionPending) {
                    callActionPending = true
                    showCodecDialog = false
                    onCallAgain(record.remoteIdentityHash, selected.code, record.localIdentityHash)
                }
            },
        )
    }
}

@Composable
private fun confirmationPeerName(record: CallHistoryRecord?): String {
    val hashPrefix = record?.remoteIdentityHash?.take(8)?.uppercase(Locale.ROOT).orEmpty()
    return record?.displayName?.takeIf(String::isNotBlank)
        ?: stringResource(R.string.call_peer_fallback, hashPrefix)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CallDetailsContent(
    state: CallDetailsState,
    onBack: () -> Unit,
    onCallAgain: (destinationHash: String, profileCode: Int) -> Unit,
    onViewPeer: (destinationHash: String) -> Unit,
    onToggleBlocked: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.call_details_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.navigate_up))
                    }
                },
                actions = {
                    if (state.record?.endedAt != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Default.Delete, stringResource(R.string.call_details_delete))
                        }
                    }
                },
            )
        },
    ) { padding ->
        when {
            state.isLoading ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    CircularProgressIndicator()
                    Text(stringResource(R.string.call_history_loading), modifier = Modifier.padding(top = 12.dp))
                }
            state.hasError ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.call_details_error))
                }
            state.record == null ->
                Column(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(stringResource(R.string.call_details_not_found))
                }
            else ->
                CallDetailsRecord(
                    record = state.record,
                    blockState = state.blockState,
                    actionError = state.actionError,
                    runtimeBlockRecoveryRequired = state.runtimeBlockRecoveryRequired,
                    localIdentityMatches = state.localIdentityMatches,
                    canStartCall = state.canStartCall,
                    recoveryPending = state.recoveryPending,
                    isActiveAttempt = state.isActiveAttempt,
                    onCallAgain = onCallAgain,
                    onViewPeer = { state.record?.currentDestinationHash?.let(onViewPeer) },
                    onToggleBlocked = onToggleBlocked,
                    modifier = Modifier.padding(padding),
                )
        }
    }
}
