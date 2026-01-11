package com.lxmf.messenger.ui.screens.onboarding.pages

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.ui.screens.onboarding.OnboardingInterfaceType

/**
 * Connectivity page - allows user to select which network interfaces to enable.
 */
@Composable
fun ConnectivityPage(
    selectedInterfaces: Set<OnboardingInterfaceType>,
    onInterfaceToggle: (OnboardingInterfaceType) -> Unit,
    blePermissionsGranted: Boolean,
    blePermissionsDenied: Boolean,
    onBack: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        // Icon
        Icon(
            imageVector = Icons.Default.Router,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.primary,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Title
        Text(
            text = "How will you connect?",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Subtitle
        Text(
            text = "Select the networks you'd like to use:",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(24.dp))

        // Interface options
        InterfaceCard(
            interfaceType = OnboardingInterfaceType.AUTO,
            isSelected = selectedInterfaces.contains(OnboardingInterfaceType.AUTO),
            onClick = { onInterfaceToggle(OnboardingInterfaceType.AUTO) },
            icon = Icons.Default.Wifi,
        )

        Spacer(modifier = Modifier.height(12.dp))

        InterfaceCard(
            interfaceType = OnboardingInterfaceType.BLE,
            isSelected = selectedInterfaces.contains(OnboardingInterfaceType.BLE),
            onClick = { onInterfaceToggle(OnboardingInterfaceType.BLE) },
            icon = Icons.Default.Bluetooth,
            statusText =
                when {
                    blePermissionsDenied -> "Permissions denied"
                    blePermissionsGranted -> "Permissions granted"
                    else -> null
                },
            statusIsError = blePermissionsDenied,
        )

        Spacer(modifier = Modifier.height(12.dp))

        InterfaceCard(
            interfaceType = OnboardingInterfaceType.TCP,
            isSelected = selectedInterfaces.contains(OnboardingInterfaceType.TCP),
            onClick = { onInterfaceToggle(OnboardingInterfaceType.TCP) },
            icon = Icons.Default.Language,
        )

        Spacer(modifier = Modifier.height(12.dp))

        InterfaceCard(
            interfaceType = OnboardingInterfaceType.RNODE,
            isSelected = selectedInterfaces.contains(OnboardingInterfaceType.RNODE),
            onClick = { onInterfaceToggle(OnboardingInterfaceType.RNODE) },
            icon = Icons.Default.Router,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Helper text
        Text(
            text = "You can configure these later in Settings",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.weight(1f))

        // Navigation buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onBack,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Back")
            }

            Button(
                onClick = onContinue,
                modifier =
                    Modifier
                        .weight(1f)
                        .height(56.dp),
                shape = RoundedCornerShape(12.dp),
            ) {
                Text("Continue")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun InterfaceCard(
    interfaceType: OnboardingInterfaceType,
    isSelected: Boolean,
    onClick: () -> Unit,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    statusText: String? = null,
    statusIsError: Boolean = false,
) {
    val borderColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
                isSelected -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.outline
            },
        label = "borderColor",
    )

    val containerColor by animateColorAsState(
        targetValue =
            when {
                !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surface
            },
        label = "containerColor",
    )

    Card(
        onClick = { if (enabled) onClick() },
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = containerColor,
            ),
        border = BorderStroke(1.dp, borderColor),
        enabled = enabled,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { if (enabled) onClick() },
                enabled = enabled,
            )

            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .size(24.dp),
                tint =
                    if (enabled) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    },
            )

            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = interfaceType.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        },
                )
                Text(
                    text = interfaceType.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color =
                        if (enabled) {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        },
                )
                interfaceType.secondaryDescription?.let { secondary ->
                    Text(
                        text = secondary,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (enabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                            },
                    )
                }
                statusText?.let { status ->
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = status,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (statusIsError) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.primary
                            },
                    )
                }
            }
        }
    }
}
