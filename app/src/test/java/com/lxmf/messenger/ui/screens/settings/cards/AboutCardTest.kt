package com.lxmf.messenger.ui.screens.settings.cards

import android.app.Application
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.util.SystemInfo
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
                )
            }
        }

        composeTestRule.onNodeWithText("Copy System Info").assertExists()
    }

    // Note: Button click tests are skipped in Robolectric due to framework limitations
    // with Compose UI interactions. These should be tested with instrumented tests.

    @Test
    fun `renders without crashing with full system info`() {
        composeTestRule.setContent {
            ColumbaTheme {
                AboutCard(
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = minimalSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = infoWithNullVersions,
                    onCopySystemInfo = {},
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
                    systemInfo = infoWithNullIdentity,
                    onCopySystemInfo = {},
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
                    systemInfo = customVersionInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = edgeCaseInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = minimalSystemInfo,
                    onCopySystemInfo = {},
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
                    systemInfo = fullSystemInfo,
                    onCopySystemInfo = { callbackInvoked = true },
                )
            }
        }

        // Wait for composition to settle
        composeTestRule.waitForIdle()

        // Callback should not be invoked during initial render
        assert(!callbackInvoked)
    }
}
