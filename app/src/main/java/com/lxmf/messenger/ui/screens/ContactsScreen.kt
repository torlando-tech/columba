package com.lxmf.messenger.ui.screens

import android.util.Log
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.ui.components.AddContactConfirmationDialog
import com.lxmf.messenger.ui.components.ProfileIcon
import com.lxmf.messenger.ui.components.simpleVerticalScrollbar
import com.lxmf.messenger.ui.theme.MeshConnected
import com.lxmf.messenger.util.formatRelativeTime
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationConstants
import com.lxmf.messenger.util.validation.ValidationResult
import com.lxmf.messenger.viewmodel.AddContactResult
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel
import com.lxmf.messenger.viewmodel.ContactsViewModel
import com.lxmf.messenger.viewmodel.SharedTextViewModel
import kotlinx.coroutines.launch

private const val TAG = "ContactsScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onContactClick: (destinationHash: String, displayName: String) -> Unit = { _, _ -> },
    onViewPeerDetails: (destinationHash: String) -> Unit = { },
    onNavigateToQrScanner: () -> Unit = {},
    pendingDeepLinkContact: String? = null,
    onDeepLinkContactProcessed: () -> Unit = {},
    onNavigateToConversation: (destinationHash: String) -> Unit = {},
    viewModel: ContactsViewModel = hiltViewModel(),
    announceViewModel: AnnounceStreamViewModel = hiltViewModel(),
    onStartChat: (destinationHash: String, peerName: String) -> Unit = { _, _ -> },
) {
    val context = LocalContext.current
    val sharedTextViewModel: SharedTextViewModel = viewModel(viewModelStoreOwner = context as androidx.activity.ComponentActivity)
    val sharedTextFromViewModel by sharedTextViewModel.sharedText.collectAsState()
    val effectivePendingSharedText = sharedTextFromViewModel?.text

    val contactsState by viewModel.contactsState.collectAsState()
    val contactCount by viewModel.contactCount.collectAsState()
    val searchQuery by viewModel.searchQuery.collectAsState()
    val currentRelayInfo by viewModel.currentRelayInfo.collectAsState()
    var isSearching by remember { mutableStateOf(false) }
    val contactsListState = rememberLazyListState()

    // Tab selection state - use rememberSaveable to preserve across navigation
    var selectedTab by androidx.compose.runtime.saveable
        .rememberSaveable { mutableStateOf(ContactsTab.MY_CONTACTS) }

    // Network tab state
    val selectedNodeTypes by announceViewModel.selectedNodeTypes.collectAsState()
    val showAudioAnnounces by announceViewModel.showAudioAnnounces.collectAsState()
    val selectedInterfaceTypes by announceViewModel.selectedInterfaceTypes.collectAsState()
    val announceSearchQuery by announceViewModel.searchQuery.collectAsState()
    val announceCount by announceViewModel.announceCount.collectAsState()
    val isAnnouncing by announceViewModel.isAnnouncing.collectAsState()
    val announceSuccess by announceViewModel.announceSuccess.collectAsState()
    val announceError by announceViewModel.announceError.collectAsState()
    var showNodeTypeFilterDialog by remember { mutableStateOf(false) }
    var showNetworkOverflowMenu by remember { mutableStateOf(false) }
    var showClearAllAnnouncesDialog by remember { mutableStateOf(false) }

    // Show toast for announce success/error
    LaunchedEffect(announceSuccess) {
        if (announceSuccess) {
            Toast.makeText(context, "Announce sent!", Toast.LENGTH_SHORT).show()
        }
    }
    LaunchedEffect(announceError) {
        announceError?.let { error ->
            Toast.makeText(context, "Error: $error", Toast.LENGTH_LONG).show()
        }
    }

    var showAddContactSheet by remember { mutableStateOf(false) }
    var showManualEntryDialog by remember { mutableStateOf(false) }

    // Deep link confirmation dialog state
    var showDeepLinkConfirmation by remember { mutableStateOf(false) }
    var deepLinkDestinationHash by remember { mutableStateOf<String?>(null) }
    var deepLinkPublicKey by remember { mutableStateOf<ByteArray?>(null) }

    // Contact already exists dialog state
    var showContactExistsDialog by remember { mutableStateOf(false) }
    var existingContactName by remember { mutableStateOf<String?>(null) }

    // Edit nickname dialog state
    var showEditNicknameDialog by remember { mutableStateOf(false) }
    var editNicknameContactHash by remember { mutableStateOf<String?>(null) }
    var editNicknameCurrentValue by remember { mutableStateOf<String?>(null) }

    // Pending contact bottom sheet state
    var showPendingContactSheet by remember { mutableStateOf(false) }
    var pendingContactToShow by remember { mutableStateOf<EnrichedContact?>(null) }

    // Unset relay confirmation dialog state
    var showUnsetRelayDialog by remember { mutableStateOf(false) }
    var relayToUnset by remember { mutableStateOf<EnrichedContact?>(null) }

    val scope = rememberCoroutineScope()

    // Handle pending deep link contact
    LaunchedEffect(pendingDeepLinkContact) {
        pendingDeepLinkContact?.let { lxmaUrl ->
            Log.d(TAG, "Processing pending deep link contact: $lxmaUrl")
            // Decode the QR code
            val decodedData = viewModel.decodeQrCode(lxmaUrl)
            if (decodedData != null) {
                val (hashHex, publicKey) = decodedData
                // Check if contact already exists
                val existingContact = viewModel.checkContactExists(hashHex)
                if (existingContact != null) {
                    // Contact exists - show info dialog
                    Log.d(TAG, "Deep link contact already exists: $hashHex")
                    existingContactName = existingContact.displayName
                    showContactExistsDialog = true
                    onDeepLinkContactProcessed()
                } else {
                    // New contact - show confirmation dialog
                    Log.d(TAG, "New deep link contact detected, showing confirmation dialog: $hashHex")
                    deepLinkDestinationHash = hashHex
                    deepLinkPublicKey = publicKey
                    showDeepLinkConfirmation = true
                }
            } else {
                Log.e(TAG, "Failed to decode deep link contact: $lxmaUrl")
                onDeepLinkContactProcessed()
            }
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "Contacts",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                    },
                    actions = {
                        // Search button
                        IconButton(onClick = { isSearching = !isSearching }) {
                            Icon(
                                imageVector = if (isSearching) Icons.Default.Close else Icons.Default.Search,
                                contentDescription = if (isSearching) "Close search" else "Search",
                            )
                        }
                        // My Contacts tab actions
                        if (selectedTab == ContactsTab.MY_CONTACTS) {
                            IconButton(onClick = { showAddContactSheet = true }) {
                                Icon(
                                    imageVector = Icons.Default.Add,
                                    contentDescription = "Add contact",
                                )
                            }
                        }
                        // Network tab actions
                        if (selectedTab == ContactsTab.NETWORK) {
                            // Announce button
                            IconButton(
                                onClick = { announceViewModel.triggerAnnounce() },
                                enabled = !isAnnouncing,
                            ) {
                                if (isAnnouncing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp,
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.Campaign,
                                        contentDescription = "Announce now",
                                    )
                                }
                            }
                            // Filter button
                            IconButton(onClick = { showNodeTypeFilterDialog = true }) {
                                Icon(
                                    imageVector = Icons.Default.FilterList,
                                    contentDescription = "Filter node types",
                                )
                            }
                            // Overflow menu
                            Box {
                                IconButton(onClick = { showNetworkOverflowMenu = true }) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More options",
                                    )
                                }
                                DropdownMenu(
                                    expanded = showNetworkOverflowMenu,
                                    onDismissRequest = { showNetworkOverflowMenu = false },
                                ) {
                                    DropdownMenuItem(
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.DeleteSweep,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        text = {
                                            Text(
                                                text = "Clear All Announces",
                                                color = MaterialTheme.colorScheme.error,
                                            )
                                        },
                                        onClick = {
                                            showNetworkOverflowMenu = false
                                            showClearAllAnnouncesDialog = true
                                        },
                                    )
                                }
                            }
                        }
                    },
                    colors =
                        TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        ),
                )

                // Search bar
                AnimatedVisibility(visible = isSearching) {
                    val currentSearchQuery =
                        when (selectedTab) {
                            ContactsTab.MY_CONTACTS -> searchQuery
                            ContactsTab.NETWORK -> announceSearchQuery
                        }
                    val currentPlaceholder =
                        when (selectedTab) {
                            ContactsTab.MY_CONTACTS -> "Search by name, hash, or tag..."
                            ContactsTab.NETWORK -> "Search by name or hash..."
                        }
                    OutlinedTextField(
                        value = currentSearchQuery,
                        onValueChange = { query ->
                            // VALIDATION: Sanitize and limit search query
                            val sanitized =
                                InputValidator.sanitizeText(
                                    query,
                                    ValidationConstants.MAX_SEARCH_QUERY_LENGTH,
                                )
                            when (selectedTab) {
                                ContactsTab.MY_CONTACTS -> viewModel.onSearchQueryChanged(sanitized)
                                ContactsTab.NETWORK -> announceViewModel.searchQuery.value = sanitized
                            }
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                        placeholder = { Text(currentPlaceholder) },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null)
                        },
                        trailingIcon = {
                            if (currentSearchQuery.isNotEmpty()) {
                                IconButton(
                                    onClick = {
                                        when (selectedTab) {
                                            ContactsTab.MY_CONTACTS -> viewModel.onSearchQueryChanged("")
                                            ContactsTab.NETWORK -> announceViewModel.searchQuery.value = ""
                                        }
                                    },
                                ) {
                                    Icon(Icons.Default.Clear, contentDescription = "Clear search")
                                }
                            }
                        },
                        singleLine = true,
                        colors =
                            OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = MaterialTheme.colorScheme.surface,
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                    )
                }

                // Tab selector
                SingleChoiceSegmentedButtonRow(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    ContactsTab.entries.forEachIndexed { index, tab ->
                        SegmentedButton(
                            shape = SegmentedButtonDefaults.itemShape(index = index, count = ContactsTab.entries.size),
                            onClick = { selectedTab = tab },
                            selected = selectedTab == tab,
                        ) {
                            val label =
                                when (tab) {
                                    ContactsTab.MY_CONTACTS -> "My Contacts ($contactCount)"
                                    ContactsTab.NETWORK -> "Network ($announceCount)"
                                }
                            Text(label)
                        }
                    }
                }
            }
        },
        floatingActionButton = {
            // Only show FAB on My Contacts tab
            if (selectedTab == ContactsTab.MY_CONTACTS) {
                FloatingActionButton(
                    onClick = { showAddContactSheet = true },
                    containerColor = MaterialTheme.colorScheme.primary,
                ) {
                    Icon(Icons.Default.Add, contentDescription = "Add contact")
                }
            }
        },
    ) { paddingValues ->
        when (selectedTab) {
            ContactsTab.MY_CONTACTS -> {
                // My Contacts tab content
                // Only show loading spinner when loading AND list is empty
                // This prevents flickering when data updates while content is displayed
                val hasContacts =
                    contactsState.groupedContacts.relay != null ||
                        contactsState.groupedContacts.pinned.isNotEmpty() ||
                        contactsState.groupedContacts.all.isNotEmpty()
                when {
                    contactsState.isLoading && !hasContacts -> {
                        LoadingContactsState(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues),
                        )
                    }
                    !contactsState.isLoading && !hasContacts -> {
                        EmptyContactsState(
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues),
                        )
                    }
                    else -> {
                        LazyColumn(
                            state = contactsListState,
                            modifier =
                                Modifier
                                    .fillMaxSize()
                                    .padding(paddingValues)
                                    .consumeWindowInsets(paddingValues)
                                    .simpleVerticalScrollbar(contactsListState),
                            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 88.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            // My Relay section (shown at top, separate from pinned)
                            contactsState.groupedContacts.relay?.let { relay ->
                                item {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                    ) {
                                        Icon(
                                            imageVector = Icons.Filled.Hub,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp),
                                            tint = MaterialTheme.colorScheme.tertiary,
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "MY RELAY",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.tertiary,
                                        )
                                        // Show "(auto)" badge if relay was auto-selected
                                        if (currentRelayInfo?.isAutoSelected == true) {
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text(
                                                text = "(auto)",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                                item(key = "relay_${relay.destinationHash}") {
                                    ContactListItemWithMenu(
                                        contact = relay,
                                        onClick = {
                                            if (relay.status == ContactStatus.PENDING_IDENTITY ||
                                                relay.status == ContactStatus.UNRESOLVED
                                            ) {
                                                pendingContactToShow = relay
                                                showPendingContactSheet = true
                                            } else {
                                                onContactClick(relay.destinationHash, relay.displayName)
                                            }
                                        },
                                        onPinToggle = { viewModel.togglePin(relay.destinationHash) },
                                        onEditNickname = {
                                            editNicknameContactHash = relay.destinationHash
                                            editNicknameCurrentValue = relay.customNickname
                                            showEditNicknameDialog = true
                                        },
                                        onViewDetails = { onViewPeerDetails(relay.destinationHash) },
                                        onRemove = {
                                            relayToUnset = relay
                                            showUnsetRelayDialog = true
                                        },
                                    )
                                }
                            }

                            // Pinned contacts section
                            if (contactsState.groupedContacts.pinned.isNotEmpty()) {
                                item {
                                    Text(
                                        text = "PINNED",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp),
                                    )
                                }
                                items(
                                    contactsState.groupedContacts.pinned,
                                    key = { contact -> "pinned_${contact.destinationHash}" },
                                ) { contact ->
                                    ContactListItemWithMenu(
                                        contact = contact,
                                        onClick = {
                                            if (contact.status == ContactStatus.PENDING_IDENTITY ||
                                                contact.status == ContactStatus.UNRESOLVED
                                            ) {
                                                pendingContactToShow = contact
                                                showPendingContactSheet = true
                                            } else {
                                                if (!effectivePendingSharedText.isNullOrBlank()) {
                                                    sharedTextViewModel.assignToDestination(contact.destinationHash)
                                                    onStartChat(contact.destinationHash, contact.displayName)
                                                } else {
                                                    onContactClick(contact.destinationHash, contact.displayName)
                                                }
                                            }
                                        },
                                        onPinToggle = { viewModel.togglePin(contact.destinationHash) },
                                        onEditNickname = {
                                            editNicknameContactHash = contact.destinationHash
                                            editNicknameCurrentValue = contact.customNickname
                                            showEditNicknameDialog = true
                                        },
                                        onViewDetails = { onViewPeerDetails(contact.destinationHash) },
                                        onRemove = { viewModel.deleteContact(contact.destinationHash) },
                                    )
                                }
                            }

                            // All contacts section
                            if (contactsState.groupedContacts.all.isNotEmpty()) {
                                if (contactsState.groupedContacts.relay != null || contactsState.groupedContacts.pinned.isNotEmpty()) {
                                    item {
                                        Text(
                                            text = "ALL CONTACTS",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                                        )
                                    }
                                }
                                items(
                                    contactsState.groupedContacts.all,
                                    key = { contact -> "all_${contact.destinationHash}" },
                                ) { contact ->
                                    ContactListItemWithMenu(
                                        contact = contact,
                                        onClick = {
                                            if (contact.status == ContactStatus.PENDING_IDENTITY ||
                                                contact.status == ContactStatus.UNRESOLVED
                                            ) {
                                                pendingContactToShow = contact
                                                showPendingContactSheet = true
                                            } else {
                                                if (!effectivePendingSharedText.isNullOrBlank()) {
                                                    sharedTextViewModel.assignToDestination(contact.destinationHash)
                                                    onStartChat(contact.destinationHash, contact.displayName)
                                                } else {
                                                    onContactClick(contact.destinationHash, contact.displayName)
                                                }
                                            }
                                        },
                                        onPinToggle = { viewModel.togglePin(contact.destinationHash) },
                                        onEditNickname = {
                                            editNicknameContactHash = contact.destinationHash
                                            editNicknameCurrentValue = contact.customNickname
                                            showEditNicknameDialog = true
                                        },
                                        onViewDetails = { onViewPeerDetails(contact.destinationHash) },
                                        onRemove = { viewModel.deleteContact(contact.destinationHash) },
                                    )
                                }
                            }
                        }
                    }
                }
            }

            ContactsTab.NETWORK -> {
                // Network tab - show announces/discovered nodes
                AnnounceStreamContent(
                    onPeerClick = { destinationHash, _ -> onViewPeerDetails(destinationHash) },
                    onStartChat = { destinationHash, displayName ->
                        if (!effectivePendingSharedText.isNullOrBlank()) {
                            sharedTextViewModel.assignToDestination(destinationHash)
                        }
                        onStartChat(destinationHash, displayName)
                    },
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                            .consumeWindowInsets(paddingValues),
                )
            }
        }
    }

    // Add contact bottom sheet
    if (showAddContactSheet) {
        AddContactBottomSheet(
            onDismiss = { showAddContactSheet = false },
            onScanQrCode = {
                showAddContactSheet = false
                onNavigateToQrScanner()
            },
            onManualEntry = {
                showAddContactSheet = false
                showManualEntryDialog = true
            },
        )
    }

    // Manual entry dialog - state for pending identity snackbar
    var showPendingIdentityMessage by remember { mutableStateOf(false) }

    // Manual entry dialog
    if (showManualEntryDialog) {
        ManualEntryDialog(
            onDismiss = { showManualEntryDialog = false },
            onConfirm = { identityString, nickname ->
                scope.launch {
                    when (val result = viewModel.addContactFromInput(identityString, nickname)) {
                        is AddContactResult.Success -> {
                            Log.d(TAG, "Contact added successfully")
                            showManualEntryDialog = false
                        }
                        is AddContactResult.PendingIdentity -> {
                            Log.d(TAG, "Contact added with pending identity")
                            showManualEntryDialog = false
                            showPendingIdentityMessage = true
                        }
                        is AddContactResult.AlreadyExists -> {
                            existingContactName = result.existingContact.displayName
                            showContactExistsDialog = true
                            showManualEntryDialog = false
                        }
                        is AddContactResult.Error -> {
                            Log.e(TAG, "Error adding contact: ${result.message}")
                            // Error is handled inside the dialog
                        }
                    }
                }
            },
        )
    }

    // Deep link confirmation dialog
    val deepLinkHash = deepLinkDestinationHash
    val deepLinkKey = deepLinkPublicKey
    if (showDeepLinkConfirmation && deepLinkHash != null) {
        AddContactConfirmationDialog(
            destinationHash = deepLinkHash,
            onDismiss = {
                showDeepLinkConfirmation = false
                deepLinkDestinationHash = null
                deepLinkPublicKey = null
                onDeepLinkContactProcessed()
            },
            onConfirm = { nickname ->
                // Add the contact
                val lxmaUrl = "lxma://$deepLinkHash:${deepLinkKey?.joinToString("") { "%02x".format(it) }}"
                viewModel.addContactFromQrCode(lxmaUrl, nickname)
                showDeepLinkConfirmation = false
                deepLinkDestinationHash = null
                deepLinkPublicKey = null
                onDeepLinkContactProcessed()
            },
        )
    }

    // Contact already exists dialog
    if (showContactExistsDialog) {
        AlertDialog(
            onDismissRequest = {
                showContactExistsDialog = false
                existingContactName = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Default.CheckCircle,
                    contentDescription = "Contact Exists",
                    tint = MaterialTheme.colorScheme.primary,
                )
            },
            title = {
                Text(
                    text = "Contact Already Added",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            text = {
                Text(
                    text =
                        if (existingContactName != null) {
                            "This contact is already in your contacts list as \"$existingContactName\"."
                        } else {
                            "This contact is already in your contacts list."
                        },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showContactExistsDialog = false
                        existingContactName = null
                    },
                ) {
                    Text("OK")
                }
            },
        )
    }

    // Unset relay confirmation dialog
    val currentRelayToUnset = relayToUnset
    if (showUnsetRelayDialog && currentRelayToUnset != null) {
        AlertDialog(
            onDismissRequest = {
                showUnsetRelayDialog = false
                relayToUnset = null
            },
            icon = {
                Icon(
                    imageVector = Icons.Filled.Hub,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                )
            },
            title = {
                Text(
                    text = "Unset as Your Relay?",
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            },
            text = {
                Text(
                    text = "\"${currentRelayToUnset.displayName}\" will be removed from contacts.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.End,
                ) {
                    TextButton(
                        onClick = {
                            viewModel.unsetRelayAndDelete(
                                currentRelayToUnset.destinationHash,
                                autoSelectNew = true,
                            )
                            showUnsetRelayDialog = false
                            relayToUnset = null
                        },
                    ) {
                        Text("Remove & Auto-Select New")
                    }
                    TextButton(
                        onClick = {
                            viewModel.unsetRelayAndDelete(
                                currentRelayToUnset.destinationHash,
                                autoSelectNew = false,
                            )
                            showUnsetRelayDialog = false
                            relayToUnset = null
                        },
                    ) {
                        Text("Remove Only")
                    }
                    TextButton(
                        onClick = {
                            showUnsetRelayDialog = false
                            relayToUnset = null
                        },
                    ) {
                        Text("Cancel")
                    }
                }
            },
        )
    }

    // Edit nickname dialog
    val nicknameContactHash = editNicknameContactHash
    if (showEditNicknameDialog && nicknameContactHash != null) {
        EditNicknameDialog(
            destinationHash = nicknameContactHash,
            currentNickname = editNicknameCurrentValue,
            onDismiss = {
                showEditNicknameDialog = false
                editNicknameContactHash = null
                editNicknameCurrentValue = null
            },
            onConfirm = { newNickname ->
                viewModel.updateNickname(nicknameContactHash, newNickname)
                showEditNicknameDialog = false
                editNicknameContactHash = null
                editNicknameCurrentValue = null
            },
        )
    }

    // Pending/unresolved contact bottom sheet
    val pendingContact = pendingContactToShow
    if (showPendingContactSheet && pendingContact != null) {
        PendingContactBottomSheet(
            contact = pendingContact,
            onDismiss = {
                showPendingContactSheet = false
                pendingContactToShow = null
            },
            onRetrySearch = {
                viewModel.retryIdentityResolution(pendingContact.destinationHash)
            },
            onRemoveContact = {
                viewModel.deleteContact(pendingContact.destinationHash)
            },
        )
    }

    // Node type filter dialog (for Network tab)
    if (showNodeTypeFilterDialog) {
        NodeTypeFilterDialog(
            selectedTypes = selectedNodeTypes,
            showAudio = showAudioAnnounces,
            selectedInterfaceTypes = selectedInterfaceTypes,
            onDismiss = { showNodeTypeFilterDialog = false },
            onConfirm = { newSelection, newShowAudio, newInterfaceTypes ->
                announceViewModel.updateSelectedNodeTypes(newSelection)
                announceViewModel.updateShowAudioAnnounces(newShowAudio)
                announceViewModel.updateSelectedInterfaceTypes(newInterfaceTypes)
                showNodeTypeFilterDialog = false
            },
        )
    }

    // Clear all announces confirmation dialog
    if (showClearAllAnnouncesDialog) {
        ClearAllAnnouncesDialog(
            onConfirm = {
                announceViewModel.deleteAllAnnounces()
                showClearAllAnnouncesDialog = false
            },
            onDismiss = {
                showClearAllAnnouncesDialog = false
            },
        )
    }
}

