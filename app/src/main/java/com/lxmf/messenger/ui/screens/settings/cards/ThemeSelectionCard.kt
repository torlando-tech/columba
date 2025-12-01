package com.lxmf.messenger.ui.screens.settings.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.lxmf.messenger.ui.theme.AppTheme
import com.lxmf.messenger.ui.theme.PresetTheme

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThemeSelectionCard(
    selectedTheme: AppTheme,
    customThemes: List<AppTheme>,
    onThemeChange: (AppTheme) -> Unit,
    onNavigateToCustomThemes: () -> Unit = {},
) {
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
            // Header with custom themes button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = "Theme",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Text(
                        text = "Theme",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }

                TextButton(onClick = onNavigateToCustomThemes) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Custom Themes")
                }
            }

            // Description
            Text(
                text = "Choose your preferred color theme. Dark and light modes adapt automatically based on your system settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Built-in themes section
            Text(
                text = "Built-in Themes",
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
                        label = { Text(theme.displayName) },
                        leadingIcon =
                            if (selectedTheme == theme) {
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

            // Custom themes section (only show if there are custom themes)
            if (customThemes.isNotEmpty()) {
                Text(
                    text = "Custom Themes",
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

            // Description of selected theme
            Text(
                text = selectedTheme.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium,
            )

            // Color preview
            ThemeColorPreview(theme = selectedTheme)
        }
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
            text = if (isDarkTheme) "Dark mode" else "Light mode",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
