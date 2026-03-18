package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.components.CollapsibleSettingsCard
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.CustomTheme
import com.lxmf.messenger.ui.theme.PresetTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSelectionCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedTheme: AppTheme,
    customThemes: List<AppTheme>,
    onThemeChange: (AppTheme) -> Unit,
    onNavigateToCustomThemes: () -> Unit = {},
) {
    CollapsibleSettingsCard(
        title = stringResource(R.string.theme_selection_title),
        icon = Icons.Default.Palette,
        isExpanded = isExpanded,
        onExpandedChange = onExpandedChange,
        headerAction = {
            TextButton(onClick = onNavigateToCustomThemes) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.theme_selection_custom_themes))
            }
        },
    ) {
        // Description
        Text(
            text = stringResource(R.string.theme_selection_description),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Built-in themes section
        Text(
            text = stringResource(R.string.theme_selection_built_in_themes),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PresetTheme.entries.forEach { theme ->
                FilterChip(
                    selected = selectedTheme == theme,
                    onClick = { onThemeChange(theme) },
                    label = { Text(localizedThemeDisplayName(theme)) },
                    leadingIcon =
                        if (selectedTheme == theme) {
                            {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = stringResource(R.string.status_selected),
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        } else {
                            null
                        },
                )
            }
        }

        // Custom themes section (only show if there are custom themes)
        if (customThemes.isNotEmpty()) {
            Text(
                text = stringResource(R.string.theme_selection_custom_themes),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                customThemes.forEach { theme ->
                    FilterChip(
                        selected = selectedTheme == theme,
                        onClick = { onThemeChange(theme) },
                        label = { Text(theme.displayName) },
                        leadingIcon =
                            if (selectedTheme == theme) {
                                {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = stringResource(R.string.status_selected),
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            } else {
                                null
                            },
                    )
                }
            }
        }

        // Description of selected theme
        Text(
            text = localizedThemeDescription(selectedTheme),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )

        // Color preview
        ThemeColorPreview(theme = selectedTheme)
    }
}

@Composable
fun ThemeColorPreview(theme: AppTheme) {
    val isDarkTheme = isSystemInDarkTheme()
    val (primary, secondary, tertiary) =
        remember(theme, isDarkTheme) {
            theme.getPreviewColors(isDarkTheme)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.theme_selection_preview),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(color = primary, shape = CircleShape),
        )

        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(color = secondary, shape = CircleShape),
        )

        Box(
            modifier =
                Modifier
                    .size(32.dp)
                    .background(color = tertiary, shape = CircleShape),
        )

        Spacer(modifier = Modifier.weight(1f))

        Text(
            text = if (isDarkTheme) stringResource(R.string.theme_selection_dark_mode) else stringResource(R.string.theme_selection_light_mode),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun localizedThemeDisplayName(theme: AppTheme): String =
    when (theme) {
        is CustomTheme -> theme.displayName
        PresetTheme.VIBRANT -> stringResource(R.string.theme_preset_vibrant)
        PresetTheme.DYNAMIC -> stringResource(R.string.theme_preset_dynamic)
        PresetTheme.OCEAN -> stringResource(R.string.theme_preset_ocean)
        PresetTheme.FOREST -> stringResource(R.string.theme_preset_forest)
        PresetTheme.SUNSET -> stringResource(R.string.theme_preset_sunset)
        PresetTheme.MONOCHROME -> stringResource(R.string.theme_preset_monochrome)
        PresetTheme.OLED_BLACK -> stringResource(R.string.theme_preset_oled_black)
        PresetTheme.EXPRESSIVE -> stringResource(R.string.theme_preset_expressive)
    }

@Composable
private fun localizedThemeDescription(theme: AppTheme): String =
    when (theme) {
        is CustomTheme -> theme.description
        PresetTheme.VIBRANT -> stringResource(R.string.theme_preset_vibrant_description)
        PresetTheme.DYNAMIC -> stringResource(R.string.theme_preset_dynamic_description)
        PresetTheme.OCEAN -> stringResource(R.string.theme_preset_ocean_description)
        PresetTheme.FOREST -> stringResource(R.string.theme_preset_forest_description)
        PresetTheme.SUNSET -> stringResource(R.string.theme_preset_sunset_description)
        PresetTheme.MONOCHROME -> stringResource(R.string.theme_preset_monochrome_description)
        PresetTheme.OLED_BLACK -> stringResource(R.string.theme_preset_oled_black_description)
        PresetTheme.EXPRESSIVE -> stringResource(R.string.theme_preset_expressive_description)
    }
