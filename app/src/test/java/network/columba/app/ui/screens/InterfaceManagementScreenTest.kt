package network.columba.app.ui.screens

import android.app.Application
import android.bluetooth.BluetoothAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.test.RegisterComponentActivityRule
import network.columba.app.data.database.entity.InterfaceEntity
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.ui.components.RNodeBatteryIndicator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InterfaceManagementScreenTest {
    private val registerActivityRule = RegisterComponentActivityRule()
    private val composeRule = createComposeRule()

    @get:Rule
    val ruleChain: RuleChain = RuleChain.outerRule(registerActivityRule).around(composeRule)

    val composeTestRule get() = composeRule

    @Test
    fun `RNode card exposes repair action when pairing is required`() {
        var repairRequested = false
        val rnode =
            InterfaceEntity(
                id = 42,
                name = "RNode E517 BLE",
                type = "RNode",
                configJson = """{"connection_mode":"ble","target_device_name":"RNode E517"}""",
            )

        composeTestRule.setContent {
            MaterialTheme {
                InterfaceCard(
                    interfaceEntity = rnode,
                    onToggle = {},
                    bluetoothState = BluetoothAdapter.STATE_ON,
                    blePermissionsGranted = true,
                    currentTransport = CurrentTransport.WIFI_LIKE,
                    isOnline = false,
                    statusReason = "pairing_required",
                    onRepairPairing = { repairRequested = true },
                )
            }
        }

        composeTestRule.onNodeWithText("Pairing required").assertIsDisplayed()
        composeTestRule.onNodeWithText("Repair").performClick()
        assertTrue(repairRequested)
    }

    // ========== RNode battery on interface card (follow-up to PR 1103) ==========

    @Test
    fun `RNode card shows battery percent when online with a live reading`() {
        val rnode =
            InterfaceEntity(
                id = 1,
                name = "RNode E517 BLE",
                type = "RNode",
                configJson = """{"connection_mode":"ble","target_device_name":"RNode E517"}""",
            )

        composeTestRule.setContent {
            MaterialTheme {
                InterfaceCard(
                    interfaceEntity = rnode,
                    onToggle = {},
                    bluetoothState = BluetoothAdapter.STATE_ON,
                    blePermissionsGranted = true,
                    isOnline = true,
                    rnodeBattery = 82,
                )
            }
        }

        composeTestRule.onNodeWithText("Online").assertIsDisplayed()
        composeTestRule.onNodeWithText("82%").assertIsDisplayed()
    }

    @Test
    fun `RNode card hides battery when online but no reading yet`() {
        val rnode =
            InterfaceEntity(
                id = 1,
                name = "RNode E517 BLE",
                type = "RNode",
                configJson = """{"connection_mode":"ble"}""",
            )

        composeTestRule.setContent {
            MaterialTheme {
                InterfaceCard(
                    interfaceEntity = rnode,
                    onToggle = {},
                    bluetoothState = BluetoothAdapter.STATE_ON,
                    blePermissionsGranted = true,
                    isOnline = true,
                    rnodeBattery = null,
                )
            }
        }

        composeTestRule.onNodeWithText("82%").assertDoesNotExist()
    }

    @Test
    fun `RNode card hides battery when offline even if a reading is present`() {
        // A reading can arrive a beat after the interface goes offline; the card
        // must not show a battery next to an "Offline" status.
        val rnode =
            InterfaceEntity(
                id = 1,
                name = "RNode E517 BLE",
                type = "RNode",
                configJson = """{"connection_mode":"ble"}""",
            )

        composeTestRule.setContent {
            MaterialTheme {
                InterfaceCard(
                    interfaceEntity = rnode,
                    onToggle = {},
                    bluetoothState = BluetoothAdapter.STATE_ON,
                    blePermissionsGranted = true,
                    isOnline = false,
                    rnodeBattery = 47,
                )
            }
        }

        composeTestRule.onNodeWithText("Offline").assertIsDisplayed()
        composeTestRule.onNodeWithText("47%").assertDoesNotExist()
    }

    @Test
    fun `non-RNode card hides battery even when a reading is present`() {
        val tcp =
            InterfaceEntity(
                id = 1,
                name = "Laptop",
                type = "TCPClient",
                configJson = """{"target_host":"10.0.0.245","target_port":4242}""",
            )

        composeTestRule.setContent {
            MaterialTheme {
                InterfaceCard(
                    interfaceEntity = tcp,
                    onToggle = {},
                    bluetoothState = BluetoothAdapter.STATE_ON,
                    blePermissionsGranted = true,
                    isOnline = true,
                    rnodeBattery = 82,
                )
            }
        }

        composeTestRule.onNodeWithText("82%").assertDoesNotExist()
    }

    @Test
    fun `RNodeBatteryIndicator renders the percent label`() {
        composeTestRule.setContent {
            MaterialTheme {
                RNodeBatteryIndicator(percent = 12)
            }
        }

        composeTestRule.onNodeWithText("12%").assertIsDisplayed()
    }

    @Test
    fun `pending restart suppresses stale runtime pairing reason only for changed interface`() {
        assertEquals(
            null,
            effectiveRuntimeStatusReason(
                statusReason = "pairing_required",
                interfaceId = 42,
                hasPendingChanges = true,
                pendingInterfaceIds = setOf(42),
            ),
        )
        assertEquals(
            "pairing_required",
            effectiveRuntimeStatusReason(
                statusReason = "pairing_required",
                interfaceId = 42,
                hasPendingChanges = true,
                pendingInterfaceIds = setOf(99),
            ),
        )
        assertEquals(
            "pairing_required",
            effectiveRuntimeStatusReason(
                statusReason = "pairing_required",
                interfaceId = 42,
                hasPendingChanges = false,
                pendingInterfaceIds = setOf(42),
            ),
        )
    }

    // ========== formatAddressWithPort Tests ==========

    @Test
    fun `formatAddressWithPort with IPv4 address does not use brackets`() {
        val result = formatAddressWithPort("192.168.1.100", 4242, isIpv6 = false)
        assertEquals("192.168.1.100:4242", result)
    }

    @Test
    fun `formatAddressWithPort with IPv6 address uses brackets`() {
        val result = formatAddressWithPort("2001:db8::1", 4242, isIpv6 = true)
        assertEquals("[2001:db8::1]:4242", result)
    }

    @Test
    fun `formatAddressWithPort with Yggdrasil address uses brackets`() {
        val result = formatAddressWithPort("200:abcd:1234::1", 4242, isIpv6 = true)
        assertEquals("[200:abcd:1234::1]:4242", result)
    }

    @Test
    fun `formatAddressWithPort detects IPv6 by colon even if isIpv6 is false`() {
        val result = formatAddressWithPort("fe80::1", 8080, isIpv6 = false)
        assertEquals("[fe80::1]:8080", result)
    }

    @Test
    fun `formatAddressWithPort with null IP returns no network`() {
        val result = formatAddressWithPort(null, 4242, isIpv6 = false)
        assertEquals("no network:4242", result)
    }

    @Test
    fun `formatAddressWithPort with custom port`() {
        val result = formatAddressWithPort("10.0.0.1", 8080, isIpv6 = false)
        assertEquals("10.0.0.1:8080", result)
    }

    @Test
    fun `formatAddressWithPort with localhost IPv4`() {
        val result = formatAddressWithPort("127.0.0.1", 3000, isIpv6 = false)
        assertEquals("127.0.0.1:3000", result)
    }

    @Test
    fun `formatAddressWithPort with all zeros bind address`() {
        val result = formatAddressWithPort("0.0.0.0", 4242, isIpv6 = false)
        assertEquals("0.0.0.0:4242", result)
    }

    // ========== getInterfaceTypeLabel Tests ==========

    @Test
    fun `getInterfaceTypeLabel returns correct label for TCPServer`() {
        val result = getInterfaceTypeLabel("TCPServer")
        assertEquals("TCP Server", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns correct label for TCPClient`() {
        val result = getInterfaceTypeLabel("TCPClient")
        assertEquals("TCP Client", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns correct label for AutoInterface`() {
        val result = getInterfaceTypeLabel("AutoInterface")
        assertEquals("Auto Discovery", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns correct label for AndroidBLE`() {
        val result = getInterfaceTypeLabel("AndroidBLE")
        assertEquals("Bluetooth LE", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns correct label for RNode`() {
        val result = getInterfaceTypeLabel("RNode")
        assertEquals("RNode LoRa", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns correct label for UDP`() {
        val result = getInterfaceTypeLabel("UDP")
        assertEquals("UDP Interface", result)
    }

    @Test
    fun `getInterfaceTypeLabel returns unknown type as-is`() {
        val result = getInterfaceTypeLabel("UnknownType")
        assertEquals("UnknownType", result)
    }

    // ========== InterfaceTypeSelector UI Tests ==========

    @Test
    fun `InterfaceTypeSelector displays title`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Select Interface Type").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeSelector displays Auto Discovery option`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Auto Discovery").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeSelector displays TCP Client option`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("TCP Client").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeSelector displays Bluetooth LE option`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Bluetooth LE").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeSelector displays RNode LoRa option`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        // RNode LoRa may be below visible area, so just check it exists
        composeTestRule.onNodeWithText("RNode LoRa").assertExists()
    }

    @Test
    fun `InterfaceTypeSelector displays Advanced section`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        // Advanced may be below visible area, so just check it exists
        composeTestRule.onNodeWithText("Advanced").assertExists()
    }

    @Test
    fun `InterfaceTypeSelector TCP Server hidden by default`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        // TCP Server should not be visible initially (collapsed)
        composeTestRule.onNodeWithText("TCP Server").assertDoesNotExist()
    }

    // Note: Tests for "TCP Server after expanding Advanced" removed due to
    // AlertDialog viewport limitations in Robolectric. The TCPServer functionality
    // is tested via the InterfaceTypeOption component tests and manual testing.

    @Test
    fun `InterfaceTypeSelector calls onTypeSelected with AutoInterface`() {
        var selectedType: String? = null

        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = { selectedType = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Auto Discovery").performClick()

        assertEquals("AutoInterface", selectedType)
    }

    @Test
    fun `InterfaceTypeSelector calls onTypeSelected with TCPClient`() {
        var selectedType: String? = null

        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = { selectedType = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("TCP Client").performClick()

        assertEquals("TCPClient", selectedType)
    }

    @Test
    fun `InterfaceTypeSelector calls onTypeSelected with AndroidBLE`() {
        var selectedType: String? = null

        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = { selectedType = it },
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Bluetooth LE").performClick()

        assertEquals("AndroidBLE", selectedType)
    }

    // Note: Test for "calls onTypeSelected with RNode" removed due to
    // AlertDialog viewport limitations in Robolectric. RNode LoRa is the 4th item
    // and doesn't receive clicks reliably. Functionality tested via InterfaceTypeOption tests.

    @Test
    fun `InterfaceTypeSelector displays Cancel button`() {
        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = {},
            )
        }

        composeTestRule.onNodeWithText("Cancel").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeSelector Cancel button calls onDismiss`() {
        var dismissed = false

        composeTestRule.setContent {
            InterfaceTypeSelector(
                onTypeSelected = {},
                onDismiss = { dismissed = true },
            )
        }

        composeTestRule.onNodeWithText("Cancel").performClick()

        assertTrue(dismissed)
    }

    // ========== InterfaceTypeOption UI Tests ==========

    @Test
    fun `InterfaceTypeOption displays title`() {
        composeTestRule.setContent {
            InterfaceTypeOption(
                title = "Test Interface",
                description = "Test description",
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("Test Interface").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeOption displays description`() {
        composeTestRule.setContent {
            InterfaceTypeOption(
                title = "Test Interface",
                description = "This is a test description",
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("This is a test description").assertIsDisplayed()
    }

    @Test
    fun `InterfaceTypeOption calls onClick when clicked`() {
        var clicked = false

        composeTestRule.setContent {
            InterfaceTypeOption(
                title = "Test Interface",
                description = "Test description",
                onClick = { clicked = true },
            )
        }

        composeTestRule.onNodeWithText("Test Interface").performClick()

        assertTrue(clicked)
    }

    // ========== DiscoveredInterfacesSummaryCard UI Tests ==========

    @Test
    fun `DiscoveredInterfacesSummaryCard displays title`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 0,
                availableCount = 0,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = false,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("Interface Discovery").assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows disabled message when discovery disabled`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 0,
                availableCount = 0,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = false,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("Tap to configure RNS 1.1.x interface discovery")
            .assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows no interfaces message when enabled but empty`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 0,
                availableCount = 0,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("Discovery enabled - no interfaces found yet")
            .assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows total count when interfaces found`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 5,
                availableCount = 3,
                unknownCount = 1,
                staleCount = 1,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("5 interfaces found via RNS Discovery")
            .assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows available status badge`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 3,
                availableCount = 3,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("3 available").assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows unknown status badge`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 2,
                availableCount = 0,
                unknownCount = 2,
                staleCount = 0,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("2 unknown").assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows stale status badge`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 1,
                availableCount = 0,
                unknownCount = 0,
                staleCount = 1,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("1 stale").assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard shows all status badges`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 6,
                availableCount = 3,
                unknownCount = 2,
                staleCount = 1,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("3 available").assertIsDisplayed()
        composeTestRule.onNodeWithText("2 unknown").assertIsDisplayed()
        composeTestRule.onNodeWithText("1 stale").assertIsDisplayed()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard hides zero count badges`() {
        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 3,
                availableCount = 3,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = true,
                onClick = {},
            )
        }

        composeTestRule.onNodeWithText("3 available").assertIsDisplayed()
        composeTestRule.onNodeWithText("0 unknown").assertDoesNotExist()
        composeTestRule.onNodeWithText("0 stale").assertDoesNotExist()
    }

    @Test
    fun `DiscoveredInterfacesSummaryCard calls onClick when clicked`() {
        var clicked = false

        composeTestRule.setContent {
            DiscoveredInterfacesSummaryCard(
                totalCount = 0,
                availableCount = 0,
                unknownCount = 0,
                staleCount = 0,
                isDiscoveryEnabled = false,
                onClick = { clicked = true },
            )
        }

        composeTestRule.onNodeWithText("Interface Discovery").performClick()

        assertTrue(clicked)
    }
}
