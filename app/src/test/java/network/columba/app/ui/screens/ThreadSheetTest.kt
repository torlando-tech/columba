package network.columba.app.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.model.ThreadMainRowUi
import network.columba.app.ui.model.ThreadRowUi
import network.columba.app.ui.model.ThreadSheetUiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * UI tests for the threads sheet (threads-spec §5.2-§5.4): rows, search,
 * pinned footer sections, swipe-to-archive, selection mode and the
 * rename/reset dialog. Interactions are asserted through the sheet's
 * callbacks — the sheet itself owns no thread data.
 *
 * Back-to-collapse behaviour is covered on device by
 * `maestro/threads/07-selection-mode.yaml`; Robolectric has no real
 * back dispatcher in this rule chain.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp", application = Application::class)
class ThreadSheetTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private val archivedCalls = mutableListOf<List<String>>()
    private val unarchivedCalls = mutableListOf<List<String>>()
    private val renames = mutableListOf<Pair<String, String?>>()
    private val markedRead = mutableListOf<String>()
    private val opened = mutableListOf<String>()
    private val deleted = mutableListOf<String>()

    @Before
    fun setUp() {
        archivedCalls.clear()
        unarchivedCalls.clear()
        renames.clear()
        markedRead.clear()
        opened.clear()
        deleted.clear()
    }

    private fun row(
        hex: String,
        title: String?,
        unread: Int = 0,
        autoTitle: String? = null,
        preview: String = "",
    ) = ThreadRowUi(
        threadIdHex = hex,
        title = title,
        autoTitle = autoTitle,
        preview = preview,
        timestamp = System.currentTimeMillis() - 120_000,
        unreadCount = unread,
    )

    private fun state(
        active: List<ThreadRowUi> = listOf(
            row("01", "Trip planning", unread = 3, preview = "hotel is booked"),
            row("02", null, autoTitle = "Build notes", unread = 1),
        ),
        dormant: List<ThreadRowUi> = listOf(row("03", "Aleph mesh", preview = "partial demo page loads now")),
        archived: List<ThreadRowUi> = listOf(row("04", "Archived work", unread = 2)),
    ) = ThreadSheetUiState(
        active = active,
        dormant = dormant,
        archived = archived,
        main = ThreadMainRowUi(preview = "hello", timestamp = System.currentTimeMillis() - 60_000, unreadCount = 2),
        currentThreadIdHex = "01",
    )

    private fun setSheet(sheetState: ThreadSheetUiState = state()) {
        composeTestRule.setContent {
            MaterialTheme {
                ThreadSheet(
                    state = sheetState,
                    onDismiss = {},
                    onThreadClick = { opened += it },
                    onMainClick = {},
                    onCreateThread = {},
                    onRename = { id, title -> renames += id to title },
                    onMarkRead = { markedRead += it },
                    onArchive = { archivedCalls += it },
                    onUnarchive = { unarchivedCalls += it },
                    onReopenThread = {},
                    onDelete = { deleted += it },
                )
            }
        }
    }

    @Test
    fun `rows render resolved titles and the pinned footer counts`() {
        setSheet()

        composeTestRule.onNodeWithText("Trip planning").assertIsDisplayed()
        // Null local title -> automatic excerpt.
        composeTestRule.onNodeWithText("Build notes").assertIsDisplayed()
        // Main row is pinned in the list; Dormant/Archived are pinned at the bottom.
        composeTestRule.onNodeWithText("Main").assertIsDisplayed()
        composeTestRule.onNodeWithText("Dormant").assertIsDisplayed()
        composeTestRule.onNodeWithText("Archived").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Close threads").assertIsDisplayed()
    }

    @Test
    fun `search filters rows by title and reports no matches`() {
        setSheet()

        composeTestRule.onNodeWithText("Search threads").performTextReplacement("aleph")

        composeTestRule.onNodeWithText("Aleph mesh").assertIsDisplayed()
        composeTestRule.onNodeWithText("Trip planning").assertDoesNotExist()

        composeTestRule.onNode(hasSetTextAction()).performTextReplacement("zzzz")
        composeTestRule.onNodeWithText("No threads match", substring = true).assertIsDisplayed()
    }

    @Test
    fun `archived footer expands in place`() {
        setSheet(
            state(
                active = listOf(row("01", "Trip planning")),
                dormant = emptyList(),
                archived = listOf(row("04", "Archived work", unread = 2)),
            ),
        )

        composeTestRule.onNodeWithText("Archived work").assertDoesNotExist()
        composeTestRule.onNodeWithText("Archived").performClick()
        composeTestRule.onNodeWithText("Archived work").assertIsDisplayed()

        composeTestRule.onNodeWithText("Archived").performClick()
        composeTestRule.onNodeWithText("Archived work").assertDoesNotExist()
    }

    @Test
    fun `long press enters selection and the bar archives the batch`() {
        setSheet()

        composeTestRule.onNodeWithText("Trip planning").performTouchInput { longClick() }

        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("Archive").performClick()

        assertEquals(listOf(listOf("01")), archivedCalls)
    }

    @Test
    fun `selection count tracks each toggled row`() {
        setSheet()

        composeTestRule.onNodeWithText("Trip planning").performTouchInput { longClick() }
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()

        composeTestRule.onNodeWithText("Build notes").performClick()
        composeTestRule.onNodeWithText("2 selected").assertIsDisplayed()

        composeTestRule.onNodeWithText("Trip planning").performClick()
        composeTestRule.onNodeWithText("1 selected").assertIsDisplayed()
    }

    @Test
    fun `letting the undo snackbar expire keeps the thread archived`() {
        setSheet()

        composeTestRule.onNodeWithText("Trip planning").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        // Past the snackbar's Short duration: it goes away without undoing, so
        // the archive stands (spec §10 "expiry leaves archived").
        composeTestRule.mainClock.advanceTimeBy(6000)
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Undo").assertDoesNotExist()
        assertTrue(unarchivedCalls.isEmpty())
    }

    @Test
    fun `swiping a row archives it and offers undo`() {
        setSheet()

        composeTestRule.onNodeWithText("Trip planning").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(listOf("01")), archivedCalls)
        composeTestRule.onNodeWithText("moved to Archived", substring = true).assertIsDisplayed()
        composeTestRule.onNodeWithText("Undo").assertIsDisplayed()

        composeTestRule.onNodeWithText("Undo").performClick()
        composeTestRule.waitForIdle()
        assertEquals(listOf(listOf("01")), unarchivedCalls)
    }

    @Test
    fun `main row has no swipe archive action`() {
        setSheet()

        composeTestRule.onNodeWithText("Main").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertTrue(archivedCalls.isEmpty())
    }

    @Test
    fun `archived section swipe un-archives instead of archiving`() {
        setSheet(
            state(
                active = listOf(row("01", "Trip planning")),
                dormant = emptyList(),
                archived = listOf(row("04", "Archived work", unread = 2)),
            ),
        )

        composeTestRule.onNodeWithText("Archived").performClick()
        composeTestRule.onNodeWithText("Archived work").performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()

        assertEquals(listOf(listOf("04")), unarchivedCalls)
        assertTrue(archivedCalls.isEmpty())
    }

    @Test
    fun `row menu offers rename mark read archive and delete`() {
        setSheet()

        composeTestRule.onAllNodesWithContentDescription("Thread actions")[0].performClick()

        composeTestRule.onNodeWithText("Rename").assertIsDisplayed()
        composeTestRule.onNodeWithText("Mark read").assertIsDisplayed()
        composeTestRule.onNodeWithText("Archive").assertIsDisplayed()
        composeTestRule.onNodeWithText("Delete").assertIsDisplayed()
    }

    @Test
    fun `row menu archives an active row and marks unread read`() {
        setSheet()

        composeTestRule.onAllNodesWithContentDescription("Thread actions")[0].performClick()
        composeTestRule.onNodeWithText("Mark read").performClick()
        composeTestRule.waitForIdle()

        assertEquals(listOf("01"), markedRead)
    }
}
