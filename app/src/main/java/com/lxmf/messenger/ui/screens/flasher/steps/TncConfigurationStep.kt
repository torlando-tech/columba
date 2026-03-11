package com.lxmf.messenger.ui.screens.flasher.steps

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/**
 * Step 4d: TNC Configuration (microReticulum only)
 *
 * After flashing microReticulum firmware, configure the radio parameters
 * for standalone transport mode via USB serial commands.
 */
@Composable
fun TncConfigurationStep(
    frequencyMhz: String,
    bandwidthKhz: String,
    spreadingFactor: String,
    codingRate: String,
    txPower: String,
    isConfiguring: Boolean,
    configError: String?,
    isStandaloneConfig: Boolean = false,
    onFrequencyChanged: (String) -> Unit,
    onBandwidthChanged: (String) -> Unit,
    onSpreadingFactorChanged: (String) -> Unit,
    onCodingRateChanged: (String) -> Unit,
    onTxPowerChanged: (String) -> Unit,
    onApply: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = if (isStandaloneConfig) Icons.Default.Settings else Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = if (isStandaloneConfig) "Transport Configuration" else "Flash Successful",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Configure transport mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text =
                    "microReticulum operates as a standalone transport node. " +
                        "Configure the radio parameters below to enable transport mode. " +
                        "These settings are saved to the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier.padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Radio parameters card
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Radio Parameters",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp))

                OutlinedTextField(
                    value = frequencyMhz,
                    onValueChange = onFrequencyChanged,
                    label = { Text("Frequency (MHz)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    singleLine = true,
                    enabled = !isConfiguring,
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = bandwidthKhz,
                        onValueChange = onBandwidthChanged,
                        label = { Text("BW (kHz)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        enabled = !isConfiguring,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = spreadingFactor,
                        onValueChange = onSpreadingFactorChanged,
                        label = { Text("SF (7-12)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !isConfiguring,
                        modifier = Modifier.weight(1f),
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    OutlinedTextField(
                        value = codingRate,
                        onValueChange = onCodingRateChanged,
                        label = { Text("CR (5-8)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !isConfiguring,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = txPower,
                        onValueChange = onTxPowerChanged,
                        label = { Text("TX Power (dBm)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        enabled = !isConfiguring,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Config error
        if (configError != null) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = configError,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Actions
        Button(
            onClick = onApply,
            enabled = !isConfiguring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            if (isConfiguring) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    strokeWidth = 2.dp,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Configuring...")
            } else {
                Text("Apply Configuration")
            }
        }

        TextButton(
            onClick = onSkip,
            enabled = !isConfiguring,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (isStandaloneConfig) "Cancel" else "Skip (configure later)")
        }
    }
}
