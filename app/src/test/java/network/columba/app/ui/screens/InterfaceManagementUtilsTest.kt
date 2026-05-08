package network.columba.app.ui.screens

import io.mockk.mockk
import network.columba.app.data.database.entity.InterfaceEntity
import network.columba.app.repository.InterfaceRepository
import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.rns.host.manager.CurrentTransport
import network.columba.app.rns.host.manager.ridesOnIpCarrier
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for the per-card network-restriction view helper. Each case asserts an
 * exact `InterfaceRestrictionView` value rather than membership/non-null so behaviour
 * change is caught precisely rather than masked by a looser assertion.
 */
class InterfaceManagementUtilsTest {
    // region restrictionView — AutoInterface (defaults to WIFI_ONLY when not specified)

    @Test
    fun restrictionView_autoInterface_wifiOnly_onCellular_returnsBlockedWifiOnly() {
        val entity = autoInterface(restrictionJsonValue = "wifi_only")
        val result = entity.restrictionView(CurrentTransport.CELLULAR)
        assertEquals(InterfaceRestrictionView.Blocked(NetworkRestriction.WIFI_ONLY), result)
    }

    @Test
    fun restrictionView_autoInterface_wifiOnly_onWifiLike_returnsAllowedWifiOnly() {
        val entity = autoInterface(restrictionJsonValue = "wifi_only")
        val result = entity.restrictionView(CurrentTransport.WIFI_LIKE)
        assertEquals(InterfaceRestrictionView.Allowed(NetworkRestriction.WIFI_ONLY), result)
    }

    // region restrictionView — TCPClient

    @Test
    fun restrictionView_tcpClient_cellularOnly_onWifiLike_returnsBlockedCellularOnly() {
        val entity = tcpClient(restrictionJsonValue = "cellular_only")
        val result = entity.restrictionView(CurrentTransport.WIFI_LIKE)
        assertEquals(InterfaceRestrictionView.Blocked(NetworkRestriction.CELLULAR_ONLY), result)
    }

    @Test
    fun restrictionView_tcpClient_any_onAnyTransport_returnsNotApplicable() {
        val entity = tcpClient(restrictionJsonValue = "any")
        // ANY → no chip rendered regardless of current transport.
        assertEquals(
            InterfaceRestrictionView.NotApplicable,
            entity.restrictionView(CurrentTransport.WIFI_LIKE),
        )
        assertEquals(
            InterfaceRestrictionView.NotApplicable,
            entity.restrictionView(CurrentTransport.CELLULAR),
        )
        assertEquals(
            InterfaceRestrictionView.NotApplicable,
            entity.restrictionView(CurrentTransport.NONE),
        )
    }

    // region restrictionView — Non-IP bypass

