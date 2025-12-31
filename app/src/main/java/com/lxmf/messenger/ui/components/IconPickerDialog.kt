package com.lxmf.messenger.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.R
import com.lxmf.messenger.ui.theme.MaterialDesignIcons

/**
 * Material Design Icons font family for icon picker previews.
 */
private val MdiFont = FontFamily(Font(R.font.materialdesignicons))

/**
 * Dialog for selecting a profile icon with foreground and background colors.
 * Used in identity settings to customize the user's profile appearance.
 *
 * @param currentIconName Current selected icon name (null if no icon selected)
 * @param currentForegroundColor Current foreground color as hex RGB (e.g., "FFFFFF")
 * @param currentBackgroundColor Current background color as hex RGB (e.g., "1E88E5")
 * @param onConfirm Callback when user confirms selection with icon name and colors
 * @param onDismiss Callback when dialog is dismissed
 */
@Composable
fun IconPickerDialog(
    currentIconName: String?,
    currentForegroundColor: String?,
    currentBackgroundColor: String?,
    onConfirm: (iconName: String?, foregroundColor: String?, backgroundColor: String?) -> Unit,
    onDismiss: () -> Unit,
) {
    var selectedIconName by remember { mutableStateOf(currentIconName) }
    var selectedForegroundColor by remember { mutableStateOf(currentForegroundColor ?: "FFFFFF") }
    var selectedBackgroundColor by remember { mutableStateOf(currentBackgroundColor ?: "1E88E5") }
    var searchQuery by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Profile Icon") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Preview section
                IconPreviewSection(
                    iconName = selectedIconName,
                    foregroundColor = selectedForegroundColor,
                    backgroundColor = selectedBackgroundColor,
                )

                HorizontalDivider()

                // Color pickers
                ColorSelectionSection(
                    foregroundColor = selectedForegroundColor,
                    backgroundColor = selectedBackgroundColor,
                    onForegroundColorChange = { selectedForegroundColor = it },
                    onBackgroundColorChange = { selectedBackgroundColor = it },
                )

                HorizontalDivider()

                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    label = { Text("Search icons") },
                    placeholder = { Text("e.g., star, heart, wifi") },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Default.Search,
                            contentDescription = "Search",
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear search",
                                )
                            }
                        }
                    },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth(),
                )

                // Icon grid
                IconCategoryList(
                    searchQuery = searchQuery,
                    selectedIconName = selectedIconName,
                    onIconSelected = { selectedIconName = it },
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onConfirm(selectedIconName, selectedForegroundColor, selectedBackgroundColor)
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            Row {
                // Clear button to remove icon
                if (currentIconName != null) {
                    TextButton(
                        onClick = {
                            onConfirm(null, null, null)
                        },
                    ) {
                        Text("Clear")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                }
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            }
        },
    )
}

/**
 * Preview section showing the selected icon at multiple sizes.
 */
@Composable
private fun IconPreviewSection(
    iconName: String?,
    foregroundColor: String,
    backgroundColor: String,
) {
    val fgColor = parseHexColor(foregroundColor, Color.White)
    val bgColor = parseHexColor(backgroundColor, Color.Gray)

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "Preview",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Small preview (32dp - list avatar size)
            IconPreview(
                iconName = iconName,
                fgColor = fgColor,
                bgColor = bgColor,
                size = 32.dp,
            )

            // Medium preview (48dp - detail view size)
            IconPreview(
                iconName = iconName,
                fgColor = fgColor,
                bgColor = bgColor,
                size = 48.dp,
            )

            // Large preview (72dp - profile header size)
            IconPreview(
                iconName = iconName,
                fgColor = fgColor,
                bgColor = bgColor,
                size = 72.dp,
            )
        }

        if (iconName != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = iconName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Single icon preview circle using MDI font.
 */
@Composable
private fun IconPreview(
    iconName: String?,
    fgColor: Color,
    bgColor: Color,
    size: Dp,
) {
    val density = LocalDensity.current
    val fontSize = with(density) { (size * 0.6f).toSp() }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(bgColor)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline,
                shape = CircleShape,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val codepoint = iconName?.let { MaterialDesignIcons.getCodepointOrNull(it) }
        if (codepoint != null) {
            Text(
                text = codepoint,
                fontFamily = MdiFont,
                fontSize = fontSize,
                color = fgColor,
            )
        } else {
            Text(
                text = "?",
                style = MaterialTheme.typography.titleMedium,
                color = fgColor,
            )
        }
    }
}

/**
 * Color selection section using the full ColorPickerDialog for both colors.
 */
@Composable
private fun ColorSelectionSection(
    foregroundColor: String,
    backgroundColor: String,
    onForegroundColorChange: (String) -> Unit,
    onBackgroundColorChange: (String) -> Unit,
) {
    var showBgColorPicker by remember { mutableStateOf(false) }
    var showFgColorPicker by remember { mutableStateOf(false) }

    val bgColor = parseHexColor(backgroundColor, Color.Gray)
    val fgColor = parseHexColor(foregroundColor, Color.White)

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Background color
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Background",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(bgColor)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { showBgColorPicker = true },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "#$backgroundColor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Icon color
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = "Icon",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(fgColor)
                        .border(
                            width = 2.dp,
                            color = MaterialTheme.colorScheme.outline,
                            shape = CircleShape,
                        )
                        .clickable { showFgColorPicker = true },
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "#$foregroundColor",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            text = "Tap a color to customize",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
    }

    // Background color picker dialog
    if (showBgColorPicker) {
        ColorPickerDialog(
            initialColor = bgColor,
            title = "Background Color",
            onConfirm = { color ->
                val hex = String.format("%06X", color.toArgb() and 0xFFFFFF)
                onBackgroundColorChange(hex)
            },
            onDismiss = { showBgColorPicker = false },
        )
    }

    // Foreground/icon color picker dialog
    if (showFgColorPicker) {
        ColorPickerDialog(
            initialColor = fgColor,
            title = "Icon Color",
            onConfirm = { color ->
                val hex = String.format("%06X", color.toArgb() and 0xFFFFFF)
                onForegroundColorChange(hex)
            },
            onDismiss = { showFgColorPicker = false },
        )
    }
}

