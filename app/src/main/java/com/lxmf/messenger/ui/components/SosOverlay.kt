package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.SosState

/**
 * Overlay composable that renders SOS-related UI on top of the main navigation.
 *
 * - [SosState.Countdown]: Shows a dismissible countdown AlertDialog with a Cancel button.
 * - [SosState.Sending]: Shows a non-dismissible progress AlertDialog.
 * - [SosState.Active]: Shows a persistent red banner (positioned via [modifier]) and a
 *   deactivation dialog (with optional PIN validation) when the user taps Deactivate.
 * - [SosState.Idle]: Renders nothing.
 *
 * @param sosState Current SOS state.
 * @param sosDeactivationPin Configured deactivation PIN (null/blank = no PIN required).
 * @param onCancel Called when the user cancels during the countdown phase.
 * @param onDeactivate Called with the entered PIN (or null) when deactivating. Returns true on success.
 * @param modifier Applied to the active-state banner.
 */
@Composable
fun SosOverlay(
    sosState: SosState,
    sosDeactivationPin: String?,
    onCancel: () -> Unit,
    onDeactivate: (String?) -> Boolean,
    modifier: Modifier = Modifier,
) {
    when (val state = sosState) {
        is SosState.Idle -> {}

        is SosState.Countdown -> {
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(32.dp),
                    )
                },
                title = {
                    Text(
                        "SOS Activating",
                        color = MaterialTheme.colorScheme.error,
                        fontWeight = FontWeight.Bold,
                    )
                },
                text = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            "${state.remainingSeconds}",
                            style = MaterialTheme.typography.displayLarge,
                            color = MaterialTheme.colorScheme.error,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("Sending SOS in ${state.remainingSeconds} second(s).")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Tap CANCEL to abort.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = onCancel) {
                        Text("CANCEL", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    }
                },
            )
        }

        is SosState.Sending -> {
            AlertDialog(
                onDismissRequest = {},
                icon = {
                    CircularProgressIndicator(
                        modifier = Modifier.size(32.dp),
                        color = MaterialTheme.colorScheme.error,
                    )
                },
                title = { Text("Sending SOS\u2026") },
                text = { Text("Sending emergency messages to your contacts.") },
                confirmButton = {},
            )
        }

        is SosState.Active -> {
            var showDeactivateDialog by remember { mutableStateOf(false) }

            // Floating pill overlay — position controlled by parent modifier
            Surface(
                modifier = modifier,
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.error,
                shadowElevation = 6.dp,
                onClick = { showDeactivateDialog = true },
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "SOS ACTIVE",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "DEACTIVATE",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onError.copy(alpha = 0.8f),
                    )
                }
            }

            if (showDeactivateDialog) {
                val requiresPin = !sosDeactivationPin.isNullOrBlank()
                var enteredPin by remember { mutableStateOf("") }
                var pinError by remember { mutableStateOf(false) }

                AlertDialog(
                    onDismissRequest = { showDeactivateDialog = false },
                    title = { Text("Deactivate SOS") },
                    text = {
                        Column {
                            Text("Are you sure you want to deactivate the SOS emergency mode?")
                            if (requiresPin) {
                                Spacer(modifier = Modifier.height(12.dp))
                                OutlinedTextField(
                                    value = enteredPin,
                                    onValueChange = {
                                        enteredPin = it.filter { c -> c.isDigit() }.take(6)
                                        pinError = false
                                    },
                                    label = { Text("Deactivation PIN") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                    visualTransformation = PasswordVisualTransformation(),
                                    isError = pinError,
                                    supportingText =
                                        if (pinError) {
                                            { Text("Incorrect PIN", color = MaterialTheme.colorScheme.error) }
                                        } else {
                                            null
                                        },
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                val pin = if (requiresPin) enteredPin else null
                                val success = onDeactivate(pin)
                                if (success) {
                                    showDeactivateDialog = false
                                } else {
                                    pinError = true
                                }
                            },
                        ) {
                            Text("Deactivate", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeactivateDialog = false }) {
                            Text("Cancel")
                        }
                    },
                )
            }
        }
    }
}
