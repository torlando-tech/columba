package com.lxmf.messenger.ui.components

import android.app.Application
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.lxmf.messenger.data.db.entity.ContactStatus
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.test.TestFactories
import com.lxmf.messenger.ui.model.SharingDuration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ShareLocationBottomSheet.
 *
 * Tests:
 * - Contact sorting logic (recency-based)
 * - UI display and interactions
 * - Contact selection and duration selection
 * - Search filtering
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalMaterial3Api::class)
class ShareLocationBottomSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Sorting Logic Tests ==========

    @Test
    fun `contacts sorted by lastMessageTimestamp descending`() {
        // Given - contacts with different message timestamps
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = 1000L),
            createContact("bob", lastMessageTimestamp = 3000L), // Most recent
            createContact("carol", lastMessageTimestamp = 2000L),
        )

        // When - apply the same sorting logic as ShareLocationBottomSheet
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by lastMessageTimestamp descending
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `contacts without messages sorted by addedTimestamp descending`() {
        // Given - contacts without message history
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = null, addedTimestamp = 1000L),
            createContact("bob", lastMessageTimestamp = null, addedTimestamp = 3000L), // Added most recently
            createContact("carol", lastMessageTimestamp = null, addedTimestamp = 2000L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by addedTimestamp descending
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `contacts with messages appear before contacts without messages`() {
        // Given - mix of contacts with and without message history
        val contacts = listOf(
            createContact("no-messages-old", lastMessageTimestamp = null, addedTimestamp = 1000L),
            createContact("has-messages", lastMessageTimestamp = 100L), // Very old message
            createContact("no-messages-new", lastMessageTimestamp = null, addedTimestamp = 5000L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - contact with messages appears first (even with old timestamp)
        assertEquals("has-messages", sorted[0].displayName)
        // Then - contacts without messages sorted by addedTimestamp
        assertEquals("no-messages-new", sorted[1].displayName)
        assertEquals("no-messages-old", sorted[2].displayName)
    }

    @Test
    fun `contacts with same lastMessageTimestamp sorted by addedTimestamp`() {
        // Given - contacts with same message timestamp
        val contacts = listOf(
            createContact("alice", lastMessageTimestamp = 1000L, addedTimestamp = 100L),
            createContact("bob", lastMessageTimestamp = 1000L, addedTimestamp = 300L), // Added more recently
            createContact("carol", lastMessageTimestamp = 1000L, addedTimestamp = 200L),
        )

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then - sorted by addedTimestamp as tiebreaker
        assertEquals("bob", sorted[0].displayName)
        assertEquals("carol", sorted[1].displayName)
        assertEquals("alice", sorted[2].displayName)
    }

    @Test
    fun `empty contact list returns empty list`() {
        // Given
        val contacts = emptyList<EnrichedContact>()

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then
        assertEquals(0, sorted.size)
    }

    @Test
    fun `single contact returns same contact`() {
        // Given
        val contacts = listOf(createContact("solo"))

        // When
        val sorted = sortContactsByRecency(contacts)

        // Then
        assertEquals(1, sorted.size)
        assertEquals("solo", sorted[0].displayName)
    }

    // ========== ContactSelectionRow UI Tests ==========

    @Test
    fun `contactSelectionRow displays contact name`() {
        composeTestRule.setContent {
            ContactSelectionRow(
                displayName = "Alice",
                destinationHash = "abcdef1234567890",
                isSelected = false,
                onSelectionChanged = {},
            )
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
    }

    @Test
    fun `contactSelectionRow click toggles selection`() {
        var selected = false

        composeTestRule.setContent {
            ContactSelectionRow(
                displayName = "Charlie",
                destinationHash = "fedcba0987654321",
                isSelected = selected,
                onSelectionChanged = { selected = it },
            )
        }

        composeTestRule.onNodeWithText("Charlie").performClick()

        assertTrue("Selection should be toggled to true", selected)
    }

    // ========== ShareLocationBottomSheet UI Tests ==========

    @Test
    fun `shareLocationBottomSheet displays title`() {
        val contacts = createTestContactsForUI(3)

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Share your location").assertIsDisplayed()
    }

    @Test
    fun `shareLocationBottomSheet displays search field`() {
        val contacts = createTestContactsForUI(3)

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Search contacts").assertIsDisplayed()
    }

    @Test
    fun `shareLocationBottomSheet displays duration chips`() {
        val contacts = createTestContactsForUI(2)

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("15 min").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 hour").assertIsDisplayed()
        composeTestRule.onNodeWithText("4 hours").assertIsDisplayed()
        composeTestRule.onNodeWithText("Until midnight").assertIsDisplayed()
        composeTestRule.onNodeWithText("Until I stop").assertIsDisplayed()
    }

    @Test
    fun `shareLocationBottomSheet noContactsSelected button disabled`() {
        val contacts = createTestContactsForUI(3)

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").assertIsNotEnabled()
    }

    @Test
    fun `shareLocationBottomSheet withPreselectedContact button enabled`() {
        val contacts = createTestContactsForUI(3)

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                initialSelectedHashes = setOf(contacts[0].destinationHash),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").assertIsEnabled()
    }

    @Test
    fun `shareLocationBottomSheet displays contacts in list`() {
        val contacts = listOf(
            TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
            TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob"),
        )

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertIsDisplayed()
    }

    @Test
    fun `shareLocationBottomSheet preselected contacts show as chips`() {
        val contacts = listOf(
            TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
            TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob"),
        )

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                initialSelectedHashes = setOf("hash1"),
            )
        }

        composeTestRule.onNodeWithContentDescription("Remove Alice").assertIsDisplayed()
    }

    @Test
    fun `shareLocationBottomSheet default duration is one hour`() {
        val contacts = createTestContactsForUI(2)
        var selectedDuration: SharingDuration? = null

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, duration -> selectedDuration = duration },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                initialSelectedHashes = setOf(contacts[0].destinationHash),
            )
        }

        composeTestRule.onNodeWithText("Start Sharing").performClick()

        assertEquals(SharingDuration.ONE_HOUR, selectedDuration)
    }

    // Note: Duration chip selection test is unstable in Robolectric due to ModalBottomSheet
    // internal state management. The default duration test verifies the callback mechanism works.

    @Test
    fun `shareLocationBottomSheet search filters contacts`() {
        val contacts = listOf(
            TestFactories.createEnrichedContact(destinationHash = "hash1", displayName = "Alice"),
            TestFactories.createEnrichedContact(destinationHash = "hash2", displayName = "Bob"),
            TestFactories.createEnrichedContact(destinationHash = "hash3", displayName = "Charlie"),
        )

        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = contacts,
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Search contacts").performTextInput("Ali")

        composeTestRule.onNodeWithText("Alice").assertIsDisplayed()
        composeTestRule.onNodeWithText("Bob").assertDoesNotExist()
        composeTestRule.onNodeWithText("Charlie").assertDoesNotExist()
    }

    @Test
    fun `shareLocationBottomSheet empty contacts still shows UI`() {
        composeTestRule.setContent {
            ShareLocationBottomSheet(
                contacts = emptyList(),
                onDismiss = {},
                onStartSharing = { _, _ -> },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            )
        }

        composeTestRule.onNodeWithText("Share your location").assertIsDisplayed()
        composeTestRule.onNodeWithText("Start Sharing").assertIsNotEnabled()
    }

    // ========== Helper Functions ==========

    private fun createTestContactsForUI(count: Int): List<EnrichedContact> {
        return (1..count).map { i ->
            TestFactories.createEnrichedContact(
                destinationHash = "hash$i",
                displayName = "Contact $i",
            )
        }
    }

    /**
     * Applies the same sorting logic as ShareLocationBottomSheet.filteredContacts
     */
    private fun sortContactsByRecency(contacts: List<EnrichedContact>): List<EnrichedContact> {
        return contacts.sortedWith(
            compareByDescending<EnrichedContact> { it.lastMessageTimestamp ?: 0L }
                .thenByDescending { it.addedTimestamp }
        )
    }

    private fun createContact(
        name: String,
        lastMessageTimestamp: Long? = null,
        addedTimestamp: Long = System.currentTimeMillis(),
    ): EnrichedContact {
        return EnrichedContact(
            destinationHash = "hash_$name",
            publicKey = null,
            displayName = name,
            customNickname = null,
            announceName = name,
            lastSeenTimestamp = null,
            hops = null,
            isOnline = false,
            hasConversation = lastMessageTimestamp != null,
            unreadCount = 0,
            lastMessageTimestamp = lastMessageTimestamp,
            notes = null,
            tags = null,
            addedTimestamp = addedTimestamp,
            addedVia = "MANUAL",
            isPinned = false,
            status = ContactStatus.ACTIVE,
            isMyRelay = false,
            nodeType = null,
        )
    }
}
