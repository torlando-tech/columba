package network.columba.app.ui.screens

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import network.columba.app.R
import network.columba.app.viewmodel.NewCallInputMode
import network.columba.app.viewmodel.NewCallInvalidReason
import network.columba.app.viewmodel.NewCallLookupState
import network.columba.app.viewmodel.NewCallViewModel

/**
 * Hash-entry screen for the "New call" flow.
 *
 * Shows a text field for the identity or LXST destination hash (per [mode]),
 * then resolves the LXST destination and looks it up in the local announce
 * database. Found announces render as a card that navigates to node details;
 * unknown destinations expose a "Search Network" action that submits a path
 * request to the Reticulum network.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewCallHashEntryScreen(
    mode: NewCallInputMode,
    initialHash: String? = null,
    onBackClick: () -> Unit,
    onViewNodeDetails: (destinationHash: String) -> Unit,
    onStartCall: (destinationHash: String) -> Unit,
    viewModel: NewCallViewModel = hiltViewModel(),
) {
    var hashInput by rememberSaveable { mutableStateOf(initialHash.orEmpty()) }
    val lookupState by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(initialHash) {
        if (!initialHash.isNullOrBlank()) {
            viewModel.submit(initialHash, mode)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.new_call_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.back),
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
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = hashInput,
                onValueChange = { hashInput = it },
                placeholder = {
                    Text(
                        stringResource(
                            if (mode == NewCallInputMode.LXST_DESTINATION_HASH) {
                                R.string.new_call_lxst_destination_hash_placeholder
                            } else {
                                R.string.new_call_identity_hash_placeholder
                            },
                        ),
                    )
                },
                singleLine = true,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                isError = lookupState is NewCallLookupState.InvalidInput,
                supportingText =
                    if (lookupState is NewCallLookupState.InvalidInput) {
                        val reason = (lookupState as NewCallLookupState.InvalidInput).reason
                        {
                            Text(
                                text = stringResource(reason.stringRes()),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    } else {
                        null
                    },
            )

            Button(
                onClick = { viewModel.submit(hashInput, mode) },
                enabled = hashInput.isNotBlank() && lookupState !is NewCallLookupState.Searching,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (mode == NewCallInputMode.LXST_DESTINATION_HASH) {
                            R.string.new_call_lookup_destination
                        } else {
                            R.string.new_call_derive_and_lookup
                        },
                    ),
                )
            }

            when (val state = lookupState) {
                NewCallLookupState.Idle -> Unit

                NewCallLookupState.Searching -> {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text(
                            stringResource(R.string.new_call_searching_local_db),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                is NewCallLookupState.InvalidInput -> Unit

                is NewCallLookupState.Resolved -> {
                    if (state.announce != null) {
                        FoundAnnounceCard(
                            destinationHash = state.telephonyDestinationHash,
                            peerName = state.announce.peerName,
                            onViewNodeDetails = { onViewNodeDetails(state.telephonyDestinationHash) },
                            onStartCall = { onStartCall(state.telephonyDestinationHash) },
                        )
                    } else {
                        NotFoundCard(
                            destinationHash = state.telephonyDestinationHash,
                            requesting = false,
                            errorMessage = null,
                            onSearchNetwork = { viewModel.searchNetwork(state.telephonyDestinationHash) },
                        )
                    }
                }

                is NewCallLookupState.RequestingNetwork -> {
                    NotFoundCard(
                        destinationHash = state.telephonyDestinationHash,
                        requesting = true,
                        errorMessage = null,
                        onSearchNetwork = {},
                    )
                }

                is NewCallLookupState.NetworkRequestFailed -> {
                    NotFoundCard(
                        destinationHash = state.telephonyDestinationHash,
                        requesting = false,
                        errorMessage = state.message ?: stringResource(R.string.new_call_network_request_failed),
                        onSearchNetwork = { viewModel.searchNetwork(state.telephonyDestinationHash) },
                    )
                }
            }
        }
    }
}

@StringRes
private fun NewCallInvalidReason.stringRes(): Int =
    when (this) {
        NewCallInvalidReason.INVALID_IDENTITY_HASH -> R.string.new_call_invalid_identity_hash
        NewCallInvalidReason.INVALID_DESTINATION_HASH -> R.string.new_call_invalid_destination_hash
    }

@Composable
private fun FoundAnnounceCard(
    destinationHash: String,
    peerName: String,
    onViewNodeDetails: () -> Unit,
    onStartCall: () -> Unit,
) {
    Card(
        onClick = onViewNodeDetails,
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = peerName,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = destinationHash,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = stringResource(R.string.new_call_announce_found_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = onStartCall,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(
                    imageVector = Icons.Default.Call,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(width = 8.dp, height = 0.dp))
                Text(stringResource(R.string.new_call_start_call))
            }
        }
    }
}

@Composable
private fun NotFoundCard(
    destinationHash: String,
    requesting: Boolean,
    errorMessage: String?,
    onSearchNetwork: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.size(width = 8.dp, height = 0.dp))
                Text(
                    text = stringResource(R.string.new_call_announce_not_found),
                    style = MaterialTheme.typography.titleSmall,
                )
            }
            if (destinationHash.isNotEmpty()) {
                Text(
                    text = destinationHash,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = stringResource(R.string.new_call_search_network_explainer),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (errorMessage != null) {
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            Button(
                onClick = onSearchNetwork,
                enabled = !requesting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (requesting) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Spacer(modifier = Modifier.size(width = 8.dp, height = 0.dp))
                }
                Text(
                    stringResource(
                        if (requesting) R.string.new_call_searching_network else R.string.new_call_search_network,
                    ),
                )
            }
        }
    }
}
