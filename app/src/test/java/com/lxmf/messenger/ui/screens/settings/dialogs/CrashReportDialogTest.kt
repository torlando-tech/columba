package com.lxmf.messenger.ui.screens.settings.dialogs

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.util.CrashReport
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class CrashReportDialogTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private val testCrashReport =
        CrashReport(
            timestamp = 1705591935000L,
            exceptionClass = "java.lang.NullPointerException",
            message = "Attempt to invoke method on null object",
            stackTrace = "java.lang.NullPointerException\n\tat Test.method(Test.kt:42)",
            logsAtCrash = null,
        )

    @Test
    fun `displays crash dialog title`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("App Crashed").assertExists()
    }

    @Test
    fun `displays crash dialog description`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "The app crashed unexpectedly. Would you like to report this issue?",
        ).assertExists()
    }

    @Test
    fun `displays exception name without package`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        // Should show just the class name, not the full package
        composeTestRule.onNodeWithText("Exception: NullPointerException").assertExists()
    }

    @Test
    fun `displays truncated message when message is long`() {
        val longMessageCrash =
            testCrashReport.copy(
                message = "A".repeat(150),
            )

        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = longMessageCrash,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        // Should truncate to 100 chars + "..."
        composeTestRule.onNodeWithText("A".repeat(100) + "...").assertExists()
    }

    @Test
    fun `displays report bug button`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Report Bug").assertExists()
    }

    @Test
    fun `displays dismiss button`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Dismiss").assertExists()
    }

    @Test
    fun `displays privacy notice`() {
        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Your bug report will include system info and recent logs. " +
                "Sensitive data like identity hashes and IP addresses are automatically redacted.",
        ).assertExists()
    }

    @Test
    fun `calls onDismiss when dismiss button clicked`() {
        var dismissed = false

        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = { dismissed = true },
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Dismiss").performClick()
        assertTrue(dismissed)
    }

    @Test
    fun `calls onReportBug when report bug button clicked`() {
        var reportBugCalled = false

        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = testCrashReport,
                    onDismiss = {},
                    onReportBug = { reportBugCalled = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Report Bug").performClick()
        assertTrue(reportBugCalled)
    }

    @Test
    fun `handles crash report with null message`() {
        val crashWithNullMessage = testCrashReport.copy(message = null)

        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = crashWithNullMessage,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        // Should render without crashing
        composeTestRule.onNodeWithText("App Crashed").assertExists()
        composeTestRule.onNodeWithText("Exception: NullPointerException").assertExists()
    }

    @Test
    fun `renders with different exception types`() {
        val runtimeException =
            testCrashReport.copy(
                exceptionClass = "java.lang.RuntimeException",
                message = "Something went wrong",
            )

        composeTestRule.setContent {
            ColumbaTheme {
                CrashReportDialog(
                    crashReport = runtimeException,
                    onDismiss = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Exception: RuntimeException").assertExists()
    }
}
