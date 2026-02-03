package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.viewmodel.DiscoveredInterfacesSortMode

/**
 * Sort mode selector with segmented buttons for discovered interfaces.
 */
@Composable
fun SortModeSelector(
    currentMode: DiscoveredInterfacesSortMode,
    hasUserLocation: Boolean,
    onModeSelected: (DiscoveredInterfacesSortMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Availability & Quality button
        SortModeButton(
            text = "Quality",
            isSelected = currentMode == DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY,
            onClick = { onModeSelected(DiscoveredInterfacesSortMode.AVAILABILITY_AND_QUALITY) },
            modifier = Modifier.weight(1f),
        )

        // Proximity button (disabled if no user location)
        SortModeButton(
            text = "Proximity",
            isSelected = currentMode == DiscoveredInterfacesSortMode.PROXIMITY,
            enabled = hasUserLocation,
            onClick = { onModeSelected(DiscoveredInterfacesSortMode.PROXIMITY) },
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * Individual sort mode button with selected/disabled states.
 */
@Composable
private fun SortModeButton(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val containerColor =
        when {
            !enabled -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
            isSelected -> MaterialTheme.colorScheme.primaryContainer
            else -> MaterialTheme.colorScheme.surfaceVariant
        }
    val contentColor =
        when {
            !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        }

    Surface(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(vertical = 12.dp, horizontal = 16.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
            color = contentColor,
            textAlign = TextAlign.Center,
        )
    }
}
