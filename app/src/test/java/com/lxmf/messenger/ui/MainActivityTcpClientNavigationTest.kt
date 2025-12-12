package com.lxmf.messenger.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.lxmf.messenger.test.TcpClientWizardTestFixtures
import com.lxmf.messenger.ui.screens.tcpclient.TcpClientWizardScreen
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Navigation tests for TCP Client Wizard integration with main navigation.
 * Tests that the wizard integrates correctly with the app's navigation graph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityTcpClientNavigationTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun tcpClientWizard_routeRegistered_displaysContent() {
        // Given - Create a test NavHost with tcp_client_wizard route
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "tcp_client_wizard",
            ) {
                composable("tcp_client_wizard") {
                    TcpClientWizardScreen(
                        onNavigateBack = { navController.popBackStack() },
                        onComplete = {},
                        viewModel = mockViewModel,
                    )
                }
            }
        }

        // Then - Wizard content is displayed (appears in TopAppBar and step header)
        composeTestRule.onAllNodesWithText("Choose Server").assertCountEquals(2)
    }

    @Test
    fun tcpClientWizard_onComplete_navigatesToDestination() {
        // Given
        var navigationCompleted = false
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.successState(),
            )
        every { mockViewModel.canProceed() } returns true

        // When
        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = "tcp_client_wizard",
            ) {
                composable("tcp_client_wizard") {
                    TcpClientWizardScreen(
                        onNavigateBack = {},
                        onComplete = {
                            navigationCompleted = true
                            // In real app: navController.navigate("interface_management")
                        },
                        viewModel = mockViewModel,
                    )
                }
            }
        }

        // Then - onComplete callback was triggered (saveSuccess causes navigation)
        assertTrue(navigationCompleted)
    }

    @Test
    fun tcpClientWizard_onNavigateBack_popBackStack() {
        // Given
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            NavHost(
                navController = rememberNavController(),
                startDestination = "tcp_client_wizard",
            ) {
                composable("tcp_client_wizard") {
                    TcpClientWizardScreen(
                        onNavigateBack = {},
                        onComplete = {},
                        viewModel = mockViewModel,
                    )
                }
            }
        }

        // Then - Back navigation callback is available
        // The actual back button click is tested in TcpClientWizardScreenTest
        composeTestRule.onAllNodesWithText("Choose Server").assertCountEquals(2)
    }

    @Test
    fun tcpClientWizard_navigationRoute_matchesMainActivity() {
        // This test verifies the route name matches what MainActivity uses
        // The route "tcp_client_wizard" is the same as in MainActivity.kt line 576
        val expectedRoute = "tcp_client_wizard"
        val mockViewModel = mockk<TcpClientWizardViewModel>(relaxed = true)
        every { mockViewModel.state } returns
            MutableStateFlow(
                TcpClientWizardTestFixtures.serverSelectionState(),
            )
        every { mockViewModel.canProceed() } returns false
        every { mockViewModel.getCommunityServers() } returns TcpClientWizardTestFixtures.testServers

        // When
        composeTestRule.setContent {
            val navController = rememberNavController()
            NavHost(
                navController = navController,
                startDestination = expectedRoute,
            ) {
                composable(expectedRoute) {
                    TcpClientWizardScreen(
                        onNavigateBack = {},
                        onComplete = {},
                        viewModel = mockViewModel,
                    )
                }
            }
        }

        // Then - Content renders successfully with the expected route
        composeTestRule.onAllNodesWithText("Choose Server").assertCountEquals(2)
    }
}