    @Test
    fun restrictionView_androidBle_wifiOnly_onCellular_returnsNotApplicable() {
        // Non-IP carriers ignore the restriction entirely — the chip should never render.
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "ble",
                type = "AndroidBLE",
                enabled = true,
                configJson = """{"network_restriction":"wifi_only"}""",
            )
        val result = entity.restrictionView(CurrentTransport.CELLULAR)
        assertEquals(InterfaceRestrictionView.NotApplicable, result)
    }

    @Test
    fun restrictionView_rnodeBleMode_wifiOnly_onCellular_returnsNotApplicable() {
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "rnode-ble",
                type = "RNode",
                enabled = true,
                configJson = """{"connection_mode":"ble","network_restriction":"wifi_only"}""",
            )
        val result = entity.restrictionView(CurrentTransport.CELLULAR)
        assertEquals(InterfaceRestrictionView.NotApplicable, result)
    }

    @Test
    fun restrictionView_rnodeTcpMode_cellularOnly_onWifiLike_returnsBlockedCellularOnly() {
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "rnode-tcp",
                type = "RNode",
                enabled = true,
                configJson = """{"connection_mode":"tcp","network_restriction":"cellular_only"}""",
            )
        val result = entity.restrictionView(CurrentTransport.WIFI_LIKE)
        assertEquals(InterfaceRestrictionView.Blocked(NetworkRestriction.CELLULAR_ONLY), result)
    }

    // region restrictionView — NoNetwork

    @Test
    fun restrictionView_autoInterface_onNoNetwork_returnsNoNetworkWifiOnly() {
        val entity = autoInterface(restrictionJsonValue = "wifi_only")
        val result = entity.restrictionView(CurrentTransport.NONE)
        assertEquals(InterfaceRestrictionView.NoNetwork(NetworkRestriction.WIFI_ONLY), result)
    }

    @Test
    fun restrictionView_tcpClient_cellularOnly_onNoNetwork_returnsNoNetwork() {
        val entity = tcpClient(restrictionJsonValue = "cellular_only")
        val result = entity.restrictionView(CurrentTransport.NONE)
        assertEquals(InterfaceRestrictionView.NoNetwork(NetworkRestriction.CELLULAR_ONLY), result)
    }

    @Test
    fun restrictionView_androidBle_onNoNetwork_returnsNotApplicable() {
        // BLE doesn't ride IP — no network is irrelevant for it.
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "ble",
                type = "AndroidBLE",
                enabled = true,
                configJson = """{}""",
            )
        val result = entity.restrictionView(CurrentTransport.NONE)
        assertEquals(InterfaceRestrictionView.NotApplicable, result)
    }

    // region restrictionView — Default-fallback parity with InterfaceRepository.parseRestriction

    @Test
    fun restrictionView_legacyJsonWithoutRestriction_autoInterface_defaultsToWifiOnly() {
        // Legacy AutoInterface row (saved before network_restriction existed) must default
        // to WIFI_ONLY — same as InterfaceRepository.parseRestriction's defaultForType.
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "auto",
                type = "AutoInterface",
                enabled = true,
                configJson = """{"group_id":"test"}""",
            )
        val result = entity.restrictionView(CurrentTransport.CELLULAR)
        assertEquals(InterfaceRestrictionView.Blocked(NetworkRestriction.WIFI_ONLY), result)
    }

    @Test
    fun restrictionView_legacyJsonWithoutRestriction_tcpClient_defaultsToAny() {
        // Legacy TCPClient defaults to ANY — chip should not render.
        val entity =
            InterfaceEntity(
                id = 1L,
                name = "tcp",
                type = "TCPClient",
                enabled = true,
                configJson = """{"target_host":"10.0.0.1","target_port":4242}""",
            )
        val result = entity.restrictionView(CurrentTransport.CELLULAR)
        assertEquals(InterfaceRestrictionView.NotApplicable, result)
    }

    // region Drift-prevention pin: UI predicate vs runtime filter predicate

    @Test
    fun entityRidesOnIpCarrier_truthTable_matchesInterfaceTransportFilter() {
        // For each (type, connection_mode) tuple the runtime filter knows about, the UI's
        // mirror predicate must return the same boolean. If a new interface type is added,
        // both predicates need updating — this test fails first to enforce alignment.
        ridesOnIpCarrierCases().forEach { case ->
            val entity =
                InterfaceEntity(
                    id = 1L,
                    name = case.config.name,
                    type = case.type,
                    enabled = true,
                    configJson =
                        if (case.connectionMode != null) {
                            """{"connection_mode":"${case.connectionMode}"}"""
                        } else {
                            """{}"""
                        },
                )
            assertEquals(
                "predicate drift for type=${case.type} connectionMode=${case.connectionMode}",
                case.config.ridesOnIpCarrier(),
                entityRidesOnIpCarrier(entity),
            )
        }
    }

    // NoRelaxedMocks is suppressed deliberately: the InterfaceDao is a construction-only
    // dependency of InterfaceRepository (its flow properties are never collected here) and
    // is not the subject of any assertion — the test asserts on entityToConfig()'s real
    // return value. Stubbing the DAO's construction-time calls explicitly would couple this
    // drift pin to InterfaceRepository's unrelated construction surface.
    @Suppress("NoRelaxedMocks")
    @Test
    fun parseRestrictionForEntity_legacyDefaults_matchInterfaceRepositoryRuntimeDefault() {
        // Legacy rows (saved before the network_restriction column existed) have no
        // restriction in their JSON, so both the UI mirror and the runtime parser fall back
        // to a type-specific default. parseRestrictionForEntity duplicates that per-type
        // default; this pins it against the runtime source of truth (InterfaceRepository.
        // entityToConfig). Without this, changing a default in InterfaceRepository would
        // silently make the chip claim a restriction the runtime filter isn't applying.
        val repo = InterfaceRepository(mockk(relaxed = true))
        legacyEntitiesWithoutRestriction().forEach { entity ->
            assertEquals(
                "restriction-default drift for type=${entity.type}",
                repo.entityToConfig(entity).networkRestriction,
                parseRestrictionForEntity(entity),
            )
        }
    }

    // Minimal, otherwise-valid entities with NO `network_restriction` field, one per
    // interface type, so `entityToConfig` parses cleanly and exercises its default branch.
    private fun legacyEntitiesWithoutRestriction(): List<InterfaceEntity> =
        listOf(
            legacyEntity("AutoInterface", "{}"),
            legacyEntity("TCPClient", """{"target_host":"10.0.0.1","target_port":4242}"""),
            legacyEntity("TCPServer", """{"listen_ip":"0.0.0.0","listen_port":4242}"""),
            legacyEntity("UDP", "{}"),
            legacyEntity("AndroidBLE", "{}"),
            legacyEntity("RNode", """{"connection_mode":"classic","target_device_name":"RNode 1"}"""),
        )

    private fun legacyEntity(
        type: String,
        configJson: String,
    ): InterfaceEntity =
        InterfaceEntity(id = 1L, name = "legacy-$type", type = type, enabled = true, configJson = configJson)

    private data class RidesOnIpCarrierCase(
        val type: String,
        val connectionMode: String?,
        val config: InterfaceConfig,
    )

    private fun ridesOnIpCarrierCases(): List<RidesOnIpCarrierCase> =
        listOf(
            RidesOnIpCarrierCase(
                type = "AutoInterface",
                connectionMode = null,
                config = InterfaceConfig.AutoInterface(name = "a"),
            ),
            RidesOnIpCarrierCase(
                type = "TCPClient",
                connectionMode = null,
                config = InterfaceConfig.TCPClient(name = "t", targetHost = "10.0.0.1", targetPort = 4242),
            ),
            RidesOnIpCarrierCase(
                type = "TCPServer",
                connectionMode = null,
                config = InterfaceConfig.TCPServer(name = "s", listenIp = "0.0.0.0", listenPort = 4242),
            ),
            RidesOnIpCarrierCase(
                type = "UDP",
                connectionMode = null,
                config = InterfaceConfig.UDP(name = "u"),
            ),
            RidesOnIpCarrierCase(
                type = "AndroidBLE",
                connectionMode = null,
                config = InterfaceConfig.AndroidBLE(name = "b"),
            ),
            RidesOnIpCarrierCase(
                type = "RNode",
                connectionMode = "tcp",
                config =
                    InterfaceConfig.RNode(
                        name = "r-tcp",
                        targetDeviceName = "",
                        connectionMode = "tcp",
                        tcpHost = "10.0.0.5",
                        tcpPort = 7633,
                    ),
            ),
            RidesOnIpCarrierCase(
                type = "RNode",
                connectionMode = "ble",
                config =
                    InterfaceConfig.RNode(
                        name = "r-ble",
                        targetDeviceName = "RNode 1",
                        connectionMode = "ble",
                    ),
            ),
            RidesOnIpCarrierCase(
                type = "RNode",
                connectionMode = "classic",
                config =
                    InterfaceConfig.RNode(
                        name = "r-classic",
                        targetDeviceName = "RNode 1",
                        connectionMode = "classic",
                    ),
            ),
            RidesOnIpCarrierCase(
                type = "RNode",
                connectionMode = "usb",
                config =
                    InterfaceConfig.RNode(
                        name = "r-usb",
                        targetDeviceName = "",
                        connectionMode = "usb",
                        usbDeviceId = 42,
                    ),
            ),
        )

    // region helpers

    private fun autoInterface(restrictionJsonValue: String): InterfaceEntity =
        InterfaceEntity(
            id = 1L,
            name = "auto",
            type = "AutoInterface",
            enabled = true,
            configJson = """{"network_restriction":"$restrictionJsonValue"}""",
        )

    private fun tcpClient(restrictionJsonValue: String): InterfaceEntity =
        InterfaceEntity(
            id = 1L,
            name = "tcp",
            type = "TCPClient",
            enabled = true,
            configJson = """{"target_host":"10.0.0.1","target_port":4242,"network_restriction":"$restrictionJsonValue"}""",
        )
}
