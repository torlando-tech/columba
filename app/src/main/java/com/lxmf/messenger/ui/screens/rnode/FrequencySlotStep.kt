package com.lxmf.messenger.ui.screens.rnode

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.CommunitySlot
import com.lxmf.messenger.data.model.CommunitySlots
import com.lxmf.messenger.data.model.FrequencySlotCalculator
import com.lxmf.messenger.data.model.RNodeRegionalPreset
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel

/**
 * Step 4: Frequency Slot Selection
 *
 * Allows users to select a Meshtastic-style frequency slot with a visual
 * spectrum bar showing the frequency band and slot positions.
 */
@Composable
fun FrequencySlotStep(viewModel: RNodeWizardViewModel) {
    val state by viewModel.state.collectAsState()
    val region = state.selectedFrequencyRegion ?: return
    val bandwidth = state.selectedModemPreset.bandwidth

    val numSlots = viewModel.getNumSlots()
    val currentFreq = viewModel.getFrequencyForSlot(state.selectedSlot)
    val meshtasticSlots = viewModel.getCommunitySlots()
    val popularPresets = viewModel.getPopularPresetsForRegion()

    // Check if current slot overlaps with Meshtastic
    val isMeshtasticSlot = CommunitySlots.isMeshtasticSlot(region.id, state.selectedSlot)
    val hasCustomFrequency = state.customFrequency != null

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
    ) {
        // Header
        Text(
            text = "Select Frequency Slot",
            style = MaterialTheme.typography.headlineSmall,
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text =
                "Choose a frequency slot within the ${region.name} band. " +
                    "Avoid Meshtastic frequencies to prevent interference.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(16.dp))

        // Warning when Meshtastic slot selected
        AnimatedVisibility(visible = isMeshtasticSlot) {
            Card(
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                    ),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            "Meshtastic Interference",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            "Slot ${state.selectedSlot} overlaps with Meshtastic. " +
                                "Choose a different slot to avoid interference.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        // Current selection display
        CurrentSlotCard(
            slot = if (hasCustomFrequency) null else state.selectedSlot,
            frequency = state.customFrequency ?: currentFreq,
            numSlots = numSlots,
            isWarning = isMeshtasticSlot,
            presetName =
                state.selectedSlotPreset?.let {
                    it.cityOrRegion ?: it.countryName
                },
        )

        Spacer(Modifier.height(24.dp))

        // Spectrum visualization
        Text(
            text = "Frequency Spectrum",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        FrequencySpectrumBar(
            frequencyStart = region.frequencyStart,
            frequencyEnd = region.frequencyEnd,
            bandwidth = bandwidth,
            numSlots = numSlots,
            selectedSlot = state.selectedSlot,
            customFrequency = state.customFrequency,
            meshtasticSlots = meshtasticSlots,
            onSlotSelected = { viewModel.selectSlot(it) },
        )

        Spacer(Modifier.height(8.dp))

        // Frequency range labels
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = FrequencySlotCalculator.formatFrequency(region.frequencyStart),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = FrequencySlotCalculator.formatFrequency(region.frequencyEnd),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(24.dp))

        // Slot slider
        Text(
            text = "Slot Number",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))

        SlotPicker(
            slot = state.selectedSlot,
            maxSlot = numSlots - 1,
            onSlotChange = { viewModel.selectSlot(it) },
        )

        Spacer(Modifier.height(24.dp))

        // Popular RNode presets (recommended)
        if (popularPresets.isNotEmpty()) {
            Text(
                text = "Popular RNode Frequencies",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "Community-tested configurations for your region",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(8.dp))

            popularPresets.forEach { preset ->
                val presetSlot =
                    FrequencySlotCalculator.calculateSlotFromFrequency(
                        region,
                        bandwidth,
                        preset.frequency,
                    )
                val isSelected =
                    state.selectedSlotPreset?.id == preset.id ||
                        (presetSlot != null && state.selectedSlot == presetSlot && state.customFrequency == null)
                PopularPresetCard(
                    preset = preset,
                    slot = presetSlot,
                    isSelected = isSelected,
                    onSelect = {
                        if (presetSlot != null) {
                            // Frequency aligns with a slot - use slot selection
                            viewModel.selectSlot(presetSlot)
                        } else {
                            // Frequency doesn't align - use direct frequency selection
                            viewModel.selectPresetFrequency(preset)
                        }
                    },
                )
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(16.dp))
        }

        // Meshtastic frequencies (to avoid)
        if (meshtasticSlots.isNotEmpty()) {
            Text(
                text = "Meshtastic Frequencies (Avoid)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )

            Spacer(Modifier.height(4.dp))

            Text(
                text = "These frequencies are used by Meshtastic networks",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )

            Spacer(Modifier.height(8.dp))

            meshtasticSlots.forEach { slot ->
                MeshtasticSlotCard(
                    slot = slot,
                    isSelected = state.selectedSlot == slot.slot,
                    frequency = viewModel.getFrequencyForSlot(slot.slot),
                )
                Spacer(Modifier.height(8.dp))
            }
        }

        // Bottom spacing
        Spacer(Modifier.height(100.dp))
    }
}

@Composable
private fun CurrentSlotCard(
    slot: Int?,
    frequency: Long,
    numSlots: Int,
    isWarning: Boolean,
    presetName: String? = null,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isWarning) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.primaryContainer
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val contentColor =
                if (isWarning) {
                    MaterialTheme.colorScheme.onErrorContainer
                } else {
                    MaterialTheme.colorScheme.onPrimaryContainer
                }

            // Show preset name if custom frequency, otherwise show slot number
            if (presetName != null) {
                Text(
                    text = presetName,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            } else if (slot != null) {
                Text(
                    text = "Slot $slot",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor,
                )
            }

            Spacer(Modifier.height(4.dp))
            Text(
                text = FrequencySlotCalculator.formatFrequency(frequency),
                style = MaterialTheme.typography.titleLarge,
                color = contentColor.copy(alpha = 0.8f),
            )
            Spacer(Modifier.height(8.dp))

            // Show appropriate subtitle
            Text(
                text =
                    if (presetName != null) {
                        "Community preset frequency"
                    } else {
                        "of $numSlots available slots"
                    },
                style = MaterialTheme.typography.bodySmall,
                color = contentColor.copy(alpha = 0.6f),
            )
        }
    }
}

