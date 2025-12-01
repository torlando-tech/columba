package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.util.BatteryOptimizationManager
import kotlinx.coroutines.delay

@Composable
fun BatteryOptimizationCard() {
    val context = LocalContext.current
    var isExempted by remember { mutableStateOf(false) }
    var isCheckingStatus by remember { mutableStateOf(true) }

    // Check exemption status on initial load and periodically (every 3 seconds)
    LaunchedEffect(Unit) {
        // Initial check with loading indicator
        delay(500)
        isExempted = BatteryOptimizationManager.isIgnoringBatteryOptimizations(context)
        isCheckingStatus = false

        // Continue checking in background without showing loading spinner
        while (true) {
            delay(3000)
            isExempted = BatteryOptimizationManager.isIgnoringBatteryOptimizations(context)
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isExempted) {
                        MaterialTheme.colorScheme.secondaryContainer
                    } else {
                        MaterialTheme.colorScheme.errorContainer
                    },
            ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = if (isExempted) Icons.Default.CheckCircle else Icons.Default.Info,
                    contentDescription = null,
                    tint =
                        if (isExempted) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                )
                Text(
                    text = "Background Service Protection",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color =
                        if (isExempted) {
                            MaterialTheme.colorScheme.onSecondaryContainer
                        } else {
                            MaterialTheme.colorScheme.onErrorContainer
                        },
                )
            }

            if (isCheckingStatus) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else if (isExempted) {
                Text(
                    text = "Battery optimization exemption granted. Columba can run reliably in the background.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )

                OutlinedButton(
                    onClick = {
                        val intent = BatteryOptimizationManager.createBatterySettingsIntent()
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("View Battery Settings")
                }
            } else {
                Text(
                    text = "Battery optimization is enabled. Android may kill the background service during Deep Doze mode, causing gaps in message delivery.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )

                Button(
                    onClick = {
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                            BatteryOptimizationManager.recordPromptShown(context)
                            val intent = BatteryOptimizationManager.createRequestExemptionIntent(context)
                            context.startActivity(intent)
                            // Status will auto-refresh within 3 seconds
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Text("Request Exemption")
                }

                TextButton(
                    onClick = {
                        val intent = BatteryOptimizationManager.createBatterySettingsIntent()
                        context.startActivity(intent)
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open Battery Settings Manually")
                }
            }
        }
    }
}
