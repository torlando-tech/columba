@file:Suppress("MatchingDeclarationName")

package com.lxmf.messenger.ui.components

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.service.ConversationLinkManager
import java.util.Locale

/**
 * Data class representing an option in a quality selection dialog.
 *
 * @param T The type of the underlying value
 * @property value The actual value this option represents
 * @property displayName The name shown to the user
 * @property description A brief description of this option
 */
data class QualityOption<T>(
    val value: T,
    val displayName: String,
    val description: String,
    val isExperimental: Boolean = false,
)

/**
 * Generic dialog for selecting quality/profile options with link state awareness.
 *
 * Provides a consistent UI for quality selection across different features
 * (image compression, voice call codecs, etc.) with:
 * - Link state information display (hops, bitrate, MTU)
 * - Recommended option highlighting
 * - Radio button selection
 * - Optional transfer time estimates
 *
 * @param T The type of the option values
 * @param title Dialog title text
 * @param subtitle Optional subtitle/description shown below the title
 * @param options List of available options to choose from
 * @param initialSelection The initially selected option value
 * @param recommendedOption The recommended option value (shows "Recommended" badge)
 * @param linkState Current link state for displaying path info (null to hide)
 * @param transferTimeEstimates Optional map of option value to transfer time string
 * @param confirmButtonText Text for the confirm button (default: "Confirm")
 * @param onConfirm Called with selected value when user confirms
 * @param onDismiss Called when dialog is dismissed
 */
@Composable
fun <T> QualitySelectionDialog(
    title: String,
    subtitle: String? = null,
    options: List<QualityOption<T>>,
    initialSelection: T,
    recommendedOption: T,
    linkState: ConversationLinkManager.LinkState? = null,
    transferTimeEstimates: Map<T, String?>? = null,
    confirmButtonText: String = "Confirm",
    onConfirm: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedValue by remember { mutableStateOf(initialSelection) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineSmall,
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Subtitle if provided
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                // Path info section (shows link state)
                PathInfoSection(linkState)

                if (linkState != null) {
                    Spacer(modifier = Modifier.height(16.dp))
                }

                // Quality options list with scroll indicators
                val scrollState = rememberScrollState()
                ScrollableOptionsContainer(
                    scrollState = scrollState,
                    modifier = Modifier.heightIn(max = 400.dp),
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .verticalScroll(scrollState),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        options.forEach { option ->
                            QualityOptionRow(
                                displayName = option.displayName,
                                description = option.description,
                                isSelected = option.value == selectedValue,
                                isRecommended = option.value == recommendedOption,
                                isExperimental = option.isExperimental,
                                transferTime = transferTimeEstimates?.get(option.value),
                                onClick = { selectedValue = option.value },
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedValue) },
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Displays link path information (hops, bitrate, MTU).
 *
 * Shows different states:
 * - Connecting... (when establishing)
 * - Path metrics (when active)
 * - Connection failed (on error)
 * - No active link (when inactive)
 */
@Composable
fun PathInfoSection(linkState: ConversationLinkManager.LinkState?) {
    val pathInfo =
        when {
            linkState == null -> null
            linkState.isEstablishing -> "Connecting..."
            linkState.isActive -> {
                buildString {
                    linkState.hops?.let { append("$it hops") }

                    linkState.bestRateBps?.let { rate ->
                        if (isNotEmpty()) append(" • ")
                        append(formatBitrate(rate))
                    }

                    linkState.linkMtu?.let { mtu ->
                        if (isNotEmpty()) append(" • ")
                        append("${mtu}B MTU")
                    }
                }.ifEmpty { null }
            }
            linkState.error != null -> "Connection failed"
            else -> "No active link"
        }

    if (pathInfo != null) {
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.small,
        ) {
            Text(
                text = pathInfo,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * A single option row with radio button, title, description, and optional badges.
 *
 * Features:
 * - Radio button for selection
 * - "Recommended" chip badge when applicable
 * - Background highlighting for selected/recommended states
 * - Optional transfer time display
 */
@Composable
fun QualityOptionRow(
    displayName: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean,
    isExperimental: Boolean = false,
    transferTime: String? = null,
    onClick: () -> Unit,
) {
    val backgroundColor =
        when {
            isSelected -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
            isRecommended -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.2f)
            else -> Color.Transparent
        }

    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(
                    selected = isSelected,
                    onClick = onClick,
                    role = Role.RadioButton,
                ),
        color = backgroundColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RadioButton(
                selected = isSelected,
                onClick = null, // Handled by selectable modifier
            )

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                // Row 1: Title + Recommended badge
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = displayName,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isRecommended) FontWeight.SemiBold else FontWeight.Normal,
                    )

                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        RecommendedChip()
                    }

                    if (isExperimental && !isRecommended) {
                        Spacer(modifier = Modifier.width(8.dp))
                        ExperimentalChip()
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Row 2: Description + Transfer time
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = description,
                        modifier = Modifier.weight(1f, fill = false),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    if (transferTime != null) {
                        Text(
                            text = transferTime,
                            modifier = Modifier.padding(start = 8.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A star icon indicating the recommended option.
 */
@Composable
fun RecommendedChip() {
    Icon(
        imageVector = Icons.Filled.Star,
        contentDescription = "Recommended",
        modifier = Modifier.height(18.dp),
        tint = MaterialTheme.colorScheme.primary,
    )
}

/**
 * A warning icon indicating an experimental option that may not work well.
 */
@Composable
fun ExperimentalChip() {
    Icon(
        imageVector = Icons.Filled.Warning,
        contentDescription = "Experimental",
        modifier = Modifier.height(18.dp),
        tint = MaterialTheme.colorScheme.tertiary,
    )
}

/**
 * Container for scrollable content with dynamic scroll indicators.
 *
 * Shows a divider at the top when scrolled down (indicating content above)
 * and a fade gradient at the bottom when more content exists below.
 * This follows Material Design guidelines for scrollable dialogs.
 *
 * @param scrollState The scroll state to track position
 * @param modifier Modifier for the container
 * @param content The scrollable content
 */
@Composable
fun ScrollableOptionsContainer(
    scrollState: ScrollState,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    // Derive scroll position states
    val showTopIndicator by remember {
        derivedStateOf { scrollState.value > 0 }
    }
    val showBottomIndicator by remember {
        derivedStateOf {
            scrollState.value < scrollState.maxValue && scrollState.maxValue > 0
        }
    }

    Column(modifier = modifier) {
        // Top divider - appears when scrolled down
        if (showTopIndicator) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant,
            )
        }

        // Scrollable content with bottom fade overlay
        Box(modifier = Modifier.weight(1f, fill = false)) {
            content()

            // Bottom fade gradient - appears when more content below
            if (showBottomIndicator) {
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(24.dp)
                            .background(
                                brush =
                                    Brush.verticalGradient(
                                        colors =
                                            listOf(
                                                Color.Transparent,
                                                MaterialTheme.colorScheme.surface,
                                            ),
                                    ),
                            ),
                )
            }
        }
    }
}

/**
 * Format bitrate in bits per second to human-readable string.
 */
fun formatBitrate(bps: Long): String =
    when {
        bps >= 1_000_000 -> String.format(Locale.US, "%.1f Mbps", bps / 1_000_000.0)
        bps >= 1_000 -> String.format(Locale.US, "%.1f kbps", bps / 1_000.0)
        else -> "$bps bps"
    }
