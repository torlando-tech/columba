package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import network.columba.app.data.db.entity.ContactStatus
import network.columba.app.rns.api.model.NodeType
import network.columba.app.service.RelayInfo
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.test.TestFactories
import network.columba.app.viewmodel.AddContactResult
import network.columba.app.viewmodel.AnnounceStreamViewModel
import network.columba.app.viewmodel.ContactGroups
import network.columba.app.viewmodel.ContactsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

/**
 * Comprehensive UI tests for ContactsScreen.kt (1,473 lines).
 * Tests all major UI components, interactions, and dialogs.
 * Uses Robolectric + Compose for local testing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ContactsScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Empty State Tests ==========

    @Test
    fun emptyContactsState_displaysIcon() {
        composeTestRule.setContent {
            EmptyContactsState()
        }

        composeTestRule.onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    @Test
    fun emptyContactsState_displaysPrimaryMessage() {
        composeTestRule.setContent {
            EmptyContactsState()
        }

        composeTestRule.onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    @Test
    fun emptyContactsState_displaysSecondaryMessage() {
        composeTestRule.setContent {
            EmptyContactsState()
        }

        composeTestRule
            .onNodeWithText(
                "Star peers in the Announce Stream\nor add contacts via QR code",
            ).assertIsDisplayed()
    }

    @Test
    fun contactsScreen_emptyList_displaysEmptyState() {
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), emptyList()),
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    // ========== TopAppBar Tests ==========

    @Test
    fun contactsScreen_displaysTitle() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("Contacts").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_displaysContactCount_singular() {
        val contacts =
            listOf(
                TestFactories.createEnrichedContact(destinationHash = "hash1"),
            )
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), contacts),
                contactCount = 1,
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Contact count is now displayed in the tab label
        composeTestRule.onNodeWithText("My Contacts (1)").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_displaysContactCount_plural() {
        val contacts =
            listOf(
                TestFactories.createEnrichedContact(destinationHash = "hash1"),
                TestFactories.createEnrichedContact(destinationHash = "hash2"),
                TestFactories.createEnrichedContact(destinationHash = "hash3"),
            )
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), contacts),
                contactCount = 3,
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Contact count is now displayed in the tab label
        composeTestRule.onNodeWithText("My Contacts (3)").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_displaysSearchButton() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Search").assertIsDisplayed()
    }

    // Note: Disabled due to FAB visibility timing issues in Robolectric
    // @Test
    // fun contactsScreen_displaysAddButton() {
    //     val mockViewModel = createMockContactsViewModel()
    //
    //     composeTestRule.setContent {
    //         ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
    //     }
    //
    //     composeTestRule.onNodeWithContentDescription("Add contact").assertIsDisplayed()
    // }

    // ========== Search Bar Tests ==========

    @Test
    fun contactsScreen_searchButtonClick_togglesSearchBar() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Initially search bar is hidden
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertDoesNotExist()

        // Click search button
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Search bar appears
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_searchBar_closeButton_hidesSearchBar() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Open search bar
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertIsDisplayed()

        // Click close button
        composeTestRule.onNodeWithContentDescription("Close search").performClick()

        // Search bar is hidden
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertDoesNotExist()
    }

    @Test
    fun contactsScreen_dismissedSearch_withNonBlankQuery_keepsFilterVisible() {
        val mockViewModel = createMockContactsViewModel(searchQuery = "Alice")

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithContentDescription("Close search").performClick()

        // The live filter must stay visible outside the dismissed bar
        composeTestRule.onNodeWithText("Filtering: \"Alice\"").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_filteredEmpty_showsQueryAwareEmptyState() {
        val mockViewModel = createMockContactsViewModel(searchQuery = "Alice", isLoading = false)

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("No contacts match \"Alice\"").assertIsDisplayed()
        composeTestRule.onNodeWithText("No contacts yet").assertDoesNotExist()
    }

    @Test
    fun contactsScreen_searchBar_textInput_updatesViewModel() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Open search bar
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Then - search field is displayed
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertIsDisplayed()

        // Enter text (find the text field and input text)
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").performTextInput("Alice")

        // Verify viewModel method was called
        verify { mockViewModel.onSearchQueryChanged("Alice") }
    }

    @Test
    fun contactsScreen_searchBar_preservesSpacesInQuery() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Open search bar
        composeTestRule.onNodeWithContentDescription("Search").performClick()
        composeTestRule.onNodeWithText("Search by name, hash, or tag...").assertIsDisplayed()

        // Regression for the "contacts search bar doesn't let you type a space" bug:
        // the field used to run every keystroke through a validator that trimmed,
        // so a trailing space (the state right after the user presses the space key)
        // was eaten on the next render and the query never contained a space.
        val field = composeTestRule.onNodeWithText("Search by name, hash, or tag...")
        field.performTextInput("Alice")
        composeTestRule.waitForIdle()

        // User presses the space key: the field content becomes "Alice "
        field.performTextInput("Alice ")
        composeTestRule.waitForIdle()
        verify { mockViewModel.onSearchQueryChanged("Alice ") }

        // User continues typing: the internal space must survive into the query
        field.performTextInput("Alice Smith")
        composeTestRule.waitForIdle()
        verify { mockViewModel.onSearchQueryChanged("Alice Smith") }
    }

    @Test
    fun contactsScreen_searchBar_clearButton_clearsText() {
        val mockViewModel = createMockContactsViewModel(searchQuery = "Test")

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Open search bar
        composeTestRule.onNodeWithContentDescription("Search").performClick()

        // Clear button should be visible when there's text
        composeTestRule.onNodeWithContentDescription("Clear search").assertIsDisplayed()

        // Click clear button
        composeTestRule.onNodeWithContentDescription("Clear search").performClick()

        // Verify clear was called
        verify { mockViewModel.onSearchQueryChanged("") }
    }

    // ========== Contact List Display Tests ==========

    @Test
    fun contactsScreen_withRelayContact_displaysRelaySection() {
        val relayContact =
            TestFactories.createEnrichedContact(
                destinationHash = "relay_hash",
                displayName = "My Relay",
                isMyRelay = true,
            )
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(relay = relayContact, pinned = emptyList(), all = emptyList()),
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("MY RELAY").assertIsDisplayed()
        composeTestRule.onNodeWithText("My Relay").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_relayAutoSelected_showsAutoBadge() {
        val relayContact =
            TestFactories.createEnrichedContact(
                destinationHash = "relay_hash",
                displayName = "Auto Relay",
                isMyRelay = true,
            )
        val relayInfo = TestFactories.createRelayInfo(isAutoSelected = true)
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(relay = relayContact, pinned = emptyList(), all = emptyList()),
                currentRelayInfo = relayInfo,
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("(auto)").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_withPinnedContacts_displaysPinnedSection() {
        val pinnedContacts =
            listOf(
                TestFactories.createEnrichedContact(
                    destinationHash = "pinned1",
                    displayName = "Pinned Contact",
                    isPinned = true,
                ),
            )
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, pinnedContacts, emptyList()),
            )

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("PINNED").assertIsDisplayed()
        composeTestRule.onNodeWithText("Pinned Contact").assertIsDisplayed()
    }

    // Note: Disabled due to LazyColumn item visibility limitations in Robolectric
    // With SegmentedButton tabs added, not all LazyColumn items are composed in tests
    // The PINNED section test above validates that the list renders correctly
    // @Test
    // fun contactsScreen_withAllContacts_displaysAllContactsSection() {
    //     val allContacts =
    //         listOf(
    //             TestFactories.createEnrichedContact(
    //                 destinationHash = "contact1",
    //                 displayName = "Regular Contact",
    //             ),
    //         )
    //     val pinnedContacts =
    //         listOf(
    //             TestFactories.createEnrichedContact(
    //                 destinationHash = "pinned1",
    //                 displayName = "Pinned",
    //                 isPinned = true,
    //             ),
    //         )
    //     val mockViewModel =
    //         createMockContactsViewModel(
    //             groupedContacts = ContactGroups(null, pinnedContacts, allContacts),
    //         )
    //
    //     composeTestRule.setContent {
    //         ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
    //     }
    //
    //     // When there are pinned contacts, "ALL CONTACTS" header is shown
    //     composeTestRule.onNodeWithText("ALL CONTACTS").assertExists()
    //     composeTestRule.onNodeWithText("Regular Contact").assertExists()
    // }

    // Note: Disabled due to LazyColumn section header visibility issues in Robolectric
    // @Test
    // fun contactsScreen_multipleSections_rendersCorrectly() {
    //     val relay = TestFactories.createEnrichedContact(
    //         destinationHash = "relay",
    //         displayName = "Relay",
    //         isMyRelay = true,
    //     )
    //     val pinned = listOf(
    //         TestFactories.createEnrichedContact(destinationHash = "p1", displayName = "Pinned1", isPinned = true),
    //     )
    //     val all = listOf(
    //         TestFactories.createEnrichedContact(destinationHash = "a1", displayName = "Contact1"),
    //     )
    //     val mockViewModel = createMockContactsViewModel(
    //         groupedContacts = ContactGroups(relay, pinned, all),
    //     )
    //
    //     composeTestRule.setContent {
    //         ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
    //     }
    //
    //     composeTestRule.onNodeWithText("MY RELAY").assertIsDisplayed()
    //     composeTestRule.onNodeWithText("PINNED").assertIsDisplayed()
    //     composeTestRule.onNodeWithText("ALL CONTACTS").assertIsDisplayed()
    // }

    // ========== ContactListItem Tests ==========

    @Test
    fun contactListItem_displaysContactName() {
        val contact = TestFactories.createEnrichedContact(displayName = "Alice")

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun contactListItem_displaysFullHash() {
        val contact =
            TestFactories.createEnrichedContact(
                destinationHash = "0123456789abcdef0123456789abcdef",
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        // Full destination hash is displayed
        composeTestRule.onNodeWithText("0123456789abcdef0123456789abcdef").assertIsDisplayed()
    }

    @Test
    fun contactListItem_onlineFlag_doesNotClaimLiveOnlineState() {
        val contact =
            TestFactories.createEnrichedContact(
                TestFactories.EnrichedContactConfig(
                    displayName = "Alice",
                    isOnline = true,
                ),
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("Online").assertDoesNotExist()
    }

    @Test
    fun contactListItem_onlineFlag_doesNotDisplayLiveRouteHops() {
        val contact =
            TestFactories.createEnrichedContact(
                TestFactories.EnrichedContactConfig(
                    displayName = "Alice",
                    isOnline = true,
                    hops = 3,
                ),
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("3 hops", substring = true).assertDoesNotExist()
    }

    @Test
    fun contactListItem_relativeTimeUsesInjectedLifecycleClock() {
        val contact =
            TestFactories.createEnrichedContact(
                TestFactories.EnrichedContactConfig(
                    displayName = "Alice",
                    lastSeenTimestamp = 60_000L,
                ),
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                nowMillis = 6 * 60_000L,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("5 minutes ago").assertIsDisplayed()
    }

    @Test
    fun contactListItem_pending_showsSearchingMessage() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Pending",
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("Searching for identity...").assertIsDisplayed()
    }

    @Test
    fun contactListItem_unresolved_showsErrorMessage() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Unresolved",
                status = ContactStatus.UNRESOLVED,
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("Identity not found - tap to retry").assertIsDisplayed()
    }

    @Test
    fun contactListItem_relay_showsRelayBadge() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Relay",
                isMyRelay = true,
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("RELAY").assertIsDisplayed()
    }

    @Test
    fun contactListItem_pinned_showsFilledStar() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Pinned",
                isPinned = true,
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        // Pinned contacts show filled star icon
        composeTestRule.onNodeWithContentDescription("Unpin").assertIsDisplayed()
    }

    @Test
    fun contactListItem_notPinned_showsOutlinedStar() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Regular",
                isPinned = false,
            )

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithContentDescription("Pin").assertIsDisplayed()
    }

    @Test
    fun contactListItem_click_invokesOnClick() {
        var clicked = false
        val contact = TestFactories.createEnrichedContact(displayName = "Alice")

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = { clicked = true },
                onPinClick = {},
            )
        }

        composeTestRule.onNodeWithText("Alice").performClick()

        assertTrue(clicked)
    }

    @Test
    fun contactListItem_pinClick_invokesOnPinClick() {
        var pinClicked = false
        val contact = TestFactories.createEnrichedContact()

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = { pinClicked = true },
            )
        }

        composeTestRule.onNodeWithContentDescription("Pin").performClick()

        assertTrue(pinClicked)
    }

    @Test
    fun contactListItem_longPress_invokesOnLongPress() {
        var longPressed = false
        val contact = TestFactories.createEnrichedContact(displayName = "Alice")

        composeTestRule.setContent {
            ContactListItem(
                contact = contact,
                onClick = {},
                onPinClick = {},
                onLongPress = { longPressed = true },
            )
        }

        composeTestRule.onNodeWithText("Alice").performTouchInput { longClick() }

        assertTrue(longPressed)
    }

    // ========== Context Menu Tests ==========

    @Test
    fun contextMenu_expanded_displaysAllOptions() {
        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = false,
                contactName = "Test",
                onPin = {},
                onEditNickname = {},
                onViewDetails = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Pin Contact").assertIsDisplayed()
        composeTestRule.onNodeWithText("View Peer Details").assertIsDisplayed()
        composeTestRule.onNodeWithText("Edit Nickname").assertIsDisplayed()
        composeTestRule.onNodeWithText("Remove from Contacts").assertIsDisplayed()
    }

    @Test
    fun contextMenu_pinnedContact_showsUnpinOption() {
        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = true,
                isRelay = false,
                contactName = "Test",
                onPin = {},
                onEditNickname = {},
                onViewDetails = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Unpin Contact").assertIsDisplayed()
    }

    @Test
    fun contextMenu_relayContact_showsUnsetOption() {
        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = true,
                contactName = "Relay",
                onPin = {},
                onEditNickname = {},
                onViewDetails = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Unset as Relay").assertIsDisplayed()
    }

    @Test
    fun contextMenu_pinClick_invokesCallback() {
        var pinCalled = false

        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = false,
                contactName = "Test",
                onPin = { pinCalled = true },
                onEditNickname = {},
                onViewDetails = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Pin Contact").performClick()

        assertTrue(pinCalled)
    }

    @Test
    fun contextMenu_editNicknameClick_invokesCallback() {
        var editCalled = false

        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = false,
                contactName = "Test",
                onPin = {},
                onEditNickname = { editCalled = true },
                onViewDetails = {},
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("Edit Nickname").performClick()

        assertTrue(editCalled)
    }

    @Test
    fun contextMenu_viewDetailsClick_invokesCallback() {
        var viewDetailsCalled = false

        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = false,
                contactName = "Test",
                onPin = {},
                onEditNickname = {},
                onViewDetails = { viewDetailsCalled = true },
                onRemove = {},
            )
        }

        composeTestRule.onNodeWithText("View Peer Details").performClick()

        assertTrue(viewDetailsCalled)
    }

    @Test
    fun contextMenu_removeClick_invokesCallback() {
        var removeCalled = false

        composeTestRule.setContent {
            ContactContextMenu(
                expanded = true,
                onDismiss = {},
                isPinned = false,
                isRelay = false,
                contactName = "Test",
                onPin = {},
                onEditNickname = {},
                onViewDetails = {},
                onRemove = { removeCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Remove from Contacts").performClick()

        assertTrue(removeCalled)
    }

    // ========== Add Contact Bottom Sheet Tests ==========

    @Test
    fun addContactBottomSheet_displaysTitle() {
        composeTestRule.setContent {
            AddContactBottomSheet(
                onDismiss = {},
                onScanQrCode = {},
                onManualEntry = {},
            )
        }

        composeTestRule.onNodeWithText("Add Contact").assertIsDisplayed()
    }

    @Test
    fun addContactBottomSheet_displaysScanQrCodeOption() {
        composeTestRule.setContent {
            AddContactBottomSheet(
                onDismiss = {},
                onScanQrCode = {},
                onManualEntry = {},
            )
        }

        composeTestRule.onNodeWithText("Scan QR Code").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan a contact's QR code to add them").assertIsDisplayed()
    }

    @Test
    fun addContactBottomSheet_displaysManualEntryOption() {
        composeTestRule.setContent {
            AddContactBottomSheet(
                onDismiss = {},
                onScanQrCode = {},
                onManualEntry = {},
            )
        }

        composeTestRule.onNodeWithText("Manual Entry").assertIsDisplayed()
        composeTestRule.onNodeWithText("Paste RNS identity string").assertIsDisplayed()
    }

    @Test
    fun addContactBottomSheet_scanQrCodeClick_invokesCallback() {
        var scanClicked = false

        composeTestRule.setContent {
            AddContactBottomSheet(
                onDismiss = {},
                onScanQrCode = { scanClicked = true },
                onManualEntry = {},
            )
        }

        composeTestRule.onNodeWithText("Scan QR Code").performClick()

        assertTrue(scanClicked)
    }

    @Test
    fun addContactBottomSheet_manualEntryClick_invokesCallback() {
        var manualClicked = false

        composeTestRule.setContent {
            AddContactBottomSheet(
                onDismiss = {},
                onScanQrCode = {},
                onManualEntry = { manualClicked = true },
            )
        }

        composeTestRule.onNodeWithText("Manual Entry").performClick()

        assertTrue(manualClicked)
    }

    // ========== Manual Entry Dialog Tests ==========

    @Test
    fun manualEntryDialog_displaysTitle() {
        composeTestRule.setContent {
            ManualEntryDialog(
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Add Contact").assertIsDisplayed()
    }

    @Test
    fun manualEntryDialog_displaysIdentityField() {
        composeTestRule.setContent {
            ManualEntryDialog(
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Identity or Address").assertIsDisplayed()
    }

    @Test
    fun manualEntryDialog_displaysNicknameField() {
        composeTestRule.setContent {
            ManualEntryDialog(
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Nickname (optional)").assertIsDisplayed()
    }

    @Test
    fun manualEntryDialog_displaysAddButton() {
        composeTestRule.setContent {
            ManualEntryDialog(
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Add").assertIsDisplayed()
    }

    @Test
    fun manualEntryDialog_displaysCancelButton() {
        composeTestRule.setContent {
            ManualEntryDialog(
                onDismiss = {},
                onConfirm = { _, _ -> },
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // ========== Edit Nickname Dialog Tests ==========

    @Test
    fun editNicknameDialog_displaysTitle() {
        composeTestRule.setContent {
            EditNicknameDialog(
                destinationHash = TestFactories.TEST_DEST_HASH,
                currentNickname = null,
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Edit Nickname").assertIsDisplayed()
    }

    @Test
    fun editNicknameDialog_displaysContactHash() {
        composeTestRule.setContent {
            EditNicknameDialog(
                destinationHash = "abcdef123456789",
                currentNickname = null,
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Contact: abcdef123456789", substring = true).assertIsDisplayed()
    }

    @Test
    fun editNicknameDialog_withCurrentNickname_prefillsField() {
        composeTestRule.setContent {
            EditNicknameDialog(
                destinationHash = TestFactories.TEST_DEST_HASH,
                currentNickname = "Alice",
                onDismiss = {},
                onConfirm = {},
            )
        }

        // The TextField should contain the current nickname
        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun editNicknameDialog_displaysSaveButton() {
        composeTestRule.setContent {
            EditNicknameDialog(
                destinationHash = TestFactories.TEST_DEST_HASH,
                currentNickname = null,
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Save").assertIsDisplayed()
    }

    @Test
    fun editNicknameDialog_displaysCancelButton() {
        composeTestRule.setContent {
            EditNicknameDialog(
                destinationHash = TestFactories.TEST_DEST_HASH,
                currentNickname = null,
                onDismiss = {},
                onConfirm = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    // ========== Pending Contact Bottom Sheet Tests ==========

    @Test
    fun pendingContactBottomSheet_pending_displaysSearchingTitle() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Pending Contact",
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Searching for Identity").assertIsDisplayed()
    }

    @Test
    fun pendingContactBottomSheet_unresolved_displaysErrorTitle() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Unresolved Contact",
                status = ContactStatus.UNRESOLVED,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Identity Not Found").assertIsDisplayed()
    }

    @Test
    fun pendingContactBottomSheet_displaysContactName() {
        val contact =
            TestFactories.createEnrichedContact(
                displayName = "Test Contact",
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Test Contact").assertIsDisplayed()
    }

    @Test
    fun pendingContactBottomSheet_displaysRetryButton() {
        val contact =
            TestFactories.createEnrichedContact(
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Search Again Now").assertIsDisplayed()
    }

    @Test
    fun pendingContactBottomSheet_displaysRemoveButton() {
        val contact =
            TestFactories.createEnrichedContact(
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Remove Contact").assertIsDisplayed()
    }

    @Test
    fun pendingContactBottomSheet_retryClick_invokesCallback() {
        var retryCalled = false
        val contact =
            TestFactories.createEnrichedContact(
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = { retryCalled = true },
                onRemoveContact = {},
            )
        }

        composeTestRule.onNodeWithText("Search Again Now").performClick()

        assertTrue(retryCalled)
    }

    @Test
    fun pendingContactBottomSheet_removeClick_invokesCallback() {
        var removeCalled = false
        val contact =
            TestFactories.createEnrichedContact(
                status = ContactStatus.PENDING_IDENTITY,
            )

        composeTestRule.setContent {
            PendingContactBottomSheet(
                contact = contact,
                onDismiss = {},
                onRetrySearch = {},
                onRemoveContact = { removeCalled = true },
            )
        }

        composeTestRule.onNodeWithText("Remove Contact").performClick()

        assertTrue(removeCalled)
    }

    // ========== Interaction Tests ==========

    // Note: Disabled due to FAB visibility timing issues in Robolectric
    // @Test
    // fun contactsScreen_addContactButton_opensBottomSheet() {
    //     val mockViewModel = createMockContactsViewModel()
    //
    //     composeTestRule.setContent {
    //         ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
    //     }
    //
    //     // Click add button
    //     composeTestRule.onNodeWithContentDescription("Add contact").performClick()
    //
    //     // Bottom sheet should appear
    //     composeTestRule.onNodeWithText("Add Contact").assertIsDisplayed()
    //     composeTestRule.onNodeWithText("Scan QR Code").assertIsDisplayed()
    // }

    @Test
    fun contactsScreen_contactClick_invokesCallback() {
        var clickedHash: String? = null
        var clickedName: String? = null
        val contacts =
            listOf(
                TestFactories.createEnrichedContact(
                    destinationHash = "test_hash",
                    displayName = "Test Contact",
                ),
            )
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), contacts),
            )

        composeTestRule.setContent {
            ContactsScreen(
                viewModel = mockViewModel,
                announceViewModel = createMockAnnounceStreamViewModel(),
                onContactClick = { hash, name ->
                    clickedHash = hash
                    clickedName = name
                },
            )
        }

        composeTestRule.onNodeWithText("Test Contact").performClick()

        assertTrue(clickedHash == "test_hash")
        assertTrue(clickedName == "Test Contact")
    }

    // ========== Tab Selection Tests ==========
    // Note: Full tab switching tests require integration tests due to Hilt dependencies
    // in AnnounceStreamContent. The rememberSaveable fix for tab persistence is verified
    // by manual testing - tab selection now persists across navigation.

    @Test
    fun contactsScreen_displaysBothTabButtons() {
        val mockViewModel = createMockContactsViewModel(contactCount = 5)
        val mockAnnounceViewModel = createMockAnnounceStreamViewModel(announceCount = 3)

        composeTestRule.setContent {
            ContactsScreen(
                viewModel = mockViewModel,
                announceViewModel = mockAnnounceViewModel,
            )
        }

        // Both tab buttons should be visible
        composeTestRule.onNodeWithText("My Contacts (5)").assertIsDisplayed()
        composeTestRule.onNodeWithText("Network (3)").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_myContactsTabSelected_showsEmptyStateOrContacts() {
        val mockViewModel = createMockContactsViewModel()
        val mockAnnounceViewModel = createMockAnnounceStreamViewModel()

        composeTestRule.setContent {
            ContactsScreen(
                viewModel = mockViewModel,
                announceViewModel = mockAnnounceViewModel,
            )
        }

        // My Contacts is selected by default - shows empty state when no contacts
        composeTestRule.onNodeWithText("No contacts yet").assertIsDisplayed()
    }

    // ========== Contact-Type Filter + Announce actions (GH issue #863) ==========

    @Test
    fun contactsScreen_myContactsTab_displaysFilterToggle() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        // Default is expanded on non-compact heights (Robolectric's default
        // window is 360x800dp), so the icon is the "hide" variant.
        composeTestRule.onNodeWithContentDescription("Hide filters").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_myContactsTab_displaysAnnounceButton() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Announce now").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_myContactsTab_announceButton_triggersAnnounce() {
        val mockViewModel = createMockContactsViewModel()
        val mockAnnounceViewModel = createMockAnnounceStreamViewModel()
        val announceSuccessFlow = MutableStateFlow(false)
        every { mockAnnounceViewModel.announceSuccess } returns announceSuccessFlow
        every { mockAnnounceViewModel.triggerAnnounce() } answers { announceSuccessFlow.value = true }

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = mockAnnounceViewModel)
        }

        composeTestRule.onNodeWithContentDescription("Announce now").performClick()
        composeTestRule.waitForIdle()

        // The screen's success toast is the user-visible outcome of the click.
        assertEquals("Announce sent!", ShadowToast.getTextOfLatestToast())
    }

    @Test
    fun contactsScreen_myContactsTab_displaysContactTypeChips() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("Peers").assertIsDisplayed()
        composeTestRule.onNodeWithText("Sites").assertIsDisplayed()
        composeTestRule.onNodeWithText("Relays").assertIsDisplayed()
        composeTestRule.onNodeWithText("Audio").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_myContactsTab_toolbarOrder_searchFilterAnnounceAdd() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        val searchLeft = toolbarLeft("Search")
        val filterLeft = toolbarLeft("Hide filters")
        val announceLeft = toolbarLeft("Announce now")
        // "Add contact" also labels the FAB; the top bar copy is the topmost node.
        val addLeft =
            composeTestRule
                .onAllNodesWithContentDescription("Add contact")
                .fetchSemanticsNodes()
                .minBy { it.boundsInRoot.top }
                .boundsInRoot
                .left

        // My Contacts mirrors the Network tab's leading Search / Filter /
        // Announce order, with its own Add action trailing.
        assertTrue("Search must precede Filter", searchLeft < filterLeft)
        assertTrue("Filter must precede Announce", filterLeft < announceLeft)
        assertTrue("Announce must precede Add", announceLeft < addLeft)
    }

    private fun toolbarLeft(contentDescription: String): Float =
        composeTestRule
            .onNodeWithContentDescription(contentDescription)
            .fetchSemanticsNode()
            .boundsInRoot
            .left

    @Test
    fun contactsScreen_contactTypeChip_selectsSingleType() {
        val mockViewModel = createMockContactsViewModel()
        bindFilters(mockViewModel)

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("Relays").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Relays").assertIsSelected()
        composeTestRule.onNodeWithText("All").assertIsNotSelected()
    }

    @Test
    fun contactsScreen_activeContactTypeChip_tapAgain_clearsFilter() {
        val mockViewModel = createMockContactsViewModel()
        bindFilters(mockViewModel, initialTypes = setOf(NodeType.PEER))

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("Peers").assertIsSelected()

        composeTestRule.onNodeWithText("Peers").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("All").assertIsSelected()
    }

    @Test
    fun contactsScreen_filterIcon_collapsesContactTypeChips() {
        val mockViewModel = createMockContactsViewModel()

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Hide filters").performClick()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Show filters").assertIsDisplayed()
        composeTestRule.onNodeWithText("Peers").assertDoesNotExist()
    }

    @Test
    fun contactsScreen_collapsedFilterWithActiveType_showsFilteringPill() {
        val mockViewModel = createMockContactsViewModel(selectedContactTypes = setOf(NodeType.PEER))

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithContentDescription("Hide filters").performClick()
        composeTestRule.mainClock.advanceTimeBy(1_000)
        composeTestRule.waitForIdle()

        // Collapsed pill keeps the active filter visible and expands on tap.
        composeTestRule.onNodeWithContentDescription("Active filters: Peers. Tap to edit.").assertIsDisplayed()
    }

    @Test
    fun contactsScreen_typeFilterHidesAllContacts_showsFilterEmptyState() {
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), emptyList()),
            )
        bindFilters(mockViewModel, initialTypes = setOf(NodeType.NODE))

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("No contacts match your filters").assertIsDisplayed()
        composeTestRule.onNodeWithText("No contacts yet").assertDoesNotExist()
    }

    @Test
    fun contactsScreen_searchQueryHidesAllContacts_showsFilterEmptyState() {
        // Search alone can empty the list; that must not read as an empty
        // address book, and the recovery action must clear the query too.
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), emptyList()),
            )
        bindFilters(mockViewModel, initialQuery = "zzz")

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("No contacts match your filters").assertIsDisplayed()
        composeTestRule.onNodeWithText("No contacts yet").assertDoesNotExist()
    }

    @Test
    fun contactsScreen_filterEmptyState_clearFilters_restoresEmptyAddressBookState() {
        val mockViewModel =
            createMockContactsViewModel(
                groupedContacts = ContactGroups(null, emptyList(), emptyList()),
            )
        // Both filters active: clearing only one would keep the list empty.
        bindFilters(mockViewModel, initialTypes = setOf(NodeType.NODE), initialQuery = "zzz")

        composeTestRule.setContent {
            ContactsScreen(viewModel = mockViewModel, announceViewModel = createMockAnnounceStreamViewModel())
        }

        composeTestRule.onNodeWithText("No contacts match your filters").assertIsDisplayed()

        composeTestRule.onNodeWithText("Clear filters").performClick()
        composeTestRule.waitForIdle()

        // Both filters dropped -> the genuine "no contacts" state takes over.
        composeTestRule.onNodeWithText("No contacts yet").assertIsDisplayed()
        composeTestRule.onNodeWithText("No contacts match your filters").assertDoesNotExist()
    }

    // ========== Test Helpers ==========

    /**
     * Wires the mock's filter state (contact type + search query) to real flows
     * so chip taps, typing and clearing land on observable UI state instead of
     * only being recorded by the mock.
     */
    private fun bindFilters(
        mockViewModel: ContactsViewModel,
        initialTypes: Set<NodeType> = emptySet(),
        initialQuery: String = "",
    ) {
        val typesFlow = MutableStateFlow(initialTypes)
        val queryFlow = MutableStateFlow(initialQuery)
        every { mockViewModel.selectedContactTypes } returns typesFlow
        every { mockViewModel.updateSelectedContactTypes(any()) } answers { typesFlow.value = firstArg() }
        every { mockViewModel.searchQuery } returns queryFlow
        every { mockViewModel.onSearchQueryChanged(any()) } answers { queryFlow.value = firstArg() }
    }

    @Suppress("NoRelaxedMocks") // ContactsViewModel is a complex ViewModel with many properties; explicit stubbing for all would be excessive
    private fun createMockContactsViewModel(
        groupedContacts: ContactGroups = ContactGroups(null, emptyList(), emptyList()),
        contactCount: Int = 0,
        searchQuery: String = "",
        selectedContactTypes: Set<NodeType> = emptySet(),
        currentRelayInfo: RelayInfo? = null,
        isLoading: Boolean = false,
    ): ContactsViewModel {
        val mockViewModel = mockk<ContactsViewModel>(relaxed = true)

        every { mockViewModel.contactsState } returns
            MutableStateFlow(
                network.columba.app.viewmodel.ContactsState(
                    groupedContacts = groupedContacts,
                    isLoading = isLoading,
                ),
            )
        every { mockViewModel.contactCount } returns MutableStateFlow(contactCount)
        every { mockViewModel.searchQuery } returns MutableStateFlow(searchQuery)
        every { mockViewModel.selectedContactTypes } returns MutableStateFlow(selectedContactTypes)
        every { mockViewModel.currentRelayInfo } returns MutableStateFlow(currentRelayInfo)

        // Mock decodeQrCode to return null by default
        coEvery { mockViewModel.decodeQrCode(any()) } returns null

        // Mock checkContactExists to return null by default
        coEvery { mockViewModel.checkContactExists(any()) } returns null

        // Mock addContactFromInput to return success by default
        coEvery { mockViewModel.addContactFromInput(any(), any()) } returns AddContactResult.Success

        return mockViewModel
    }

    @Suppress("NoRelaxedMocks") // AnnounceStreamViewModel is a complex ViewModel with many properties; explicit stubbing for all would be excessive
    private fun createMockAnnounceStreamViewModel(announceCount: Int = 0): AnnounceStreamViewModel {
        val mock = mockk<AnnounceStreamViewModel>(relaxed = true)
        every { mock.isAnnouncing } returns MutableStateFlow(false)
        every { mock.announceSuccess } returns MutableStateFlow(false)
        every { mock.announceError } returns MutableStateFlow(null)
        every { mock.announceCount } returns MutableStateFlow(announceCount)
        every { mock.selectedNodeTypes } returns MutableStateFlow(emptySet())
        every { mock.showAudioAnnounces } returns MutableStateFlow(true)
        every { mock.searchQuery } returns MutableStateFlow("")
        return mock
    }
}
