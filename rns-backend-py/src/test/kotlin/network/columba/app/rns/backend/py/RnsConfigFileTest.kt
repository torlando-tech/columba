package network.columba.app.rns.backend.py

import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.ReticulumConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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
        val out = RnsConfigFile.build(cfg(), joinShareInstance = true)
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
            joinShareInstance = true,
        )
        assertTrue(out.contains("rpc_key = deadbeefdeadbeef"))
    }

    @Test
    fun `shared-client render omits rpc_key when null`() {
        val out = RnsConfigFile.build(cfg(), joinShareInstance = true)
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
        val out = RnsConfigFile.build(cfg(), joinShareInstance = false, skipAutoInterface = true)
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
    fun `discovery settings render in reticulum section`() {
        // RNS 1.1.x reads `discover_interfaces` + `autoconnect_discovered_interfaces`
        // from `[reticulum]` at construction (Reticulum.py:551,584). The values must
        // appear under `[reticulum]`, not inside any interface section, or upstream
        // won't pick them up.
        val out =
            RnsConfigFile.build(
                cfg().copy(
                    discoverInterfaces = true,
                    autoconnectDiscoveredInterfaces = 7,
                ),
            )
        assertTrue(out.contains("discover_interfaces = Yes"))
        assertTrue(out.contains("autoconnect_discovered_interfaces = 7"))
        val reticulumIdx = out.indexOf("[reticulum]")
        val interfacesIdx = out.indexOf("[interfaces]")
        val discoverIdx = out.indexOf("discover_interfaces")
        // The discovery lines must be between [reticulum] and [interfaces], not
        // floating inside an interface block.
        assertTrue(reticulumIdx >= 0 && discoverIdx > reticulumIdx && discoverIdx < interfacesIdx)
    }

    @Test
    fun `discovery defaults render as off`() {
        val out = RnsConfigFile.build(cfg())
        assertTrue(out.contains("discover_interfaces = No"))
        assertTrue(out.contains("autoconnect_discovered_interfaces = 0"))
    }

    @Test
    fun `autoconnect_interface_mode omitted when null (RNS default)`() {
        // null means "let RNS decide" — no line emitted so RNS falls back
        // to its own MODE_GATEWAY/MODE_FULL default.
        val out = RnsConfigFile.build(cfg().copy(autoconnectInterfaceMode = null))
        assertFalse(out.contains("autoconnect_interface_mode"))
    }

    @Test
    fun `autoconnect_interface_mode emitted under reticulum section when set`() {
        val out = RnsConfigFile.build(
            cfg().copy(
                autoconnectDiscoveredInterfaces = 5,
                autoconnectInterfaceMode = "full",
            ),
        )
        assertTrue(out.contains("autoconnect_interface_mode = full"))
        // Must sit between [reticulum] and [interfaces], same as the count.
        val reticulumIdx = out.indexOf("[reticulum]")
        val interfacesIdx = out.indexOf("[interfaces]")
        val modeIdx = out.indexOf("autoconnect_interface_mode")
        assertTrue(reticulumIdx >= 0 && modeIdx > reticulumIdx && modeIdx < interfacesIdx)
    }

    @Test
    fun `autoconnect_interface_mode accepts each valid RNS mode token`() {
        // Slim RNS 1.4+ Reticulum.py validates the token; these are the exact
        // strings the config parser accepts (see InterfaceMode.value mapping).
        for (mode in listOf("full", "gateway", "access_point", "roaming", "boundary", "internal")) {
            val out = RnsConfigFile.build(cfg().copy(autoconnectInterfaceMode = mode))
            assertTrue("expected autoconnect_interface_mode = $mode", out.contains("autoconnect_interface_mode = $mode"))
        }
    }

    @Test
    fun `autoconnect_interface_mode omits unknown values restored from backup`() {
        // A restored preferences backup can carry a value the selector never
        // produced. Rather than emit a token RNS rejects (or that leaves RNS
        // on its default while the UI shows "Default"), omit the line so RNS
        // falls back to its own MODE_GATEWAY/MODE_FULL default.
        for (mode in listOf("bogus", "", "UPPER", "full gateway", "full\nmode")) {
            val out = RnsConfigFile.build(cfg().copy(autoconnectInterfaceMode = mode))
            assertFalse("unknown mode '$mode' must not be emitted", out.contains("autoconnect_interface_mode"))
        }
    }

    @Test
    fun `skipAutoInterface omits AutoInterface data_port and group_id`() {
        val customAuto = InterfaceConfig.AutoInterface(
            groupId = "test-group",
            dataPort = 29999,
        )
        val out = RnsConfigFile.build(
            cfg(interfaces = listOf(customAuto)),
            joinShareInstance = false,
            skipAutoInterface = true,
        )
        assertFalse(out.contains("data_port = 29999"))
        assertFalse(out.contains("group_id = test-group"))
    }

    // ---- host-mode (we ARE the shared instance) -----------------------------

    @Test
    fun `host mode emits share_instance yes and shared_instance_type tcp`() {
        // User toggled "Share Instance" on; no co-located master detected.
        // Daemon publishes itself on TCP 37428 so other RNS apps on the
        // device can RPC through us.
        val out = RnsConfigFile.build(cfg(), hostShareInstance = true)
        assertTrue(out.contains("share_instance = Yes"))
        assertTrue(out.contains("shared_instance_type = tcp"))
    }

    @Test
    fun `host mode keeps interfaces block because we own the transport`() {
        // Distinguishes host (master) from join (client): the master needs
        // its own interface set to drive traffic; the client routes through
        // someone else's interfaces.
        val out = RnsConfigFile.build(cfg(), hostShareInstance = true)
        assertTrue(out.contains("[interfaces]"))
        assertTrue(out.contains("type = AutoInterface"))
        assertTrue(out.contains("type = TCPClientInterface"))
    }

    @Test
    fun `host mode does NOT emit rpc_key`() {
        // The host is the authority — upstream RNS generates its own RPC
        // key for incoming clients at runtime. A persisted rpc_key only
        // matters for joining a foreign master (Sideband).
        val out = RnsConfigFile.build(
            cfg().copy(rpcKey = "deadbeefdeadbeef"),
            hostShareInstance = true,
        )
        assertFalse(out.contains("rpc_key"))
    }

    @Test
    fun `host and join together — join wins, interfaces skipped, rpc_key kept`() {
        // PythonRnsRuntime resolves the conflict by demoting host→false
        // before reaching here, but defend against accidental misuse: if
        // both arrive true, the JOIN branch must take precedence (kernel
        // can't double-bind 37428 anyway).
        val out = RnsConfigFile.build(
            cfg().copy(rpcKey = "deadbeefdeadbeef"),
            joinShareInstance = true,
            hostShareInstance = true,
        )
        assertTrue(out.contains("share_instance = Yes"))
        assertTrue(out.contains("shared_instance_type = tcp"))
        assertFalse(out.contains("[interfaces]"))
        assertTrue(out.contains("rpc_key = deadbeefdeadbeef"))
    }

    @Test
    fun `neither host nor join — own-instance, no share_instance keys`() {
        val out = RnsConfigFile.build(cfg())
        assertTrue(out.contains("share_instance = No"))
        assertFalse(out.contains("shared_instance_type"))
        assertFalse(out.contains("rpc_key"))
    }

    // ---- announce ingress filter ([filter], RNS 1.6.0) ----------------------

    @Test
    fun `filter default on emits required_only and all five built-in types`() {
        val out = RnsConfigFile.build(cfg())
        assertTrue(out.contains("[filter]"))
        assertTrue(out.contains("required_only = Yes"))
        val allowedLine = out.lines().first { it.trim().startsWith("allowed_types") }
        for (aspect in listOf(
            "lxmf.delivery",
            "lxmf.propagation",
            "nomadnetwork.node",
            "lxst.telephony",
            "rnstransport.discovery.interface",
        )) {
            assertTrue("missing $aspect", allowedLine.contains(aspect))
        }
        // hop_limiter 0 = unlimited: the line must be absent so RNS keeps
        // its own default.
        assertFalse(out.contains("hop_limiter"))
    }

    @Test
    fun `filter disabled omits the whole filter section`() {
        val out = RnsConfigFile.build(cfg().copy(announceFilterEnabled = false))
        assertFalse(out.contains("[filter]"))
        assertFalse(out.contains("required_only"))
        assertFalse(out.contains("allowed_types"))
    }

    @Test
    fun `hosting without filter-while-sharing keeps the filter off`() {
        // Joined apps register their own announce needs on the master; a
        // node-wide gate would starve them, so hosting defaults to no filter.
        val out = RnsConfigFile.build(cfg(), hostShareInstance = true)
        assertFalse(out.contains("[filter]"))
    }

    @Test
    fun `hosting with filter-while-sharing opt-in emits the filter`() {
        val out = RnsConfigFile.build(
            cfg().copy(announceFilterWhileHosting = true),
            hostShareInstance = true,
        )
        assertTrue(out.contains("required_only = Yes"))
    }

    @Test
    fun `joining a master never emits the filter — master's config governs`() {
        // Announce handling runs in the master's process; our [filter]
        // section would be inert at best.
        val out = RnsConfigFile.build(cfg(), joinShareInstance = true)
        assertFalse(out.contains("[filter]"))
    }

    @Test
    fun `optional aspect toggles drop exactly their entries`() {
        val out = RnsConfigFile.build(
            cfg().copy(
                announceFilterAllowNodes = false,
                announceFilterAllowCalls = false,
            ),
        )
        val allowedLine = out.lines().first { it.trim().startsWith("allowed_types") }
        assertFalse(allowedLine.contains("nomadnetwork.node"))
        assertFalse(allowedLine.contains("lxst.telephony"))
        assertTrue(allowedLine.contains("lxmf.delivery"))
        assertTrue(allowedLine.contains("lxmf.propagation"))
        assertTrue(allowedLine.contains("rnstransport.discovery.interface"))
    }

    @Test
    fun `custom aspects emit only while hosting`() {
        val withCustom = cfg().copy(
            announceFilterWhileHosting = true,
            announceFilterCustomAspects = listOf("sideband.status"),
        )
        val hosted = RnsConfigFile.build(withCustom, hostShareInstance = true)
        assertTrue(hosted.contains("sideband.status"))
        // Not master → dormant: persisted but never emitted.
        val own = RnsConfigFile.build(withCustom)
        assertFalse(own.contains("sideband.status"))
    }

    @Test
    fun `invalid and duplicate custom aspects are dropped at render`() {
        // A restored backup or hand-edited value must not corrupt the
        // grammar or duplicate built-ins.
        val out = RnsConfigFile.build(
            cfg().copy(
                announceFilterWhileHosting = true,
                announceFilterCustomAspects = listOf(
                    "UPPER.CASE",
                    "no_dot",
                    "with space.x",
                    "lxmf.delivery",
                    "good.app",
                    "good.app",
                ),
            ),
            hostShareInstance = true,
        )
        val allowedLine = out.lines().first { it.trim().startsWith("allowed_types") }
        assertFalse(allowedLine.contains("UPPER"))
        assertFalse(allowedLine.contains("no_dot"))
        assertFalse(allowedLine.contains("space"))
        assertFalse(allowedLine.contains("good.app, good.app"))
        assertTrue(allowedLine.contains("good.app"))
        // Built-in dedup: lxmf.delivery appears exactly once.
        assertEquals(1, Regex("lxmf\\.delivery").findAll(allowedLine).count())
    }

    @Test
    fun `hop limiter emitted only when positive`() {
        val out = RnsConfigFile.build(cfg().copy(announceFilterMaxHops = 3))
        assertTrue(out.contains("hop_limiter = 3"))
        val zero = RnsConfigFile.build(cfg().copy(announceFilterMaxHops = 0))
        assertFalse(zero.contains("hop_limiter"))
    }

    @Test
    fun `filter section sits between logging and interfaces`() {
        val out = RnsConfigFile.build(cfg())
        val loggingIdx = out.indexOf("[logging]")
        val filterIdx = out.indexOf("[filter]")
        val interfacesIdx = out.indexOf("[interfaces]")
        assertTrue(loggingIdx in 0 until filterIdx)
        assertTrue(filterIdx < interfacesIdx)
    }
}
