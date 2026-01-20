package com.lxmf.messenger.ui.screens.flasher.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmf.messenger.viewmodel.FlasherStep

/**
 * Horizontal numbered stepper for the flasher wizard.
 *
 * Design:
 * ```
 *     ①────────②────────③────────④────────⑤
 *   Device    Detect  Firmware  Flash   Done
 * ```
 *
 * - Completed steps: Filled primary circle
 * - Current step: Primary outlined circle (highlighted)
 * - Future steps: Dimmed outlined circle
 * - Connecting lines animate during transitions
 */
@Composable
fun FlasherStepIndicator(
    currentStep: FlasherStep,
    modifier: Modifier = Modifier,
) {
    val steps =
        listOf(
            StepInfo(FlasherStep.DEVICE_SELECTION, "Device", 1),
            StepInfo(FlasherStep.DEVICE_DETECTION, "Detect", 2),
            StepInfo(FlasherStep.FIRMWARE_SELECTION, "Firmware", 3),
            StepInfo(FlasherStep.FLASH_PROGRESS, "Flash", 4),
            StepInfo(FlasherStep.COMPLETE, "Done", 5),
        )

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Step circles with connecting lines
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            steps.forEachIndexed { index, stepInfo ->
                val state =
                    when {
                        stepInfo.step.ordinal < currentStep.ordinal -> StepState.COMPLETED
                        stepInfo.step == currentStep -> StepState.CURRENT
                        else -> StepState.FUTURE
                    }

                // Step circle
                StepCircle(
                    number = stepInfo.number,
                    state = state,
                )

                // Connecting line (except after last step)
                if (index < steps.lastIndex) {
                    val nextStepCompleted = steps[index + 1].step.ordinal <= currentStep.ordinal
                    StepConnector(
                        isCompleted = nextStepCompleted,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // Step labels
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Top,
        ) {
            steps.forEach { stepInfo ->
                val state =
                    when {
                        stepInfo.step.ordinal < currentStep.ordinal -> StepState.COMPLETED
                        stepInfo.step == currentStep -> StepState.CURRENT
                        else -> StepState.FUTURE
                    }

                StepLabel(
                    label = stepInfo.label,
                    state = state,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

private data class StepInfo(
    val step: FlasherStep,
    val label: String,
    val number: Int,
)

private enum class StepState {
    COMPLETED,
    CURRENT,
    FUTURE,
}

@Composable
private fun StepCircle(
    number: Int,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    val backgroundColor by animateColorAsState(
        targetValue =
            when (state) {
                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                StepState.CURRENT -> MaterialTheme.colorScheme.surface
                StepState.FUTURE -> MaterialTheme.colorScheme.surface
            },
        animationSpec = tween(durationMillis = 300),
        label = "stepBackgroundColor",
    )

    val borderColor by animateColorAsState(
        targetValue =
            when (state) {
                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                StepState.CURRENT -> MaterialTheme.colorScheme.primary
                StepState.FUTURE -> MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(durationMillis = 300),
        label = "stepBorderColor",
    )

    val textColor by animateColorAsState(
        targetValue =
            when (state) {
                StepState.COMPLETED -> MaterialTheme.colorScheme.onPrimary
                StepState.CURRENT -> MaterialTheme.colorScheme.primary
                StepState.FUTURE -> MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(durationMillis = 300),
        label = "stepTextColor",
    )

    Box(
        modifier =
            modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(backgroundColor)
                .border(
                    width = 2.dp,
                    color = borderColor,
                    shape = CircleShape,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = number.toString(),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = textColor,
        )
    }
}

@Composable
private fun StepConnector(
    isCompleted: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress by animateFloatAsState(
        targetValue = if (isCompleted) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "connectorProgress",
    )

    Box(
        modifier =
            modifier
                .padding(horizontal = 4.dp)
                .height(2.dp),
    ) {
        // Background line (always visible)
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant),
        )

        // Progress overlay
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(progress)
                    .height(2.dp)
                    .background(MaterialTheme.colorScheme.primary),
        )
    }
}

@Composable
private fun StepLabel(
    label: String,
    state: StepState,
    modifier: Modifier = Modifier,
) {
    val textColor by animateColorAsState(
        targetValue =
            when (state) {
                StepState.COMPLETED -> MaterialTheme.colorScheme.primary
                StepState.CURRENT -> MaterialTheme.colorScheme.primary
                StepState.FUTURE -> MaterialTheme.colorScheme.outlineVariant
            },
        animationSpec = tween(durationMillis = 300),
        label = "labelColor",
    )

    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 10.sp,
        color = textColor,
        textAlign = TextAlign.Center,
        maxLines = 1,
        modifier = modifier,
    )
}
