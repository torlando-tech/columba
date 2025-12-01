package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.data.repository.CustomThemeData
import com.lxmf.messenger.viewmodel.ThemeManagementViewModel

/**
 * Screen for managing custom themes.
 * Displays a list of all custom themes with options to create, edit, and delete.
 *
 * @param onBackClick Callback when user navigates back
 * @param onCreateTheme Callback to navigate to theme creation screen
 * @param onEditTheme Callback to navigate to theme editing screen with theme ID
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeManagementScreen(
    onBackClick: () -> Unit = {},
    onCreateTheme: () -> Unit = {},
    onEditTheme: (Long) -> Unit = {},
    onApplyTheme: (Long) -> Unit = {},
    viewModel: ThemeManagementViewModel = hiltViewModel(),
) {
    val themes by viewModel.themes.collectAsState(initial = emptyList())
    var showDeleteDialog by remember { mutableStateOf<CustomThemeData?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Custom Themes") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = onCreateTheme,
                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = "Create Theme",
                )
            }
        },
    ) { paddingValues ->
        if (themes.isEmpty()) {
            // Empty state
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "No custom themes yet",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Create your first theme",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            // Theme list
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(themes, key = { it.id }) { theme ->
                    ThemeCard(
                        theme = theme,
                        onEdit = { onEditTheme(theme.id) },
                        onDelete = { showDeleteDialog = theme },
                        onApply = { onApplyTheme(theme.id) },
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteDialog?.let { theme ->
        AlertDialog(
            onDismissRequest = { showDeleteDialog = null },
            title = { Text("Delete Theme?") },
            text = {
                Text("Are you sure you want to delete \"${theme.name}\"? This action cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteTheme(theme.id)
                        showDeleteDialog = null
                    },
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

/**
 * Card displaying a custom theme with preview and actions
 */
@Composable
private fun ThemeCard(
    theme: CustomThemeData,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onApply: () -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable { onEdit() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Theme info and preview
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Color preview swatches
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Light mode colors
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ColorCircle(Color(theme.lightColors.primary))
                        ColorCircle(Color(theme.lightColors.secondary))
                        ColorCircle(Color(theme.lightColors.tertiary))
                    }
                    // Dark mode colors
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        ColorCircle(Color(theme.darkColors.primary))
                        ColorCircle(Color(theme.darkColors.secondary))
                        ColorCircle(Color(theme.darkColors.tertiary))
                    }
                }

                // Theme name and description
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = theme.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (theme.description.isNotBlank()) {
                        Text(
                            text = theme.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }

            // Actions menu
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                    )
                }

                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("Apply Theme") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onApply()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Edit") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Edit,
                                contentDescription = null,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onEdit()
                        },
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                "Delete",
                                color = MaterialTheme.colorScheme.error,
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                            )
                        },
                        onClick = {
                            showMenu = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

/**
 * Small colored circle for theme preview
 */
@Composable
private fun ColorCircle(color: Color) {
    Box(
        modifier =
            Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(color)
                .border(
                    width = 1.dp,
                    color = MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
                    shape = RoundedCornerShape(12.dp),
                ),
    )
}
