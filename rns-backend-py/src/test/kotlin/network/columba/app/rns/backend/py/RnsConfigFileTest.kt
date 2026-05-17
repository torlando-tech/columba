package network.columba.app.rns.backend.py

import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RnsConfigFileTest {
    private val auto = InterfaceConfig.AutoInterface()
    private val tcp = InterfaceConfig.TCPClient(targetHost = "amsterdam.connect.reticulum.network", targetPort = 4965)

    private fun cfg(interfaces: List<InterfaceConfig> = listOf(auto, tcp)) =
        ReticulumConfig(
            storagePath = "/tmp/test",
            enabledInterfaces = interfaces,
        )

    @Test
    fun `own-instance render sets share_instance no and includes interfaces`() {
        val out = RnsConfigFile.build(cfg())
        assertTrue(out.contains("share_instance = No"))
        assertTrue(out.contains("[interfaces]"))
        assertTrue(out.contains("type = AutoInterface"))
        assertTrue(out.contains("enabled = yes"))
        assertTrue(out.contains("type = TCPClientInterface"))
    }

    @Test
    fun `shared-client render sets share_instance yes and omits interfaces`() {
        val out = RnsConfigFile.build(cfg(), shareInstance = true)
        assertTrue(out.contains("share_instance = Yes"))
        assertTrue(out.contains("shared_instance_type = tcp"))
        assertFalse(out.contains("[interfaces]"))
        assertFalse(out.contains("type = AutoInterface"))
        assertFalse(out.contains("type = TCPClientInterface"))
    }

    @Test
    fun `shared-client render emits rpc_key when supplied`() {
        val out = RnsConfigFile.build(
            cfg().copy(rpcKey = "deadbeefdeadbeef"),
            shareInstance = true,
        )
        assertTrue(out.contains("rpc_key = deadbeefdeadbeef"))
    }

    @Test
    fun `shared-client render omits rpc_key when null`() {
        val out = RnsConfigFile.build(cfg(), shareInstance = true)
        assertFalse(out.contains("rpc_key"))
    }

    @Test
    fun `own-instance render omits rpc_key even when supplied`() {
        // rpc_key is only meaningful when joining a shared instance.
        val out = RnsConfigFile.build(cfg().copy(rpcKey = "deadbeefdeadbeef"))
        assertFalse(out.contains("rpc_key"))
    }

    @Test
    fun `skipAutoInterface disables AutoInterface but keeps other interfaces`() {
        val out = RnsConfigFile.build(cfg(), shareInstance = false, skipAutoInterface = true)
        assertTrue(out.contains("[interfaces]"))
        assertTrue(out.contains("type = AutoInterface"))
        assertTrue(out.contains("enabled = no"))
        assertTrue(out.contains("WARNING: AutoInterface disabled"))
        // TCP interface is unaffected by the AutoInterface skip.
        assertTrue(out.contains("type = TCPClientInterface"))
        assertTrue(out.contains("target_host = amsterdam.connect.reticulum.network"))
    }

    @Test
    fun `AndroidBLE renders type AndroidBLE with max_connections and timing keys`() {
        val ble = InterfaceConfig.AndroidBLE(
            maxConnections = 5,
            blePowerPreset = "battery_saver",
            bleDiscoveryIntervalMs = 7000L,
            bleScanDurationMs = 12000L,
        )
        val out = RnsConfigFile.build(cfg(interfaces = listOf(ble)))
        // Bare type — matches the bundled AndroidBLE.py deployed by
        // event_bridge.deploy_bundled_interfaces().
        assertTrue(out.contains("type = AndroidBLE\n") || out.contains("type = AndroidBLE\r"))
        // Parent BLEInterface reads `max_connections`, NOT `max_peers`.
        assertTrue(out.contains("max_connections = 5"))
        assertFalse(out.contains("max_peers"))
        assertTrue(out.contains("ble_power_preset = battery_saver"))
        assertTrue(out.contains("ble_discovery_interval_ms = 7000"))
        assertTrue(out.contains("ble_scan_duration_ms = 12000"))
    }

    @Test
    fun `AndroidBLE omits device_name when blank`() {
        val ble = InterfaceConfig.AndroidBLE(deviceName = "")
        val out = RnsConfigFile.build(cfg(interfaces = listOf(ble)))
        assertFalse(out.contains("device_name"))
    }

    @Test
    fun `AndroidBLE includes device_name when set`() {
        val ble = InterfaceConfig.AndroidBLE(deviceName = "Columba1")
        val out = RnsConfigFile.build(cfg(interfaces = listOf(ble)))
        assertTrue(out.contains("device_name = Columba1"))
    }

    @Test
    fun `skipAutoInterface omits AutoInterface data_port and group_id`() {
        val customAuto = InterfaceConfig.AutoInterface(
            groupId = "test-group",
            dataPort = 29999,
        )
        val out = RnsConfigFile.build(
            cfg(interfaces = listOf(customAuto)),
            shareInstance = false,
            skipAutoInterface = true,
        )
        assertFalse(out.contains("data_port = 29999"))
        assertFalse(out.contains("group_id = test-group"))
    }
}
