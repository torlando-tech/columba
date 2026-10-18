package network.columba.app.ui.screens.flasher.steps

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.host.R as RnsHostR

/**
 * Step 4: Flash Progress
 *
 * Shows the flashing progress with a determinate progress bar.
 * Also handles waiting for manual reset and provisioning states.
 * Includes a cancel button with confirmation dialog.
 */
@Composable
fun FlashProgressStep(
    progress: Int,
    message: String,
    showCancelConfirmation: Boolean,
    onShowCancelConfirmation: () -> Unit,
    onHideCancelConfirmation: () -> Unit,
    onCancelFlash: () -> Unit,
    modifier: Modifier = Modifier,
    needsManualReset: Boolean = false,
    isProvisioning: Boolean = false,
    onDeviceReset: () -> Unit = {},
) {
    // Keep screen on during flashing to prevent interruption
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose {
            window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    // Pulsing animation for the icon
    val infiniteTransition = rememberInfiniteTransition(label = "flashPulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(800, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "pulseAlpha",
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        when {
            needsManualReset -> {
                // Show manual reset instructions
                ManualResetContent(
                    message = message,
                    alpha = alpha,
                    onDeviceReset = onDeviceReset,
                )
            }
            isProvisioning -> {
                // Show provisioning progress
                ProvisioningContent(
                    message = message,
                    alpha = alpha,
                )
            }
            else -> {
                // Show flashing progress
                FlashingContent(
                    progress = progress,
                    message = message,
                    alpha = alpha,
                    onShowCancelConfirmation = onShowCancelConfirmation,
                )
            }
        }
    }

    // Cancel confirmation dialog
    if (showCancelConfirmation) {
        CancelConfirmationDialog(
            onDismiss = onHideCancelConfirmation,
            onConfirm = onCancelFlash,
        )
    }
}

@Composable
private fun FlashingContent(
    progress: Int,
    message: String,
    alpha: Float,
    onShowCancelConfirmation: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Animated icon
        Icon(
            imageVector = Icons.Default.Memory,
            contentDescription = null,
            modifier =
                Modifier
                    .size(80.dp)
                    .alpha(alpha),
            tint = MaterialTheme.colorScheme.primary,
        )

        // Title
        Text(
            text = stringResource(R.string.flasher_title_flashing_firmware),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Progress card
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Progress percentage
                Text(
                    text = "$progress%",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                // Progress bar
                LinearProgressIndicator(
                    progress = { progress / 100f },
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                )

                // Status message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Warning
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.flasher_do_not_disconnect),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        OutlinedButton(
            onClick = onShowCancelConfirmation,
        ) {
            Text(stringResource(R.string.flasher_btn_cancel))
        }
    }
}

@Composable
private fun ManualResetContent(
    message: String,
    alpha: Float,
    onDeviceReset: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Animated icon
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier =
                Modifier
                    .size(80.dp)
                    .alpha(alpha),
            tint = MaterialTheme.colorScheme.primary,
        )

        // Title
        Text(
            text = stringResource(R.string.flasher_title_reset_required),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Instructions card
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(RnsHostR.string.nordic_flash_complete),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )

                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Info card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.flasher_reset_instructions),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Continue button
        Button(
            onClick = onDeviceReset,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.flasher_btn_device_reset))
        }
    }
}

@Composable
private fun ProvisioningContent(
    message: String,
    alpha: Float,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        // Animated icon
        Icon(
            imageVector = Icons.Default.Settings,
            contentDescription = null,
            modifier =
                Modifier
                    .size(80.dp)
                    .alpha(alpha),
            tint = MaterialTheme.colorScheme.primary,
        )

        // Title
        Text(
            text = stringResource(R.string.flasher_title_provisioning),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )

        // Progress card
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Indeterminate progress bar
                LinearProgressIndicator(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(8.dp),
                )

                // Status message
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // Info card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.flasher_provisioning_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(12.dp),
            )
        }
    }
}

@Composable
private fun CancelConfirmationDialog(
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.flasher_dialog_cancel_title)) },
        text = {
            Text(stringResource(R.string.flasher_dialog_cancel_text))
        },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
            ) {
                Text(
                    text = stringResource(R.string.flasher_btn_cancel_flash),
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.flasher_btn_continue_flashing))
            }
        },
    )
}
