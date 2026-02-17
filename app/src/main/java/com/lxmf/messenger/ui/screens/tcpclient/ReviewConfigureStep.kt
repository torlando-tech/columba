package com.lxmf.messenger.ui.screens.tcpclient

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel

/**
 * Step 2: Review and Configure
 *
 * Shows the selected server information and allows editing
 * interface name, host, and port.
 */
@Composable
fun ReviewConfigureStep(viewModel: TcpClientWizardViewModel) {
    val state by viewModel.state.collectAsState()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .imePadding()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Server summary card
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        "Server",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                    Text(
                        if (state.isCustomMode) "Custom Server" else state.selectedServer?.name.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
        }

        Spacer(Modifier.height(24.dp))

        // Interface name
        OutlinedTextField(
            value = state.interfaceName,
            onValueChange = { viewModel.updateInterfaceName(it) },
            label = { Text("Interface Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        // Target host
        OutlinedTextField(
            value = state.targetHost,
            onValueChange = { viewModel.updateTargetHost(it) },
            label = { Text("Target Host") },
            placeholder = { Text("hostname or IP address") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )

        Spacer(Modifier.height(16.dp))

        // Target port
        OutlinedTextField(
            value = state.targetPort,
            onValueChange = { viewModel.updateTargetPort(it) },
            label = { Text("Target Port") },
            placeholder = { Text("4242") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )

        Spacer(Modifier.height(24.dp))

        // Bootstrap interface toggle (RNS 1.1.x feature)
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Bootstrap Interface",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        "Auto-disconnect once better connections are discovered",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(16.dp))
                Switch(
                    checked = state.bootstrapOnly,
                    onCheckedChange = { viewModel.toggleBootstrapOnly(it) },
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Tor/Orbot SOCKS proxy toggle
        Card(
            colors =
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Connect via Tor (Orbot)",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            "Route through SOCKS5 proxy. Required for .onion addresses. " +
                                "Orbot must be installed and connected.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(16.dp))
                    Switch(
                        checked = state.socksProxyEnabled,
                        onCheckedChange = { viewModel.toggleSocksProxy(it) },
                    )
                }

                // Show proxy host/port fields when enabled
                AnimatedVisibility(visible = state.socksProxyEnabled) {
                    Column(
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    ) {
                        OutlinedTextField(
                            value = state.socksProxyHost,
                            onValueChange = { viewModel.updateSocksProxyHost(it) },
                            label = { Text("SOCKS5 Proxy Host") },
                            placeholder = { Text("127.0.0.1") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = state.socksProxyPort,
                            onValueChange = { viewModel.updateSocksProxyPort(it) },
                            label = { Text("SOCKS5 Proxy Port") },
                            placeholder = { Text("9050") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                }
            }
        }

        // Bottom spacing for navigation bar
        Spacer(Modifier.height(100.dp))
    }
}
