package network.columba.app.ui.components

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import network.columba.app.viewmodel.InterfaceConfigState
import network.columba.app.viewmodel.UiMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Text goldens for the interface configuration dialog and its public
 * sub-composables: type selector, per-type field groups (AutoInterface,
 * TCP client/server, RNode, Android BLE), and the scope / mode / network
 * restriction selectors.
 *
 * Part of the issue #24 render-level i18n proof; see [TextGoldens].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InterfaceConfigTextGoldenTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun setUp() {
        TextGoldens.freezeClock()
    }

    // ========== InterfaceConfigDialog ==========

    @Test
    fun `interface config dialog add auto interface`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigDialog(
                    configState = AUTO_STATE,
                    isEditing = false,
                    onDismiss = {},
                    onSave = {},
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_dialog_add_auto", composeRule)
    }

    @Test
    fun `interface config dialog edit tcp client advanced`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigDialog(
                    configState = TCP_CLIENT_STATE,
                    isEditing = true,
                    onDismiss = {},
                    onSave = {},
                    onConfigUpdate = {},
                )
            }
        }
        composeRule.onNodeWithText("Advanced Options").performClick()
        TextGoldens.assertGolden("interface_config_dialog_edit_tcp_client_advanced", composeRule)
    }

    @Test
    fun `interface config dialog add tcp server advanced`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigDialog(
                    configState = TCP_SERVER_STATE,
                    isEditing = false,
                    onDismiss = {},
                    onSave = {},
                    onConfigUpdate = {},
                )
            }
        }
        composeRule.onNodeWithText("Advanced Options").performClick()
        TextGoldens.assertGolden("interface_config_dialog_add_tcp_server_advanced", composeRule)
    }

    @Test
    fun `interface config dialog edit android ble advanced`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceConfigDialog(
                    configState = BLE_STATE,
                    isEditing = true,
                    onDismiss = {},
                    onSave = {},
                    onConfigUpdate = {},
                )
            }
        }
        composeRule.onNodeWithText("Advanced Options").performClick()
        TextGoldens.assertGolden("interface_config_dialog_edit_android_ble_advanced", composeRule)
    }

    // ========== InterfaceTypeSelector ==========

    @Test
    fun `interface type selector enabled`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceTypeSelector(selectedType = "AutoInterface", enabled = true, onTypeChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_type_selector_enabled", composeRule)
    }

    @Test
    fun `interface type selector disabled`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceTypeSelector(selectedType = "TCPClient", enabled = false, onTypeChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_type_selector_disabled", composeRule)
    }

    // ========== AutoInterfaceFields ==========

    @Test
    fun `auto interface fields with port errors`() {
        composeRule.setContent {
            MaterialTheme {
                AutoInterfaceFields(
                    configState = AUTO_STATE.copy(
                        discoveryPortError = UiMessage.Raw("Port must be between 1 and 65535"),
                        dataPortError = UiMessage.Raw("Port must be between 1 and 65535"),
                    ),
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_auto_interface_fields_errors", composeRule)
    }

    // ========== TCPClientFields ==========

    @Test
    fun `tcp client fields proxy disabled`() {
        composeRule.setContent {
            MaterialTheme {
                TCPClientFields(configState = TCP_CLIENT_STATE, onConfigUpdate = {})
            }
        }
        TextGoldens.assertGolden("interface_config_tcp_client_fields", composeRule)
    }

    @Test
    fun `tcp client fields proxy enabled`() {
        composeRule.setContent {
            MaterialTheme {
                TCPClientFields(
                    configState = TCP_CLIENT_STATE.copy(
                        socksProxyEnabled = true,
                        socksProxyHost = "127.0.0.1",
                        socksProxyPort = "9050",
                    ),
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_tcp_client_fields_proxy_enabled", composeRule)
    }

    // ========== TCPServerFields ==========

    @Test
    fun `tcp server fields with validation errors`() {
        composeRule.setContent {
            MaterialTheme {
                TCPServerFields(
                    configState = TCP_SERVER_STATE.copy(
                        listenIpError = UiMessage.Raw("Invalid IP address"),
                        listenPortError = UiMessage.Raw("Port must be between 1 and 65535"),
                    ),
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_tcp_server_fields_errors", composeRule)
    }

    // ========== RNodeFields ==========

    @Test
    fun `rnode fields with visible passphrase`() {
        composeRule.setContent {
            MaterialTheme {
                RNodeFields(
                    configState = InterfaceConfigState(
                        name = "RNode KISS",
                        type = "RNode",
                        networkName = "rnode-ifac",
                        passphrase = "radio-pass",
                        passphraseVisible = true,
                    ),
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_rnode_fields", composeRule)
    }

    // ========== AndroidBLEFields ==========

    @Test
    fun `android ble fields balanced preset`() {
        composeRule.setContent {
            MaterialTheme {
                AndroidBLEFields(configState = BLE_STATE, onConfigUpdate = {})
            }
        }
        TextGoldens.assertGolden("interface_config_android_ble_fields_balanced", composeRule)
    }

    @Test
    fun `android ble fields custom preset with duty cycle warning`() {
        composeRule.setContent {
            MaterialTheme {
                AndroidBLEFields(
                    configState = BLE_STATE.copy(
                        blePowerPreset = "custom",
                        bleDiscoveryIntervalMs = "5000",
                        bleDiscoveryIntervalIdleMs = "30000",
                        bleScanDurationMs = "10000",
                        bleAdvertisingRefreshIntervalMs = "60000",
                    ),
                    onConfigUpdate = {},
                )
            }
        }
        TextGoldens.assertGolden("interface_config_android_ble_fields_custom", composeRule)
    }

    // ========== DiscoveryScopeSelector ==========

    @Test
    fun `discovery scope selector site`() {
        composeRule.setContent {
            MaterialTheme {
                DiscoveryScopeSelector(selectedScope = "site", onScopeChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_discovery_scope_selector_site", composeRule)
    }

    // ========== InterfaceModeSelector ==========

    @Test
    fun `interface mode selector gateway`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceModeSelector(selectedMode = "gateway", onModeChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_mode_selector_gateway", composeRule)
    }

    @Test
    fun `interface mode selector unknown imported mode`() {
        composeRule.setContent {
            MaterialTheme {
                InterfaceModeSelector(selectedMode = "imported-mode", onModeChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_mode_selector_unknown_mode", composeRule)
    }

    // ========== NetworkRestrictionSelector ==========

    @Test
    fun `network restriction selector wifi only`() {
        composeRule.setContent {
            MaterialTheme {
                NetworkRestrictionSelector(selectedRestriction = "wifi_only", onRestrictionChange = {})
            }
        }
        TextGoldens.assertGolden("interface_config_network_restriction_selector_wifi_only", composeRule)
    }

    private companion object {
        val AUTO_STATE =
            InterfaceConfigState(
                name = "Home Auto",
                type = "AutoInterface",
                enabled = true,
                groupId = "columba-net",
                discoveryScope = "site",
                discoveryPort = "29716",
                dataPort = "42671",
                mode = "roaming",
            )

        val TCP_CLIENT_STATE =
            InterfaceConfigState(
                name = "Laptop TCP",
                type = "TCPClient",
                enabled = true,
                targetHost = "tcp.example.net",
                targetPort = "4242",
                networkName = "ifac-net",
                passphrase = "s3cret-pass",
                passphraseVisible = false,
                mode = "gateway",
                networkRestriction = "any",
            )

        val TCP_SERVER_STATE =
            InterfaceConfigState(
                name = "Hub Server",
                type = "TCPServer",
                enabled = true,
                listenIp = "0.0.0.0",
                listenPort = "4242",
                mode = "full",
            )

        val BLE_STATE =
            InterfaceConfigState(
                name = "BLE Radio",
                type = "AndroidBLE",
                enabled = true,
                deviceName = "COLMB",
                maxConnections = "7",
                blePowerPreset = "balanced",
                bleDiscoveryIntervalMs = "5000",
                bleDiscoveryIntervalIdleMs = "30000",
                bleScanDurationMs = "10000",
                bleAdvertisingRefreshIntervalMs = "60000",
                mode = "roaming",
            )
    }
}
