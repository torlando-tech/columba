package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard

/**
 * Feature flag to enable/disable RMSP UI.
 * Set to true when RMSP feature is stable and ready for production.
 */
private const val RMSP_FEATURE_ENABLED = false

/**
 * Settings card for managing map tile source preferences.
 *
 * Features:
 * - Toggle for HTTP map source (OpenFreeMap) - shown in header
 * - Toggle for RMSP map source (Reticulum mesh) - shown in content, controlled by RMSP_FEATURE_ENABLED
 * - Validation to prevent disabling both sources
 * - Info about available RMSP servers
 */
@Composable
fun MapSourcesCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    httpEnabled: Boolean,
    onHttpEnabledChange: (Boolean) -> Unit,
    rmspEnabled: Boolean,
    onRmspEnabledChange: (Boolean) -> Unit,
    markerDeclutterEnabled: Boolean = true,
    onMarkerDeclutterEnabledChange: (Boolean) -> Unit = {},
    rmspServerCount: Int = 0,
    hasOfflineMaps: Boolean = false,
) {
    // Allow disabling HTTP - the map screen now shows a helpful overlay when no sources enabled
    // This lets users intentionally disable HTTP for offline-only or privacy-conscious use
    val effectiveRmspEnabled = RMSP_FEATURE_ENABLED && rmspEnabled
    val canDisableHttp = true // Always allow - MapScreen shows overlay when no sources available
    val canDisableRmsp = httpEnabled || hasOfflineMaps
    val showWarning = !httpEnabled && !effectiveRmspEnabled && !hasOfflineMaps

    CollapsibleSettingsCard(
        title = stringResource(R.string.map_sources_title),
        icon = Icons.Default.Map,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
    ) {
        // Description
        Text(
            text = stringResource(R.string.map_sources_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(
            text = stringResource(R.string.map_sources_section_sources),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )

        // HTTP source toggle
        MapSourceToggle(
            title = stringResource(R.string.map_sources_http_title),
            description = stringResource(R.string.map_sources_http_description),
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
                title = stringResource(R.string.map_sources_rmsp_title),
                description =
                    if (rmspServerCount > 0) {
                        if (rmspServerCount == 1) {
                            stringResource(R.string.map_sources_rmsp_servers_one, rmspServerCount)
                        } else {
                            stringResource(R.string.map_sources_rmsp_servers_other, rmspServerCount)
                        }
                    } else {
                        stringResource(R.string.map_sources_rmsp_description)
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

        Text(
            text = stringResource(R.string.map_sources_section_settings),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(top = 8.dp),
        )

        // Marker declutter toggle
        MapSourceToggle(
            title = stringResource(R.string.map_sources_marker_declutter_title),
            description = stringResource(R.string.map_sources_marker_declutter_description),
            enabled = markerDeclutterEnabled,
            onEnabledChange = onMarkerDeclutterEnabledChange,
        )

        // Info when no sources enabled
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
                    contentDescription = stringResource(R.string.map_sources_warning_content_description),
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = stringResource(R.string.map_sources_no_sources_warning),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        // Offline maps info
        if (hasOfflineMaps) {
            Text(
                text = stringResource(R.string.map_sources_offline_maps_available),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
            )
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