@Suppress("UnusedParameter")
@Composable
private fun FrequencySpectrumBar(
    frequencyStart: Long,
    frequencyEnd: Long,
    bandwidth: Int,
    numSlots: Int,
    selectedSlot: Int,
    customFrequency: Long?,
    meshtasticSlots: List<CommunitySlot>,
    onSlotSelected: (Int) -> Unit,
) {
    val primaryColor = MaterialTheme.colorScheme.primary
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val errorColor = MaterialTheme.colorScheme.error
    val onSurface = MaterialTheme.colorScheme.onSurface
    val tertiaryColor = MaterialTheme.colorScheme.tertiary

    // Guard against edge cases (single slot or invalid state)
    val effectiveNumSlots = maxOf(1, numSlots)
    val frequencyRange = (frequencyEnd - frequencyStart).toFloat()

    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .height(60.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(surfaceVariant)
                .pointerInput(effectiveNumSlots) {
                    detectTapGestures { offset ->
                        val slotWidth = size.width.toFloat() / effectiveNumSlots
                        val tappedSlot = (offset.x / slotWidth).toInt().coerceIn(0, effectiveNumSlots - 1)
                        onSlotSelected(tappedSlot)
                    }
                },
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize(),
        ) {
            val slotWidth = size.width / effectiveNumSlots

            // Draw slot markers (small ticks at regular intervals)
            val tickInterval =
                when {
                    numSlots > 50 -> 10
                    numSlots > 20 -> 5
                    else -> 1
                }

            for (i in 0 until numSlots step tickInterval) {
                val x = i * slotWidth + slotWidth / 2
                drawLine(
                    color = onSurface.copy(alpha = 0.2f),
                    start = Offset(x, size.height - 8.dp.toPx()),
                    end = Offset(x, size.height),
                    strokeWidth = 1.dp.toPx(),
                )
            }

            // Draw Meshtastic slot markers (red - to avoid)
            meshtasticSlots.forEach { slot ->
                val x = slot.slot * slotWidth + slotWidth / 2
                drawCircle(
                    color = errorColor.copy(alpha = 0.6f),
                    radius = 6.dp.toPx(),
                    center = Offset(x, size.height / 2),
                )
            }

            // Draw selected indicator - either slot-based or custom frequency position
            if (customFrequency != null && frequencyRange > 0) {
                // Custom frequency - calculate position based on actual frequency
                val ratio = (customFrequency - frequencyStart).toFloat() / frequencyRange
                val customX = ratio * size.width
                drawCircle(
                    color = tertiaryColor,
                    radius = 12.dp.toPx(),
                    center = Offset(customX, size.height / 2),
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(customX, size.height / 2),
                )
            } else {
                // Slot-based selection
                val selectedX = selectedSlot * slotWidth + slotWidth / 2
                drawCircle(
                    color = primaryColor,
                    radius = 12.dp.toPx(),
                    center = Offset(selectedX, size.height / 2),
                )
                drawCircle(
                    color = Color.White,
                    radius = 6.dp.toPx(),
                    center = Offset(selectedX, size.height / 2),
                )
            }
        }

        // Legend for Meshtastic slots
        if (meshtasticSlots.isNotEmpty()) {
            Row(
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(8.dp)
                            .background(errorColor.copy(alpha = 0.6f), CircleShape),
                )
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "Meshtastic",
                    style = MaterialTheme.typography.labelSmall,
                    color = onSurface.copy(alpha = 0.6f),
                )
            }
        }
    }
}

