package com.lxmf.messenger.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import com.lxmf.messenger.util.ThemeColorGenerator

/**
 * ROYGBIV preset colors for quick selection.
 * Each color is defined by its HSL values for easy slider updates.
 */
private data class PresetColor(
    val name: String,
    val color: Color,
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
)

private val roygbivPresets =
    listOf(
        PresetColor("Red", Color(0xFFF44336), 4f, 0.90f, 0.58f),
        PresetColor("Orange", Color(0xFFFF9800), 36f, 1.0f, 0.50f),
        PresetColor("Yellow", Color(0xFFFFEB3B), 54f, 1.0f, 0.62f),
        PresetColor("Green", Color(0xFF4CAF50), 122f, 0.39f, 0.49f),
        PresetColor("Blue", Color(0xFF2196F3), 207f, 0.90f, 0.54f),
        PresetColor("Indigo", Color(0xFF3F51B5), 231f, 0.48f, 0.48f),
        PresetColor("Violet", Color(0xFF9C27B0), 291f, 0.64f, 0.42f),
        PresetColor("White", Color(0xFFFFFFFF), 0f, 0f, 1f),
        PresetColor("Black", Color(0xFF000000), 0f, 0f, 0f),
    )

/**
 * Color picker dialog with HSV sliders and hex input.
 * Allows users to select a color with real-time preview.
 *
 * @param initialColor Starting color (defaults to primary color)
 * @param title Dialog title
 * @param onConfirm Callback when user confirms color selection
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun ColorPickerDialog(
    initialColor: Color,
    title: String = "Pick a Color",
    onConfirm: (Color) -> Unit,
    onDismiss: () -> Unit,
) {
    // Convert initial color to HSV
    val hsvArray = FloatArray(3)
    ColorUtils.colorToHSL(initialColor.toArgb(), hsvArray)

    var hue by remember { mutableFloatStateOf(hsvArray[0]) }
    var saturation by remember { mutableFloatStateOf(hsvArray[1]) }
    var lightness by remember { mutableFloatStateOf(hsvArray[2]) }
    var hexInput by remember {
        mutableStateOf(ThemeColorGenerator.argbToHex(initialColor.toArgb(), includeAlpha = false))
    }
    var isEditingHex by remember { mutableStateOf(false) }

    // Calculate current color from HSL values
    val currentColor =
        remember(hue, saturation, lightness) {
            val hsl = floatArrayOf(hue, saturation, lightness)
            Color(ColorUtils.HSLToColor(hsl))
        }

    // Update hex input when sliders change (but not when user is typing)
    LaunchedEffect(currentColor, isEditingHex) {
        if (!isEditingHex) {
            hexInput = ThemeColorGenerator.argbToHex(currentColor.toArgb(), includeAlpha = false)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Color preview box
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(100.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(currentColor)
                                .border(
                                    width = 2.dp,
                                    color = MaterialTheme.colorScheme.outline,
                                    shape = RoundedCornerShape(12.dp),
                                ),
                    )
                }

                // Quick color presets (ROYGBIV + B&W)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    roygbivPresets.forEach { preset ->
                        Box(
                            modifier =
                                Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(preset.color)
                                    .border(
                                        width = 1.dp,
                                        color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                                        shape = CircleShape,
                                    )
                                    .clickable {
                                        hue = preset.hue
                                        saturation = preset.saturation
                                        lightness = preset.lightness
                                    },
                        )
                    }
                }

                // Hex input field
                TextField(
                    value = hexInput,
                    onValueChange = { newHex ->
                        isEditingHex = true
                        hexInput = newHex
                        // Try to parse hex and update HSL if valid
                        ThemeColorGenerator.hexToArgb(newHex)?.let { argb ->
                            val hsl = FloatArray(3)
                            ColorUtils.colorToHSL(argb, hsl)
                            hue = hsl[0]
                            saturation = hsl[1]
                            lightness = hsl[2]
                        }
                    },
                    label = { Text("Hex Color") },
                    placeholder = { Text("#RRGGBB") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .onFocusChanged { focusState ->
                                if (!focusState.isFocused) {
                                    isEditingHex = false
                                }
                            },
                )

                // Hue slider (0-360)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Hue",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${hue.toInt()}°",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = hue,
                        onValueChange = { hue = it },
                        valueRange = 0f..360f,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = currentColor,
                            ),
                    )
                }

                // Saturation slider (0-1)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Saturation",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${(saturation * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = saturation,
                        onValueChange = { saturation = it },
                        valueRange = 0f..1f,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = currentColor,
                            ),
                    )
                }

                // Lightness slider (0-1)
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Lightness",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "${(lightness * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Slider(
                        value = lightness,
                        onValueChange = { lightness = it },
                        valueRange = 0f..1f,
                        colors =
                            SliderDefaults.colors(
                                thumbColor = currentColor,
                                activeTrackColor = currentColor,
                            ),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(currentColor)
                    onDismiss()
                },
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
