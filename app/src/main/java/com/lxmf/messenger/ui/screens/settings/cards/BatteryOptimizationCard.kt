package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.lxmf.messenger.reticulum.model.BatteryProfile
import com.lxmf.messenger.util.BatteryOptimizationManager

@Composable
fun BatteryOptimizationCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    batteryProfile: BatteryProfile,
    onBatteryProfileChange: (BatteryProfile) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isExempted by remember { mutableStateOf(false) }
    var isCheckingStatus by remember { mutableStateOf(true) }

    fun refreshStatus() {
        isExempted = BatteryOptimizationManager.isIgnoringBatteryOptimizations(context)
        isCheckingStatus = false
    }

    LaunchedEffect(context) {
        refreshStatus()
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    refreshStatus()
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val containerColor =
        if (isExempted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        if (isExempted) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onErrorContainer
        }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onExpandedChange(!isExpanded) },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = if (isExempted) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Column {
                        Text(
                            text = "Battery & Background",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = contentColor,
                        )
                        Text(
                            text = batteryProfile.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = contentColor,
                        )
                    }
                }

                Icon(
                    imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = contentColor,
                )
            }

            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 300)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 300)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = "Choose how aggressively Columba should run native Reticulum in the background. Changes apply immediately.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = contentColor,
                    )

                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.35f)),
                    ) {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            BatteryProfile.entries.forEach { profile ->
                                Row(
                                    modifier =
                                        Modifier
                                            .fillMaxWidth()
                                            .selectable(
                                                selected = batteryProfile == profile,
                                                onClick = { onBatteryProfileChange(profile) },
                                                role = Role.RadioButton,
                                            ).padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = batteryProfile == profile,
                                        onClick = null,
                                    )
                                    Column(
                                        modifier =
                                            Modifier
                                                .padding(start = 16.dp)
                                                .weight(1f),
                                    ) {
                                        Text(
                                            text = profile.displayName,
                                            style = MaterialTheme.typography.titleSmall,
                                            color = contentColor,
                                        )
                                        Text(
                                            text = profile.description,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = contentColor.copy(alpha = 0.9f),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider(color = contentColor.copy(alpha = 0.2f))

                    if (isCheckingStatus) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (isExempted) {
                        Text(
                            text = "Battery optimization exemption granted. Columba can run more reliably in the background during deep idle.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
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
                            text =
                                "Android battery optimization is still enabled. The battery profile above reduces " +
                                    "background work, but Android may still delay or kill background networking " +
                                    "unless Columba is exempted.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        Button(
                            onClick = {
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
                                    BatteryOptimizationManager.recordPromptShown(context)
                                    BatteryOptimizationManager.requestBatteryOptimizationExemption(context)
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
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
    }
}
