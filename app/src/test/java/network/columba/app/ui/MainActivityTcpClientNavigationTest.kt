package network.columba.app.ui

import android.app.Application
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.test.TcpClientWizardTestFixtures
import network.columba.app.ui.screens.tcpclient.TcpClientWizardScreen
import network.columba.app.viewmodel.TcpClientWizardViewModel
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Rendering smoke tests for the TCP Client Wizard in a NavHost.
 *
 * Back-stack completion behavior is covered by NavigationBackStackContractTest;
 * this class intentionally does not substitute callbacks for production navigation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class MainActivityTcpClientNavigationTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun tcpClientWizard_routeRegistered_displaysContent() {
        // Given - Create a test NavHost with tcp_client_wizard route
        val mockViewModel = mockk<TcpClientWizardViewModel>()
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
}