/**
 * Single color swatch button.
 */
@Composable
private fun ColorSwatch(
    hexColor: String,
    colorName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val color = parseHexColor(hexColor, Color.Gray)
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)
    }

    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = CircleShape,
            )
            .clickable(
                onClick = onClick,
                onClickLabel = colorName,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isSelected) {
            // Show check mark with contrasting color
            val checkColor = if (isColorDark(color)) Color.White else Color.Black
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Selected: $colorName",
                tint = checkColor,
                modifier = Modifier.size(14.dp),
            )
        }
    }
}

/**
 * Expandable icon category list with lazy loading.
 * When searching, searches ALL 7000+ icons in the MDI library.
 * When not searching, shows curated categories for quick browsing.
 */
@Composable
private fun IconCategoryList(
    searchQuery: String,
    selectedIconName: String?,
    onIconSelected: (String) -> Unit,
) {
    val categories = MaterialDesignIcons.iconsByCategory
    val expandedCategories = remember { mutableStateMapOf<String, Boolean>() }

    // When searching, search ALL icons in the library (7000+)
    // When not searching, show curated categories
    val isSearching = searchQuery.isNotBlank()
    val searchResults by remember(searchQuery) {
        derivedStateOf {
            if (isSearching) {
                val query = searchQuery.lowercase()
                MaterialDesignIcons.getAllIconNames()
                    .filter { it.lowercase().contains(query) }
                    .take(100) // Limit results for performance
            } else {
                emptyList()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = 300.dp),
        contentPadding = PaddingValues(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (isSearching) {
            // Show search results from ALL icons
            if (searchResults.isNotEmpty()) {
                item(key = "search_header") {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = "Search Results",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = "${searchResults.size} icons" +
                                    if (searchResults.size >= 100) " (showing first 100)" else "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
                item(key = "search_results") {
                    IconGrid(
                        icons = searchResults,
                        selectedIconName = selectedIconName,
                        onIconSelected = onIconSelected,
                    )
                }
            } else {
                item {
                    Text(
                        text = "No icons found for \"$searchQuery\"",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        } else {
            // Show curated categories when not searching
            categories.forEach { (category, icons) ->
                item(key = "header_$category") {
                    CategoryHeader(
                        category = category,
                        iconCount = icons.size,
                        isExpanded = expandedCategories[category] ?: false,
                        onToggle = {
                            expandedCategories[category] = !(expandedCategories[category] ?: false)
                        },
                    )
                }

                val isExpanded = expandedCategories[category] ?: false
                if (isExpanded) {
                    item(key = "icons_$category") {
                        IconGrid(
                            icons = icons,
                            selectedIconName = selectedIconName,
                            onIconSelected = onIconSelected,
                        )
                    }
                }
            }

            // Hint about search
            item(key = "search_hint") {
                Text(
                    text = "Search to find any of ${MaterialDesignIcons.iconCount} icons",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * Category header with expand/collapse toggle.
 */
@Composable
private fun CategoryHeader(
    category: String,
    iconCount: Int,
    isExpanded: Boolean,
    onToggle: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle),
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = category,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = "$iconCount icons",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Grid of icons within a category.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IconGrid(
    icons: List<String>,
    selectedIconName: String?,
    onIconSelected: (String) -> Unit,
) {
    FlowRow(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        icons.forEach { iconName ->
            IconGridItem(
                iconName = iconName,
                isSelected = iconName == selectedIconName,
                onClick = { onIconSelected(iconName) },
            )
        }
    }
}

/**
 * Single selectable icon item in the grid using MDI font.
 */
@Composable
private fun IconGridItem(
    iconName: String,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val backgroundColor = if (isSelected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val borderColor = if (isSelected) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)
    }
    val iconTint = if (isSelected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val codepoint = MaterialDesignIcons.getCodepointOrNull(iconName)

    Surface(
        modifier = Modifier
            .size(44.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(
                width = if (isSelected) 2.dp else 1.dp,
                color = borderColor,
                shape = RoundedCornerShape(8.dp),
            )
            .clickable(onClick = onClick),
        color = backgroundColor,
    ) {
        Box(
            contentAlignment = Alignment.Center,
        ) {
            if (codepoint != null) {
                Text(
                    text = codepoint,
                    fontFamily = MdiFont,
                    fontSize = with(LocalDensity.current) { 24.dp.toSp() },
                    color = iconTint,
                )
            } else {
                Text(
                    text = "?",
                    color = iconTint,
                )
            }
        }
    }
}

/**
 * Parse a hex color string to Compose Color.
 */
@Suppress("SwallowedException")
private fun parseHexColor(hex: String, default: Color): Color {
    return try {
        val cleanHex = hex.removePrefix("#")
        Color(android.graphics.Color.parseColor("#$cleanHex"))
    } catch (_: IllegalArgumentException) {
        default
    }
}

/**
 * Check if a color is dark (for choosing contrasting text color).
 */
private fun isColorDark(color: Color): Boolean {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance < 0.5
}

