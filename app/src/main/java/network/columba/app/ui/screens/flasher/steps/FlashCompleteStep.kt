package network.columba.app.ui.screens.flasher.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import network.columba.app.R
import network.columba.app.rns.host.flasher.FrequencyBand
import network.columba.app.rns.host.flasher.RNodeDeviceInfo
import network.columba.app.ui.screens.flasher.asString
import network.columba.app.viewmodel.FlashResult

/**
 * Step 5: Flash Complete
 *
 * Shows the result of the flash operation and provides next actions:
 * - Success: Device info, "Flash Another", "Configure RNode", "Done"
 * - Failure: Error details, "Retry", "Done"
 * - Cancelled: Confirmation and options
 */
@Composable
fun FlashCompleteStep(
    result: FlashResult?,
    onFlashAnother: () -> Unit,
    onConfigureRNode: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        when (result) {
            is FlashResult.Success ->
                SuccessContent(
                    deviceInfo = result.deviceInfo,
                    onFlashAnother = onFlashAnother,
                    onConfigureRNode = onConfigureRNode,
                    onDone = onDone,
                )
            is FlashResult.Failure ->
                FailureContent(
                    error = result.error.asString(),
                    onFlashAnother = onFlashAnother,
                    onDone = onDone,
                )
            is FlashResult.Cancelled ->
                CancelledContent(
                    onFlashAnother = onFlashAnother,
                    onDone = onDone,
                )
            null -> {
                // Should not happen
                Text(stringResource(R.string.flasher_unknown_state))
            }
        }
    }
}

@Composable
private fun SuccessContent(
    deviceInfo: RNodeDeviceInfo?,
    onFlashAnother: () -> Unit,
    onConfigureRNode: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Success icon
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(R.string.flasher_flash_success_heading),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
        )

        Text(
            text = stringResource(R.string.flasher_updated_successfully),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Device info card
        if (deviceInfo != null) {
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Memory,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.flasher_device_information),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                    DeviceInfoRow(label = stringResource(R.string.flasher_label_board), value = deviceInfo.board.displayName)
                    deviceInfo.firmwareVersion?.let { version ->
                        DeviceInfoRow(label = stringResource(R.string.flasher_label_firmware), value = "v$version")
                    }
                    val band = FrequencyBand.fromModelCode(deviceInfo.model)
                    if (band != FrequencyBand.UNKNOWN) {
                        DeviceInfoRow(label = stringResource(R.string.flasher_label_band), value = band.displayName)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Button(
            onClick = onConfigureRNode,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flasher_btn_configure_rnode))
        }

        OutlinedButton(
            onClick = onFlashAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flasher_btn_flash_another))
        }

        TextButton(
            onClick = onDone,
        ) {
            Text(stringResource(R.string.flasher_btn_done))
        }
    }
}

@Composable
private fun FailureContent(
    error: String,
    onFlashAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scrollState = rememberScrollState()
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .navigationBarsPadding()
                .padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Error icon
        Icon(
            imageVector = Icons.Default.Error,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.error,
        )

        Text(
            text = stringResource(R.string.flasher_flash_failed),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.error,
        )

        // Error details card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.flasher_error_details),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }

        // Recovery tips
        Card(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.flasher_recovery_tips),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.flasher_recovery_tips_list),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Button(
            onClick = onFlashAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flasher_btn_try_again))
        }

        TextButton(
            onClick = onDone,
        ) {
            Text(stringResource(R.string.flasher_btn_done))
        }
    }
}

@Composable
private fun CancelledContent(
    onFlashAnother: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Cancel icon
        Icon(
            imageVector = Icons.Default.Cancel,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.outline,
        )

        Text(
            text = stringResource(R.string.flasher_flash_cancelled),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
        )

        Text(
            text = stringResource(R.string.flasher_cancelled_note),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        // Warning card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(R.string.flasher_inconsistent_warning),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(16.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Button(
            onClick = onFlashAnother,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(stringResource(R.string.flasher_btn_try_again))
        }

        TextButton(
            onClick = onDone,
        ) {
            Text(stringResource(R.string.flasher_btn_done))
        }
    }
}

@Composable
private fun DeviceInfoRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}
