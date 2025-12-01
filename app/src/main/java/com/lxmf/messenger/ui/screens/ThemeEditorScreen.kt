package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.ColorPickerDialog
import com.lxmf.messenger.util.ThemeColorGenerator
import com.lxmf.messenger.viewmodel.ColorRole
import com.lxmf.messenger.viewmodel.ThemeEditorViewModel

/**
 * Screen for creating and editing custom themes.
 * Supports seed color mode (generate harmonized palette from one color)
 * and advanced mode (manually edit individual color roles).
 *
 * @param themeId Optional ID for editing existing theme (null for new theme)
 * @param onBackClick Callback when user cancels or navigates back
 * @param onSave Callback when theme is saved successfully
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeEditorScreen(
    themeId: Long? = null,
    onBackClick: () -> Unit = {},
    onSave: () -> Unit = {},
    viewModel: ThemeEditorViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showColorPicker by remember { mutableStateOf(false) }
    val isDarkMode = isSystemInDarkTheme()
    var previewMode by remember { mutableIntStateOf(if (isDarkMode) 1 else 0) } // 0 = Light, 1 = Dark

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(if (themeId == null) "Create Theme" else "Edit Theme")
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    // Cancel button
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel",
                        )
                    }
                    // Save button
                    IconButton(
                        onClick = {
                            viewModel.saveTheme()
                            onSave()
                        },
                        enabled = state.themeName.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Save",
                        )
                    }
                    // Save & Apply button
                    IconButton(
                        onClick = {
                            viewModel.saveAndApplyTheme()
                            onSave()
                        },
                        enabled = state.themeName.isNotBlank(),
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "Save & Apply",
                            tint = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Theme Name Input
            OutlinedTextField(
                value = state.themeName,
                onValueChange = { viewModel.updateThemeName(it) },
                label = { Text("Theme Name") },
                placeholder = { Text("My Custom Theme") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            // Theme Description Input
            OutlinedTextField(
                value = state.themeDescription,
                onValueChange = { viewModel.updateThemeDescription(it) },
                label = { Text("Description (Optional)") },
                placeholder = { Text("Describe your theme...") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )

            // Color Selection Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Theme Colors",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = if (state.useHarmonizedColors) "Auto-generating harmonized colors from primary" else "Customize each color individually",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Harmonized mode toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "Auto-harmonize colors",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Switch(
                            checked = state.useHarmonizedColors,
                            onCheckedChange = { viewModel.toggleHarmonizedMode() },
                        )
                    }

                    // Large preview showing currently selected color
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Box(
                            modifier =
                                Modifier
                                    .size(80.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(viewModel.getSelectedColor())
                                    .border(
                                        width = 2.dp,
                                        color = MaterialTheme.colorScheme.outline,
                                        shape = RoundedCornerShape(12.dp),
                                    )
                                    .clickable { showColorPicker = true },
                        )

                        Column(
                            horizontalAlignment = Alignment.End,
                        ) {
                            val selectedRoleName =
                                when (state.selectedColorRole) {
                                    ColorRole.PRIMARY -> "Primary"
                                    ColorRole.SECONDARY -> "Secondary"
                                    ColorRole.TERTIARY -> "Tertiary"
                                }
                            Text(
                                text = selectedRoleName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text =
                                    ThemeColorGenerator.argbToHex(
                                        viewModel.getSelectedColor().toArgb(),
                                        includeAlpha = false,
                                    ),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                            )
                            TextButton(onClick = { showColorPicker = true }) {
                                Text("Change Color")
                            }
                        }
                    }

                    // Show color palette (clickable swatches)
                    Text(
                        text = "Color Palette",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ColorSwatch(
                            label = "Primary",
                            color = state.primarySeedColor,
                            isSelected = state.selectedColorRole == ColorRole.PRIMARY,
                            onClick = {
                                viewModel.selectColorRole(ColorRole.PRIMARY)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ColorSwatch(
                            label = "Secondary",
                            color = state.secondarySeedColor,
                            isSelected = state.selectedColorRole == ColorRole.SECONDARY,
                            onClick = {
                                viewModel.selectColorRole(ColorRole.SECONDARY)
                            },
                            modifier = Modifier.weight(1f),
                        )
                        ColorSwatch(
                            label = "Tertiary",
                            color = state.tertiarySeedColor,
                            isSelected = state.selectedColorRole == ColorRole.TERTIARY,
                            onClick = {
                                viewModel.selectColorRole(ColorRole.TERTIARY)
                            },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }

            // Preview Section
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = "Theme Preview",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )

                    // Light/Dark mode tabs
                    TabRow(
                        selectedTabIndex = previewMode,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Tab(
                            selected = previewMode == 0,
                            onClick = { previewMode = 0 },
                            text = { Text("Light Mode") },
                        )
                        Tab(
                            selected = previewMode == 1,
                            onClick = { previewMode = 1 },
                            text = { Text("Dark Mode") },
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Preview UI components with generated theme
                    ThemePreview(
                        primarySeedColor = state.primarySeedColor,
                        secondarySeedColor = state.secondarySeedColor,
                        tertiarySeedColor = state.tertiarySeedColor,
                        isDark = previewMode == 1,
                    )
                }
            }
        }
    }

    // Color Picker Dialog
    if (showColorPicker) {
        val currentColorRoleName =
            when (state.selectedColorRole) {
                ColorRole.PRIMARY -> "Primary"
                ColorRole.SECONDARY -> "Secondary"
                ColorRole.TERTIARY -> "Tertiary"
            }

        ColorPickerDialog(
            initialColor = viewModel.getSelectedColor(),
            title = "Pick $currentColorRoleName Color",
            onConfirm = { color ->
                viewModel.updateSelectedColor(color)
            },
            onDismiss = { showColorPicker = false },
        )
    }
}

/**
 * Small color swatch showing a color with label
 * Clickable with visual selection state
 */
@Composable
private fun ColorSwatch(
    label: String,
    color: Color,
    isSelected: Boolean = false,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(color)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(8.dp),
                    )
                    .clickable(onClick = onClick),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Preview showing how the theme looks on actual UI components
 */
@Composable
private fun ThemePreview(
    primarySeedColor: Color,
    secondarySeedColor: Color,
    tertiarySeedColor: Color,
    isDark: Boolean,
) {
    val colorScheme =
        ThemeColorGenerator.generateColorScheme(
            primarySeed = primarySeedColor.toArgb(),
            secondarySeed = secondarySeedColor.toArgb(),
            tertiarySeed = tertiarySeedColor.toArgb(),
            isDark = isDark,
        )

    // Apply the generated color scheme to a preview container
    MaterialTheme(colorScheme = colorScheme) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.background)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outline,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top App Bar Preview (most prominent color in app)
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = "Top App Bar",
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 16.dp),
                )
            }

            // Surface with primary color
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Primary",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.titleMedium,
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Secondary
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.secondary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Secondary",
                        color = MaterialTheme.colorScheme.onSecondary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                // Tertiary
                Box(
                    modifier =
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.tertiary),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Tertiary",
                        color = MaterialTheme.colorScheme.onTertiary,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            // Surface and surface variant
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Surface",
                    color = MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Box(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .height(32.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "Surface Variant",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}