@Composable
private fun SlotPicker(
    slot: Int,
    maxSlot: Int,
    onSlotChange: (Int) -> Unit,
) {
    Column {
        // Increment/decrement buttons with current value
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = { if (slot > 0) onSlotChange(slot - 1) },
                enabled = slot > 0,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
            ) {
                Icon(Icons.Default.Remove, contentDescription = "Decrease slot")
            }

            Spacer(Modifier.width(24.dp))

            Text(
                text = "$slot",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(60.dp),
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.width(24.dp))

            FilledIconButton(
                onClick = { if (slot < maxSlot) onSlotChange(slot + 1) },
                enabled = slot < maxSlot,
                colors =
                    IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    ),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Increase slot")
            }
        }

        // Only show slider if there are multiple slots available
        // (Slider requires steps >= 0, and single-slot bands don't need a slider)
        if (maxSlot > 0) {
            Spacer(Modifier.height(16.dp))

            // Slider for quick navigation
            Slider(
                value = slot.toFloat(),
                onValueChange = { onSlotChange(it.toInt()) },
                valueRange = 0f..maxSlot.toFloat(),
                steps = maxSlot - 1,
                modifier = Modifier.fillMaxWidth(),
            )

            // Slider labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "0",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$maxSlot",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            // Single slot available - show informational message
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Only one frequency slot available for this region/preset combination",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun PopularPresetCard(
    preset: RNodeRegionalPreset,
    slot: Int?,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    OutlinedCard(
        onClick = onSelect,
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = preset.cityOrRegion ?: preset.countryName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preset.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    if (slot != null) {
                        Text(
                            text = "Slot $slot",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = FrequencySlotCalculator.formatFrequency(preset.frequency),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isSelected) {
                    Spacer(Modifier.width(12.dp))
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = "Selected",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun MeshtasticSlotCard(
    slot: CommunitySlot,
    isSelected: Boolean,
    frequency: Long,
) {
    Card(
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (isSelected) {
                        MaterialTheme.colorScheme.errorContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    },
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp),
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = slot.name,
                        style = MaterialTheme.typography.titleMedium,
                        color =
                            if (isSelected) {
                                MaterialTheme.colorScheme.onErrorContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = slot.description.removePrefix("⚠️ "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "Slot ${slot.slot}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = FrequencySlotCalculator.formatFrequency(frequency),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
