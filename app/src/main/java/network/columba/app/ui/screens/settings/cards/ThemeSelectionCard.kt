package network.columba.app.ui.screens.settings.cards

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import network.columba.app.ui.components.CollapsibleSettingsCard
import network.columba.app.ui.theme.AppTheme
import network.columba.app.ui.theme.PresetTheme
import network.columba.app.ui.theme.ThemeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSelectionCard(
    isExpanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    selectedTheme: AppTheme,
    customThemes: List<AppTheme>,
    themeMode: ThemeMode,
    onThemeChange: (AppTheme) -> Unit,
    onThemeModeChange: (ThemeMode) -> Unit,
    onNavigateToCustomThemes: () -> Unit = {},
) {
    CollapsibleSettingsCard(
        title = "Theme",
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
                Text("Custom Themes")
            }
        },
    ) {
        // Description
        Text(
            text = "Choose your preferred color theme. Theme mode follows your system settings by default and can be overridden below.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Theme mode section
        Text(
            text = "Theme Mode",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        ThemeChipRow(
            options = ThemeMode.entries.toList(),
            selected = themeMode,
            onSelect = onThemeModeChange,
        ) { it.displayName }

        // Built-in themes section
        Text(
            text = "Built-in Themes",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp),
        )

        ThemeChipRow(
            options = PresetTheme.entries.toList(),
            selected = selectedTheme,
            onSelect = onThemeChange,
        ) { it.displayName }

        // Custom themes section (only show if there are custom themes)
        if (customThemes.isNotEmpty()) {
            Text(
                text = "Custom Themes",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )

            ThemeChipRow(
                options = customThemes,
                selected = selectedTheme,
                onSelect = onThemeChange,
            ) { it.displayName }
        }

        // Description of selected theme
        Text(
            text = selectedTheme.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Medium,
        )

        // Color preview
        ThemeColorPreview(theme = selectedTheme, isDark = themeMode.resolveDark(isSystemInDarkTheme()))
    }
}

@Composable
fun ThemeColorPreview(theme: AppTheme, isDark: Boolean) {
    val (primary, secondary, tertiary) =
        remember(theme, isDark) {
            theme.getPreviewColors(isDark)
        }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Preview:",
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
            text = if (isDark) "Dark mode" else "Light mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ThemeChipRow(
    options: Collection<T>,
    selected: T,
    onSelect: (T) -> Unit,
    displayName: (T) -> String,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = { onSelect(option) },
                label = { Text(displayName(option)) },
                leadingIcon =
                    if (selected == option) {
                        {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Selected",
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