/**
 * Contact list item with integrated context menu.
 * Extracted to reduce duplication between pinned and all contacts sections.
 */
@Composable
private fun ContactListItemWithMenu(
    contact: EnrichedContact,
    onClick: () -> Unit,
    onPinToggle: () -> Unit,
    onEditNickname: () -> Unit,
    onViewDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    val hapticFeedback = LocalHapticFeedback.current
    var showMenu by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        ContactListItem(
            contact = contact,
            onClick = onClick,
            onPinClick = onPinToggle,
            onLongPress = {
                hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                showMenu = true
            },
        )

        ContactContextMenu(
            expanded = showMenu,
            onDismiss = { showMenu = false },
            isPinned = contact.isPinned,
            isRelay = contact.isMyRelay,
            contactName = contact.displayName,
            onPin = {
                onPinToggle()
                showMenu = false
            },
            onEditNickname = {
                onEditNickname()
                showMenu = false
            },
            onViewDetails = {
                onViewDetails()
                showMenu = false
            },
            onRemove = {
                onRemove()
                showMenu = false
            },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ContactListItem(
    contact: EnrichedContact,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPinClick: () -> Unit,
    onLongPress: () -> Unit = {},
) {
    // Determine if contact is pending or unresolved
    val isPending = contact.status == ContactStatus.PENDING_IDENTITY
    val isUnresolved = contact.status == ContactStatus.UNRESOLVED
    val isActive = contact.status == ContactStatus.ACTIVE

    // Dim colors for non-active contacts
    val textAlpha = if (isActive) 1f else 0.6f

    Card(
        modifier =
            modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = onLongPress,
                ),
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
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Profile icon with online/status indicator
            Box {
                // Use ProfileIcon with icon data if available, fallback to identicon
                ProfileIcon(
                    iconName = contact.iconName,
                    foregroundColor = contact.iconForegroundColor,
                    backgroundColor = contact.iconBackgroundColor,
                    size = 48.dp,
                    fallbackHash = contact.publicKey ?: contact.destinationHash.toByteArray(),
                )

                // Status indicator overlay
                when {
                    isPending -> {
                        // Show spinner for pending
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(14.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    isUnresolved -> {
                        // Show error icon for unresolved
                        Box(
                            modifier =
                                Modifier
                                    .size(18.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MaterialTheme.colorScheme.surfaceVariant, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Error,
                                contentDescription = "Identity not found",
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    contact.isOnline -> {
                        // Online indicator for active contacts
                        Box(
                            modifier =
                                Modifier
                                    .size(14.dp)
                                    .align(Alignment.BottomEnd)
                                    .background(MeshConnected, CircleShape)
                                    .clip(CircleShape),
                        )
                    }
                }

                // Relay badge overlay (Hub icon in top-right corner)
                if (contact.isMyRelay) {
                    Box(
                        modifier =
                            Modifier
                                .size(20.dp)
                                .align(Alignment.TopEnd)
                                .offset(x = 4.dp, y = (-4).dp)
                                .background(MaterialTheme.colorScheme.tertiary, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Hub,
                            contentDescription = "My Relay",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onTertiary,
                        )
                    }
                }
            }

            // Contact info
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Display name (constrained to prevent badge squishing)
                Text(
                    text = contact.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = textAlpha),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                // Destination hash
                Text(
                    text = contact.destinationHash,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha),
                )

                // Status line - varies based on contact status
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        isPending -> {
                            Text(
                                text = "Searching for identity...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        isUnresolved -> {
                            Text(
                                text = "Identity not found - tap to retry",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                        else -> {
                            // Normal status for active contacts
                            val lastSeen = contact.lastSeenTimestamp
                            if (lastSeen != null) {
                                Text(
                                    text = if (contact.isOnline) "Online" else formatRelativeTime(lastSeen),
                                    style = MaterialTheme.typography.bodySmall,
                                    color =
                                        if (contact.isOnline) {
                                            MeshConnected
                                        } else {
                                            MaterialTheme.colorScheme.onSurfaceVariant
                                        },
                                )

                                if (contact.isOnline && contact.hops != null) {
                                    Text(
                                        text = "• ${contact.hops} hops",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            } else {
                                Text(
                                    text = "Never seen",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }

                            // Show first tag if exists
                            val tags = contact.getTagsList()
                            if (tags.isNotEmpty()) {
                                Text(
                                    text = "• ${tags.first()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                        }
                    }
                }
            }

            // Badge column (location badge + relay badge + source badge)
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 4.dp),
            ) {
                // Show location icon if contact is sharing their location with us
                if (contact.isReceivingLocationFrom) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = "Sharing location with you",
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }

                // Show "RELAY" badge for relay contacts
                if (contact.isMyRelay) {
                    Box(
                        modifier =
                            Modifier
                                .background(
                                    color = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.2f),
                                    shape = RoundedCornerShape(4.dp),
                                ).padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "RELAY",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }

                // Source badge - prioritize status over addedVia for pending contacts
                val (badgeIcon, badgeColor) =
                    when {
                        contact.status == ContactStatus.PENDING_IDENTITY ->
                            Icons.Default.HourglassEmpty to MaterialTheme.colorScheme.secondary
                        contact.status == ContactStatus.UNRESOLVED ->
                            Icons.Default.Error to MaterialTheme.colorScheme.error
                        contact.addedVia == "ANNOUNCE" ->
                            Icons.Default.Star to MaterialTheme.colorScheme.tertiary
                        contact.addedVia == "QR_CODE" ->
                            Icons.Default.QrCode to MaterialTheme.colorScheme.secondary
                        contact.addedVia == "MANUAL" || contact.addedVia == "MANUAL_PENDING" ->
                            Icons.Default.Edit to MaterialTheme.colorScheme.secondary
                        else ->
                            Icons.Default.Person to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                Icon(
                    imageVector = badgeIcon,
                    contentDescription = "Added via ${contact.addedVia}",
                    modifier = Modifier.size(16.dp),
                    tint = badgeColor.copy(alpha = textAlpha),
                )
            }

            // Pin button (or retry button for unresolved)
            if (isUnresolved) {
                IconButton(onClick = onClick) {
                    Icon(
                        imageVector = Icons.Filled.Refresh,
                        contentDescription = "Retry search",
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            } else {
                IconButton(onClick = onPinClick) {
                    Icon(
                        imageVector = if (contact.isPinned) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (contact.isPinned) "Unpin" else "Pin",
                        tint =
                            if (contact.isPinned) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = textAlpha)
                            },
                    )
                }
            }
        }
    }
}

@Composable
fun ContactContextMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    isPinned: Boolean,
    isRelay: Boolean,
    contactName: String,
    onPin: () -> Unit,
    onEditNickname: () -> Unit,
    onViewDetails: () -> Unit,
    onRemove: () -> Unit,
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 3.dp,
        offset = DpOffset(x = 8.dp, y = 0.dp),
    ) {
        // Pin/Unpin contact
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = if (isPinned) Icons.Filled.Star else Icons.Outlined.Star,
                    contentDescription = null,
                    tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                )
            },
            text = {
                Text(if (isPinned) "Unpin Contact" else "Pin Contact")
            },
            onClick = onPin,
        )

        HorizontalDivider()

        // View peer details
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                )
            },
            text = {
                Text("View Peer Details")
            },
            onClick = onViewDetails,
        )

        HorizontalDivider()

        // Edit nickname
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = null,
                )
            },
            text = {
                Text("Edit Nickname")
            },
            onClick = onEditNickname,
        )

        HorizontalDivider()

        // Remove from contacts (destructive action)
        DropdownMenuItem(
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            text = {
                Text(
                    text = if (isRelay) "Unset as Relay" else "Remove from Contacts",
                    color = MaterialTheme.colorScheme.error,
                )
            },
            onClick = onRemove,
        )
    }
}

