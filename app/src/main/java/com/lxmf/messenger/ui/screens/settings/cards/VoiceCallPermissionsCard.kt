package com.lxmf.messenger.ui.screens.settings.cards

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.lxmf.messenger.notifications.CallNotificationHelper
import kotlinx.coroutines.delay

@Composable
fun VoiceCallPermissionsCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    // Not relevant below Android 10 — background activity launch restrictions
    // only became an issue starting with Q
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return

    val context = LocalContext.current
    val helper = remember { CallNotificationHelper(context.applicationContext) }

    var micGranted by remember { mutableStateOf(false) }
    var overlayGranted by remember { mutableStateOf(false) }
    var fullScreenGranted by remember { mutableStateOf(false) }
    var isCheckingStatus by remember { mutableStateOf(true) }

    val needsFullScreenPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
    val allGranted = micGranted && overlayGranted && (!needsFullScreenPermission || fullScreenGranted)

    // Poll permission status (500ms initial + 3s loop, matches BatteryOptimizationCard)
    LaunchedEffect(Unit) {
        delay(500)
        micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        overlayGranted = helper.canDrawOverlays()
        fullScreenGranted = helper.canUseFullScreenIntent()
        isCheckingStatus = false

        while (true) {
            delay(3000)
            micGranted = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.RECORD_AUDIO,
            ) == PackageManager.PERMISSION_GRANTED
            overlayGranted = helper.canDrawOverlays()
            fullScreenGranted = helper.canUseFullScreenIntent()
        }
    }

    val containerColor =
        if (allGranted) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.errorContainer
        }
    val contentColor =
        if (allGranted) {
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
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header row (always visible)
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
                        imageVector = if (allGranted) Icons.Default.CheckCircle else Icons.Default.Info,
                        contentDescription = null,
                        tint = contentColor,
                    )
                    Text(
                        text = "Voice Call Permissions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = contentColor,
                    )
                }

                Icon(
                    imageVector =
                        if (isExpanded) {
                            Icons.Default.KeyboardArrowUp
                        } else {
                            Icons.Default.KeyboardArrowDown
                        },
                    contentDescription = if (isExpanded) "Collapse" else "Expand",
                    tint = contentColor,
                )
            }

            // Expanded content with animation
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(animationSpec = tween(durationMillis = 300)),
                exit = shrinkVertically(animationSpec = tween(durationMillis = 300)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (isCheckingStatus) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                    } else if (allGranted) {
                        Text(
                            text = "All permissions granted. Incoming voice calls will show a full-screen call screen.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        PermissionStatusRow(
                            label = "Microphone",
                            granted = true,
                            contentColor = contentColor,
                        )

                        PermissionStatusRow(
                            label = "Display over other apps",
                            granted = true,
                            contentColor = contentColor,
                        )

                        if (needsFullScreenPermission) {
                            PermissionStatusRow(
                                label = "Full-screen notifications",
                                granted = true,
                                contentColor = contentColor,
                            )
                        }
                    } else {
                        Text(
                            text = "Some permissions are missing. Without them, voice calls may not work correctly.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = contentColor,
                        )

                        PermissionRow(
                            label = "Microphone",
                            description = "Required to capture audio during voice calls.",
                            granted = micGranted,
                            contentColor = contentColor,
                            onGrantClick = {
                                val intent =
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                context.startActivity(intent)
                            },
                        )

                        PermissionRow(
                            label = "Display over other apps",
                            description = "Required to show the call screen when the app is in the background.",
                            granted = overlayGranted,
                            contentColor = contentColor,
                            onGrantClick = {
                                context.startActivity(helper.getOverlayPermissionSettingsIntent())
                            },
                        )

                        if (needsFullScreenPermission) {
                            PermissionRow(
                                label = "Full-screen notifications",
                                description = "Required on Android 14+ to launch the call screen from a notification.",
                                granted = fullScreenGranted,
                                contentColor = contentColor,
                                onGrantClick = {
                                    helper.getFullScreenIntentSettingsIntent()?.let {
                                        context.startActivity(it)
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PermissionRow(
    label: String,
    description: String,
    granted: Boolean,
    contentColor: Color,
    onGrantClick: () -> Unit,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        PermissionStatusRow(
            label = label,
            granted = granted,
            contentColor = contentColor,
        )

        Text(
            text = description,
            style = MaterialTheme.typography.bodySmall,
            color = contentColor.copy(alpha = 0.8f),
            modifier = Modifier.padding(start = 28.dp),
        )

        if (!granted) {
            Button(
                onClick = onGrantClick,
                modifier = Modifier.padding(start = 28.dp),
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                    ),
            ) {
                Text("Open Settings")
            }
        }
    }
}

@Composable
private fun PermissionStatusRow(
    label: String,
    granted: Boolean,
    contentColor: Color,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.Close,
            contentDescription = if (granted) "Granted" else "Not granted",
            tint = contentColor,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = contentColor,
        )
    }
}
