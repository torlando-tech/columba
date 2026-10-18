package network.columba.app.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import network.columba.app.R
import network.columba.app.viewmodel.IdentityUnlockUiState
import network.columba.app.viewmodel.IdentityUnlockViewModel

/**
 * Screen shown after an Auto Backup restore when the active identity's
 * Keystore-wrapped key blob survived the restore but the Keystore AES key
 * that produced it didn't (Keystore keys are app-UID-bound and don't cross
 * the uninstall boundary). The user picks: import the `.identity` file they
 * saved before the phone swap, or start fresh with a new identity.
 */
@Composable
fun IdentityUnlockScreen(
    onResolved: () -> Unit,
    viewModel: IdentityUnlockViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    val activeIdentity by viewModel.activeIdentity.collectAsState()
    val context = LocalContext.current

    val importLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocument(),
        ) { uri: Uri? ->
            uri?.let { viewModel.importIdentityFile(it) }
        }

    var showStartFreshConfirm by remember { mutableStateOf(false) }
    var showExplainer by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        // `StartedFresh` deliberately isn't handled here: the ViewModel kills
        // the process immediately after emitting it, so any in-process
        // navigation would race the shutdown and misroute to Chats with no
        // active identity. The next cold launch lands on the onboarding flow
        // because Start Fresh also cleared `HAS_COMPLETED_ONBOARDING`.
        if (uiState is IdentityUnlockUiState.Restored) {
            onResolved()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(32.dp))

            Icon(
                imageVector = Icons.Default.Lock,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary,
            )

            Spacer(Modifier.height(24.dp))

            Text(
                text = stringResource(R.string.unlock_restore_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = stringResource(R.string.unlock_restore_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            activeIdentity?.let { identity ->
                Spacer(Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.unlock_looking_for, identity.displayName, identity.identityHash.take(8)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(32.dp))

            when (val state = uiState) {
                is IdentityUnlockUiState.Loading -> {
                    LoadingBlock(state.message.resolve(context))
                }
                is IdentityUnlockUiState.Error -> {
                    ErrorBlock(state.message.resolve(context)) {
                        viewModel.dismissError()
                    }
                }
                is IdentityUnlockUiState.HashMismatch -> {
                    HashMismatchDialog(
                        imported = state.importedHash,
                        active = state.activeHash,
                        onDismiss = { viewModel.cancelHashMismatch() },
                    )
                }
                else -> Unit
            }

            Button(
                onClick = {
                    // `.identity` files don't have a well-known MIME, so we
                    // open anything and let the parser reject invalid files.
                    importLauncher.launch(arrayOf("*/*"))
                },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = uiState !is IdentityUnlockUiState.Loading,
            ) {
                Text(
                    text = stringResource(R.string.unlock_import_button),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            FilledTonalButton(
                onClick = { showStartFreshConfirm = true },
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
                enabled = uiState !is IdentityUnlockUiState.Loading,
            ) {
                Text(
                    text = stringResource(R.string.unlock_start_fresh),
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Spacer(Modifier.height(12.dp))

            TextButton(onClick = { showExplainer = true }) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.size(4.dp))
                Text(text = stringResource(R.string.unlock_why_button))
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showStartFreshConfirm) {
        AlertDialog(
            onDismissRequest = { showStartFreshConfirm = false },
            title = { Text(stringResource(R.string.unlock_start_fresh_title)) },
            text = {
                Text(
                    stringResource(R.string.unlock_start_fresh_body),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showStartFreshConfirm = false
                    viewModel.startFresh()
                }) { Text(stringResource(R.string.unlock_start_fresh)) }
            },
            dismissButton = {
                TextButton(onClick = { showStartFreshConfirm = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    if (showExplainer) {
        AlertDialog(
            onDismissRequest = { showExplainer = false },
            title = { Text(stringResource(R.string.unlock_why_title)) },
            text = {
                Text(
                    stringResource(R.string.unlock_why_body),
                )
            },
            confirmButton = {
                TextButton(onClick = { showExplainer = false }) { Text(stringResource(R.string.unlock_got_it)) }
            },
        )
    }
}

@Composable
private fun LoadingBlock(message: String) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(8.dp))
        Text(
            text = message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ErrorBlock(
    message: String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.unlock_try_again)) }
        }
    }
}

@Composable
private fun HashMismatchDialog(
    imported: String,
    active: String,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.unlock_mismatch_title)) },
        text = {
            Text(
                stringResource(
                    R.string.unlock_mismatch_body,
                    imported.take(8),
                    active.take(8),
                ),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.unlock_choose_another)) } },
    )
}