@Composable
fun LoadingContactsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(48.dp),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "Loading contacts...",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun EmptyContactsState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = Icons.Default.People,
            contentDescription = null,
            modifier = Modifier.size(96.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No contacts yet",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Star peers in the Announce Stream\nor add contacts via QR code",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddContactBottomSheet(
    onDismiss: () -> Unit,
    onScanQrCode: () -> Unit,
    onManualEntry: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            Text(
                text = "Add Contact",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )

            Divider(modifier = Modifier.padding(vertical = 8.dp))

            // Scan QR Code option
            ListItem(
                headlineContent = { Text("Scan QR Code") },
                supportingContent = { Text("Scan a contact's QR code to add them") },
                leadingContent = {
                    Icon(
                        Icons.Default.QrCodeScanner,
                        contentDescription = null,
                    )
                },
                modifier =
                    Modifier.clickable {
                        onDismiss()
                        onScanQrCode()
                    },
            )

            // Manual Entry option
            ListItem(
                headlineContent = { Text("Manual Entry") },
                supportingContent = { Text("Paste RNS identity string") },
                leadingContent = {
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = null,
                    )
                },
                modifier = Modifier.clickable { onManualEntry() },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntryDialog(
    onDismiss: () -> Unit,
    onConfirm: (identityString: String, nickname: String?) -> Unit,
) {
    var identityString by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add Contact") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Paste an LXMF identity string (lxma://...) or destination hash from Sideband",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = identityString,
                    onValueChange = {
                        // VALIDATION: Trim whitespace automatically
                        identityString = it.trim()
                        errorMessage = null
                    },
                    label = { Text("Identity or Address") },
                    placeholder = { Text("lxma://... or 32-char hash") },
                    singleLine = false,
                    maxLines = 4,
                    modifier = Modifier.fillMaxWidth(),
                    isError = errorMessage != null,
                    supportingText = {
                        val error = errorMessage
                        if (error != null) {
                            Text(error, color = MaterialTheme.colorScheme.error)
                        } else {
                            Text("Full lxma:// URL or 32-character hex address")
                        }
                    },
                )

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { newValue ->
                        // VALIDATION: Enforce nickname length limit
                        if (newValue.length <= ValidationConstants.MAX_NICKNAME_LENGTH) {
                            nickname = newValue
                        }
                    },
                    label = { Text("Nickname (optional)") },
                    placeholder = { Text("Enter a name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${nickname.length}/${ValidationConstants.MAX_NICKNAME_LENGTH}")
                    },
                    isError = nickname.length >= ValidationConstants.MAX_NICKNAME_LENGTH,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    // VALIDATION: Use InputValidator to parse both full lxma:// and hash-only input
                    when (val result = InputValidator.parseIdentityInput(identityString)) {
                        is ValidationResult.Error -> {
                            errorMessage = result.message
                            return@TextButton
                        }
                        is ValidationResult.Success -> {
                            // Validation passed, proceed
                            onConfirm(identityString, nickname.ifBlank { null })
                        }
                    }
                },
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Bottom sheet for pending or unresolved contacts.
 * Shows status explanation and actions (retry search, remove contact).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PendingContactBottomSheet(
    contact: EnrichedContact,
    onDismiss: () -> Unit,
    onRetrySearch: () -> Unit,
    onRemoveContact: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isPending = contact.status == ContactStatus.PENDING_IDENTITY
    val isUnresolved = contact.status == ContactStatus.UNRESOLVED

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Header with profile icon and name
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ProfileIcon(
                    iconName = contact.iconName,
                    foregroundColor = contact.iconForegroundColor,
                    backgroundColor = contact.iconBackgroundColor,
                    size = 48.dp,
                    fallbackHash = contact.publicKey ?: contact.destinationHash.toByteArray(),
                )
                Column {
                    Text(
                        text = contact.displayName,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = contact.destinationHash,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            HorizontalDivider()

            // Status explanation
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(
                    imageVector = if (isPending) Icons.Default.HourglassEmpty else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (isPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(24.dp),
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (isPending) "Searching for Identity" else "Identity Not Found",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isPending) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    )
                    Text(
                        text =
                            if (isPending) {
                                "This contact was added with only their address. Columba is " +
                                    "searching the network for their full identity. Once found, " +
                                    "you'll be able to send messages."
                            } else {
                                "Columba couldn't find this contact's identity on the network " +
                                    "after 24 hours. They may be offline or using a different " +
                                    "address. You can retry the search or remove this contact."
                            },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Action buttons
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Retry search button
                TextButton(
                    onClick = {
                        onRetrySearch()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (isPending) "Search Again Now" else "Retry Search")
                }

                // Remove contact button
                TextButton(
                    onClick = {
                        onRemoveContact()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors =
                        androidx.compose.material3.ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text("Remove Contact")
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
fun EditNicknameDialog(
    destinationHash: String,
    currentNickname: String?,
    onDismiss: () -> Unit,
    onConfirm: (String?) -> Unit,
) {
    var nickname by remember { mutableStateOf(currentNickname.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Nickname") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Set a custom nickname for this contact",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Show destination hash for context
                Text(
                    text = "Contact: $destinationHash",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )

                OutlinedTextField(
                    value = nickname,
                    onValueChange = { newValue ->
                        // VALIDATION: Enforce nickname length limit
                        if (newValue.length <= ValidationConstants.MAX_NICKNAME_LENGTH) {
                            nickname = newValue
                        }
                    },
                    label = { Text("Nickname") },
                    placeholder = { Text("Enter a custom name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    supportingText = {
                        Text("${nickname.length}/${ValidationConstants.MAX_NICKNAME_LENGTH}")
                    },
                    isError = nickname.length >= ValidationConstants.MAX_NICKNAME_LENGTH,
                    trailingIcon = {
                        if (nickname.isNotEmpty()) {
                            IconButton(onClick = { nickname = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Clear,
                                    contentDescription = "Clear nickname",
                                )
                            }
                        }
                    },
                )

                if (nickname.isEmpty() && currentNickname != null) {
                    Text(
                        text = "Clearing the nickname will use the announce name if available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onConfirm(nickname.ifBlank { null })
                },
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
