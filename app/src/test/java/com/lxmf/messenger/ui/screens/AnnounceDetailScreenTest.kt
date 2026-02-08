package com.lxmf.messenger.ui.screens

import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.test.RegisterComponentActivityRule
import com.lxmf.messenger.viewmodel.AnnounceStreamViewModel
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
 * UI tests for AnnounceDetailScreen.
 * Tests the node details display including transfer size limit for propagation nodes.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AnnounceDetailScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    // ========== Transfer Size Limit Card Tests ==========

    @Test
    fun `transfer size limit card displays for propagation node with limit`() {
        val mockViewModel = mockk<AnnounceStreamViewModel>()
        val announce = createPropagationNodeAnnounce(transferLimitKb = 25600) // 25 MB

        every { mockViewModel.getAnnounceFlow(any()) } returns MutableStateFlow(announce)
        every { mockViewModel.isContactFlow(any()) } returns MutableStateFlow(false)
        every { mockViewModel.isMyRelayFlow(any()) } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnnounceDetailScreen(
                    destinationHash = "test_hash",
                    onBackClick = {},
                    onStartChat = { _, _ -> },
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Transfer Size Limit").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("25 MB").performScrollTo().assertIsDisplayed()
        composeTestRule
            .onNodeWithText("Maximum message size accepted by this relay")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `transfer size limit card displays KB for small limits`() {
        val mockViewModel = mockk<AnnounceStreamViewModel>()
        val announce = createPropagationNodeAnnounce(transferLimitKb = 256)

        every { mockViewModel.getAnnounceFlow(any()) } returns MutableStateFlow(announce)
        every { mockViewModel.isContactFlow(any()) } returns MutableStateFlow(false)
        every { mockViewModel.isMyRelayFlow(any()) } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnnounceDetailScreen(
                    destinationHash = "test_hash",
                    onBackClick = {},
                    onStartChat = { _, _ -> },
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Transfer Size Limit").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("256 KB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `transfer size limit card displays fractional MB`() {
        val mockViewModel = mockk<AnnounceStreamViewModel>()
        val announce = createPropagationNodeAnnounce(transferLimitKb = 1536) // 1.5 MB

        every { mockViewModel.getAnnounceFlow(any()) } returns MutableStateFlow(announce)
        every { mockViewModel.isContactFlow(any()) } returns MutableStateFlow(false)
        every { mockViewModel.isMyRelayFlow(any()) } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnnounceDetailScreen(
                    destinationHash = "test_hash",
                    onBackClick = {},
                    onStartChat = { _, _ -> },
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Transfer Size Limit").performScrollTo().assertIsDisplayed()
        composeTestRule.onNodeWithText("1.5 MB").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `transfer size limit card not displayed when limit is null`() {
        val mockViewModel = mockk<AnnounceStreamViewModel>()
        val announce = createPropagationNodeAnnounce(transferLimitKb = null)

        every { mockViewModel.getAnnounceFlow(any()) } returns MutableStateFlow(announce)
        every { mockViewModel.isContactFlow(any()) } returns MutableStateFlow(false)
        every { mockViewModel.isMyRelayFlow(any()) } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnnounceDetailScreen(
                    destinationHash = "test_hash",
                    onBackClick = {},
                    onStartChat = { _, _ -> },
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Transfer Size Limit").assertDoesNotExist()
    }

    @Test
    fun `transfer size limit card not displayed for non-propagation nodes`() {
        val mockViewModel = mockk<AnnounceStreamViewModel>()
        val announce = createLxmfPeerAnnounce()

        every { mockViewModel.getAnnounceFlow(any()) } returns MutableStateFlow(announce)
        every { mockViewModel.isContactFlow(any()) } returns MutableStateFlow(false)
        every { mockViewModel.isMyRelayFlow(any()) } returns MutableStateFlow(false)

        composeTestRule.setContent {
            MaterialTheme {
                AnnounceDetailScreen(
                    destinationHash = "test_hash",
                    onBackClick = {},
                    onStartChat = { _, _ -> },
                    viewModel = mockViewModel,
                )
            }
        }

        composeTestRule.onNodeWithText("Transfer Size Limit").assertDoesNotExist()
    }

    // ========== Helper Functions ==========

    private fun createPropagationNodeAnnounce(transferLimitKb: Int? = 256): Announce =
        Announce(
            destinationHash = "0102030405060708090a0b0c0d0e0f10",
            peerName = "Test Propagation Node",
            publicKey = ByteArray(32) { it.toByte() },
            appData = null,
            hops = 2,
            lastSeenTimestamp = System.currentTimeMillis(),
            nodeType = "PROPAGATION_NODE",
            receivingInterface = "TCP",
            receivingInterfaceType = "TCP",
            aspect = "lxmf.propagation",
            isFavorite = false,
            stampCost = 16,
            stampCostFlexibility = 2,
            peeringCost = null,
            propagationTransferLimitKb = transferLimitKb,
        )

    private fun createLxmfPeerAnnounce(): Announce =
        Announce(
            destinationHash = "0102030405060708090a0b0c0d0e0f10",
            peerName = "Test Peer",
            publicKey = ByteArray(32) { it.toByte() },
            appData = null,
            hops = 1,
            lastSeenTimestamp = System.currentTimeMillis(),
            nodeType = "LXMF_PEER",
            receivingInterface = "BLE",
            receivingInterfaceType = "BLE",
            aspect = "lxmf.delivery",
            isFavorite = false,
            stampCost = null,
            stampCostFlexibility = null,
            peeringCost = null,
            propagationTransferLimitKb = null,
        )
}
