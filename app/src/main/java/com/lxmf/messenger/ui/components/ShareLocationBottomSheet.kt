package com.lxmf.messenger.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.ui.model.SharingDuration

/**
 * Bottom sheet for configuring location sharing with contacts.
 *
 * Features:
 * - Search field for filtering contacts
 * - Selected contacts shown as InputChips (removable)
 * - Scrollable contact list with checkboxes
 * - Duration selection via FilterChips
 * - "Start Sharing" button (disabled until contacts selected)
 *
 * @param contacts List of available contacts to share with
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param onStartSharing Callback when sharing is initiated with selected contacts and duration
 * @param sheetState The state of the bottom sheet
 * @param initialSelectedHashes Set of destination hashes to pre-select when opening the sheet
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ShareLocationBottomSheet(
    contacts: List<EnrichedContact>,
    onDismiss: () -> Unit,
    onStartSharing: (selectedContacts: List<EnrichedContact>, duration: SharingDuration) -> Unit,
    sheetState: SheetState,
    initialSelectedHashes: Set<String> = emptySet(),
) {
    var searchQuery by remember { mutableStateOf("") }
    var selectedContactHashes by remember { mutableStateOf(initialSelectedHashes) }
    var selectedDuration by remember { mutableStateOf(SharingDuration.ONE_HOUR) }

    val filteredContacts = remember(contacts, searchQuery) {
        val uniqueContacts = contacts.distinctBy { it.destinationHash }
        val filtered = if (searchQuery.isBlank()) {
            uniqueContacts
        } else {
            uniqueContacts.filter { contact ->
                contact.displayName.contains(searchQuery, ignoreCase = true)
            }
        }
        // Sort by recency: lastMessageTimestamp (desc), fallback to addedTimestamp (desc)
        filtered.sortedWith(
            compareByDescending<EnrichedContact> { it.lastMessageTimestamp ?: 0L }
                .thenByDescending { it.addedTimestamp }
        )
    }

    val selectedContacts = remember(contacts, selectedContactHashes) {
        contacts
            .distinctBy { it.destinationHash }
            .filter { it.destinationHash in selectedContactHashes }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
        ) {
            // Title
            Text(
                text = "Share your location",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search contacts") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Selected contacts as InputChips
            if (selectedContacts.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    selectedContacts.forEach { contact ->
                        InputChip(
                            selected = true,
                            onClick = {
                                selectedContactHashes = selectedContactHashes - contact.destinationHash
                            },
                            label = { Text(contact.displayName) },
                            trailingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Remove ${contact.displayName}",
                                    modifier = Modifier.height(InputChipDefaults.IconSize),
                                )
                            },
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Contact list
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 200.dp),
            ) {
                items(
                    items = filteredContacts,
                    key = { it.destinationHash },
                ) { contact ->
                    ContactSelectionRow(
                        displayName = contact.displayName,
                        destinationHash = contact.destinationHash,
                        isSelected = contact.destinationHash in selectedContactHashes,
                        onSelectionChanged = { selected ->
                            selectedContactHashes = if (selected) {
                                selectedContactHashes + contact.destinationHash
                            } else {
                                selectedContactHashes - contact.destinationHash
                            }
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(16.dp))

            // Duration label
            Text(
                text = "Duration:",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Duration selection chips
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                SharingDuration.entries.forEach { duration ->
                    FilterChip(
                        selected = selectedDuration == duration,
                        onClick = { selectedDuration = duration },
                        label = { Text(duration.displayText) },
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Start Sharing button
            Button(
                onClick = {
                    onStartSharing(selectedContacts, selectedDuration)
                    onDismiss()
                },
                enabled = selectedContacts.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Start Sharing")
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}
