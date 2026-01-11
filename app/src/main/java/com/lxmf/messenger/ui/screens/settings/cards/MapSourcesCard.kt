package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Feature flag to enable/disable RMSP UI.
 * Set to true when RMSP feature is stable and ready for production.
 */
private const val RMSP_FEATURE_ENABLED = false

/**
 * Settings card for managing map tile source preferences.
 *
 * Features:
 * - Toggle for HTTP map source (OpenFreeMap)
 * - Toggle for RMSP map source (Reticulum mesh) - controlled by RMSP_FEATURE_ENABLED
 * - Validation to prevent disabling both sources
 * - Info about available RMSP servers
 */
@Composable
fun MapSourcesCard(
    httpEnabled: Boolean,
    onHttpEnabledChange: (Boolean) -> Unit,
    rmspEnabled: Boolean,
    onRmspEnabledChange: (Boolean) -> Unit,
    rmspServerCount: Int = 0,
    hasOfflineMaps: Boolean = false,
) {
    // At least one source must be enabled (or have offline maps)
    // When RMSP is disabled via feature flag, only check hasOfflineMaps for HTTP toggle
    val effectiveRmspEnabled = RMSP_FEATURE_ENABLED && rmspEnabled
    val canDisableHttp = effectiveRmspEnabled || hasOfflineMaps
    val canDisableRmsp = httpEnabled || hasOfflineMaps
    val showWarning = !httpEnabled && !effectiveRmspEnabled && !hasOfflineMaps

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = "Map Sources",
                    tint = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = "Map Sources",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            // Description
            Text(
                text = "Configure how map tiles are fetched. Offline maps take priority when available.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // HTTP source toggle
            MapSourceToggle(
                title = "HTTP (OpenFreeMap)",
                description = "Fetch tiles from the internet",
                enabled = httpEnabled,
                onEnabledChange = { enabled ->
                    if (enabled || canDisableHttp) {
                        onHttpEnabledChange(enabled)
                    }
                },
                isDisabled = !httpEnabled && !canDisableHttp,
            )

            // RMSP source toggle - controlled by feature flag
            if (RMSP_FEATURE_ENABLED) {
                MapSourceToggle(
                    title = "RMSP (Mesh Network)",
                    description =
                        if (rmspServerCount > 0) {
                            "$rmspServerCount server${if (rmspServerCount != 1) "s" else ""} available"
                        } else {
                            "Fetch tiles from Reticulum mesh"
                        },
                    enabled = rmspEnabled,
                    onEnabledChange = { enabled ->
                        if (enabled || canDisableRmsp) {
                            onRmspEnabledChange(enabled)
                        }
                    },
                    isDisabled = !rmspEnabled && !canDisableRmsp,
                )
            }

            // Warning when no sources enabled
            if (showWarning) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "Warning",
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text = "At least one map source must be enabled",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Offline maps info
            if (hasOfflineMaps) {
                Text(
                    text = "Offline maps available - they will be used when location is covered",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

@Composable
private fun MapSourceToggle(
    title: String,
    description: String,
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    isDisabled: Boolean = false,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(enabled = !isDisabled) {
                    onEnabledChange(!enabled)
                },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(
            modifier = Modifier.weight(1f),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color =
                    if (isDisabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color =
                    if (isDisabled) {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )
        }
        Switch(
            checked = enabled,
            onCheckedChange = onEnabledChange,
            enabled = !isDisabled,
        )
    }
}
