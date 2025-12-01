package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.viewmodel.MainViewModel
import com.lxmf.messenger.viewmodel.UiState

/**
 * Main screen of the Columba application.
 * Demonstrates integration with Reticulum protocol through the abstraction layer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: MainViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val networkStatus by viewModel.networkStatus.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Columba LXMF Messenger") },
                actions = {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = "Network status",
                        tint = Color(viewModel.getNetworkStatusColor()),
                        modifier = Modifier.padding(end = 16.dp),
                    )
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header
            Text(
                text = "Hello, Reticulum!",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.padding(top = 32.dp),
            )

            Text(
                text = "This is a demonstration of the Kotlin UI layer communicating with the Reticulum abstraction layer.",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Network Status Card
            Card(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text = "Network Status",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = networkStatus.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            // Action Buttons
            Button(
                onClick = { viewModel.initializeReticulum() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Initialize Reticulum")
            }

            Button(
                onClick = { viewModel.createIdentity() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Create Identity")
            }

            Button(
                onClick = { viewModel.testSendPacket() },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Test Send Packet")
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Status/Result Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    when (uiState) {
                        is UiState.Error ->
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                            )
                        is UiState.Success ->
                            CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer,
                            )
                        else -> CardDefaults.cardColors()
                    },
            ) {
                Column(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                ) {
                    Text(
                        text =
                            when (uiState) {
                                is UiState.Initial -> "Ready"
                                is UiState.Loading -> "Status"
                                is UiState.Success -> "Success"
                                is UiState.Error -> "Error"
                            },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    when (val state = uiState) {
                        is UiState.Initial -> {
                            Text("Click the buttons above to test the Reticulum abstraction layer.")
                        }
                        is UiState.Loading -> {
                            CircularProgressIndicator(
                                modifier =
                                    Modifier
                                        .padding(8.dp)
                                        .size(24.dp),
                            )
                            Text(state.message)
                        }
                        is UiState.Success -> {
                            Text(state.message)
                        }
                        is UiState.Error -> {
                            Text(state.message)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // Footer
            Text(
                text = "Powered by Reticulum Network Stack",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
