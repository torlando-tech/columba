package network.columba.app.ui.screens.settings.cards

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ApplicationProvider
import network.columba.app.R
import network.columba.app.service.AppUpdateResult
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.ui.theme.ColumbaTheme
import network.columba.app.util.SystemInfo
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AboutCardTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    private val fullSystemInfo =
        SystemInfo(
            appVersion = "3.0.7",
            appBuildCode = 30007,
            buildType = "debug",
            gitCommitHash = "abc1234",
            buildDate = "2025-01-16 10:30",
            androidVersion = "14",
            apiLevel = 34,
            deviceModel = "Pixel 7",
            manufacturer = "Google",
            identityHash = "a1b2c3d4e5f6",
            reticulumVersion = "1.0.4",
            lxmfVersion = "0.9.2",
            bleReticulumVersion = "0.2.2",
        )

    private val minimalSystemInfo =
        SystemInfo(
            appVersion = "3.0.1",
            appBuildCode = 30001,
            buildType = "release",
            gitCommitHash = "xyz9999",
            buildDate = "2025-01-15 09:00",
            androidVersion = "13",
            apiLevel = 33,
            deviceModel = "Samsung S21",
            manufacturer = "Samsung",
            identityHash = null,
            reticulumVersion = null,
            lxmfVersion = null,
            bleReticulumVersion = null,
        )

    @Test
    fun `displays Columba logo`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("Columba Logo").assertExists()
    }

    @Test
    fun `displays app name`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Columba").assertExists()
    }

    @Test
    fun `displays tagline`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText(
            "Native Android messaging app using Bluetooth LE, TCP, or RNode (LoRa) over LXMF and Reticulum",
        ).assertExists()
    }

    @Test
    fun `displays app information section header`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("App Information").assertExists()
    }

    @Test
    fun `displays copy system info button`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Copy System Info").assertExists()
    }

    @Test
    fun `displays report bug button`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Report Bug").assertExists()
    }

    @Test
    fun `view license handles missing external activity without crashing`() {
        assertMissingExternalActivityHandled("View License")
    }

    @Test
    fun `resource link handles missing external activity without crashing`() {
        assertMissingExternalActivityHandled("GitHub Repository")
    }

    @Test
    fun `view release handles missing external activity without crashing`() {
        assertMissingExternalActivityHandled(
            buttonText = "View Release",
            updateCheckResult =
                AppUpdateResult.UpdateAvailable(
                    currentVersion = "3.0.7",
                    tagName = "v3.0.8",
                    htmlUrl = "https://example.invalid/release",
                    isPrerelease = false,
                ),
        )
    }

    @Test
    fun `external links handle security exception without crashing`() {
        assertMissingExternalActivityHandled(
            buttonText = "View License",
            launchFailure = SecurityException("External activities blocked"),
        )
    }

    private fun assertMissingExternalActivityHandled(
        buttonText: String,
        updateCheckResult: AppUpdateResult = AppUpdateResult.Idle,
        launchFailure: RuntimeException = ActivityNotFoundException("No browser installed"),
    ) {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        var launchAttempts = 0
        val noExternalActivityContext =
            object : ContextWrapper(baseContext) {
                override fun startActivity(intent: Intent) {
                    launchAttempts += 1
                    throw launchFailure
                }
            }

        composeTestRule.setContent {
            CompositionLocalProvider(LocalContext provides noExternalActivityContext) {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    ColumbaTheme {
                        AboutCard(
                            isExpanded = true,
                            onExpandedChange = {},
                            systemInfo = fullSystemInfo,
                            onCopySystemInfo = {},
                            onReportBug = {},
                            updateCheckResult = updateCheckResult,
                        )
                    }
                }
            }
        }

        composeTestRule
            .onNodeWithText(buttonText)
            .performScrollTo()
            .performClick()

        assertEquals(1, launchAttempts)
        assertEquals(
            baseContext.getString(R.string.error_no_app_for_link),
            ShadowToast.getTextOfLatestToast(),
        )
    }

    @Test
    fun `renders without crashing with full system info`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        // If we get here without crashing, the test passes
        composeTestRule.onNodeWithText("Columba").assertExists()
    }

    @Test
    fun `renders without crashing with minimal system info`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = minimalSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        // Should still render the basic structure
        composeTestRule.onNodeWithText("Columba").assertExists()
        composeTestRule.onNodeWithText("Copy System Info").assertExists()
    }

    @Test
    fun `renders without crashing with null protocol versions`() {
        val infoWithNullVersions =
            fullSystemInfo.copy(
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = infoWithNullVersions,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Columba").assertExists()
    }

    @Test
    fun `renders without crashing with null identity hash`() {
        val infoWithNullIdentity = fullSystemInfo.copy(identityHash = null)

        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = infoWithNullIdentity,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Columba").assertExists()
    }

    @Test
    fun `displays version number from system info`() {
        val customVersionInfo = fullSystemInfo.copy(appVersion = "9.9.9")

        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = customVersionInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("9.9.9").assertExists()
    }

    @Test
    fun `card renders with all edge cases`() {
        val edgeCaseInfo =
            SystemInfo(
                appVersion = "",
                appBuildCode = 0,
                buildType = "",
                gitCommitHash = "",
                buildDate = "",
                androidVersion = "",
                apiLevel = 0,
                deviceModel = "",
                manufacturer = "",
                identityHash = null,
                reticulumVersion = null,
                lxmfVersion = null,
                bleReticulumVersion = null,
            )

        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = edgeCaseInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        // Should still render basic structure without crashing
        composeTestRule.onNodeWithText("Columba").assertExists()
        composeTestRule.onNodeWithText("Copy System Info").assertExists()
    }

    @Test
    fun `card contains section headers`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        // Verify key section headers exist
        composeTestRule.onNodeWithText("App Information").assertExists()
        composeTestRule.onNodeWithText("Device Information").assertExists()
        composeTestRule.onNodeWithText("Protocol Versions").assertExists()
        composeTestRule.onNodeWithText("Links & Resources").assertExists()
        composeTestRule.onNodeWithText("Built With").assertExists()
    }

    @Test
    fun `card contains identity section when hash is present`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Identity").assertExists()
        composeTestRule.onNodeWithText("a1b2c3d4e5f6").assertExists()
    }

    @Test
    fun `card omits identity section when hash is null`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = minimalSystemInfo,
                    onCopySystemInfo = {},
                    onReportBug = {},
                )
            }
        }

        // Identity section should not exist
        composeTestRule.onNodeWithText("Identity Hash").assertDoesNotExist()
    }

    @Test
    fun `callback is not invoked when card is first rendered`() {
        var callbackInvoked = false

        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    isExpanded = true,
                    onExpandedChange = {},
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = { callbackInvoked = true },
                    onReportBug = {},
                )
            }
        }

        // Wait for composition to settle
        composeTestRule.waitForIdle()

        // Callback should not be invoked during initial render
        assert(!callbackInvoked)
    }
}
