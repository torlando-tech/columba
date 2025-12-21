package com.lxmf.messenger.reticulum.model

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for InterfaceConfig extension functions.
 */
class InterfaceConfigExtTest {
    // ========== toJsonString Tests ==========

    @Test
    fun `AutoInterface toJsonString contains all fields`() {
        val config =
            InterfaceConfig.AutoInterface(
                name = "Test Auto",
                enabled = true,
                groupId = "test-group",
                discoveryScope = "site",
                discoveryPort = 12345,
                dataPort = 12346,
                mode = "gateway",
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("test-group", json.getString("group_id"))
        assertEquals("site", json.getString("discovery_scope"))
        assertEquals(12345, json.getInt("discovery_port"))
        assertEquals(12346, json.getInt("data_port"))
        assertEquals("gateway", json.getString("mode"))
    }

    @Test
    fun `AutoInterface toJsonString omits both ports when null`() {
        val config =
            InterfaceConfig.AutoInterface(
                name = "Test Auto",
                enabled = true,
                groupId = "test-group",
                discoveryScope = "link",
                discoveryPort = null,
                dataPort = null,
                mode = "full",
            )

        val json = JSONObject(config.toJsonString())

        assertFalse("discovery_port should be omitted when null", json.has("discovery_port"))
        assertFalse("data_port should be omitted when null", json.has("data_port"))
        assertEquals("test-group", json.getString("group_id"))
        assertEquals("link", json.getString("discovery_scope"))
        assertEquals("full", json.getString("mode"))
    }

    @Test
    fun `AutoInterface toJsonString includes only discoveryPort when dataPort is null`() {
        val config =
            InterfaceConfig.AutoInterface(
                name = "Test Auto",
                enabled = true,
                discoveryPort = 29716,
                dataPort = null,
            )

        val json = JSONObject(config.toJsonString())

        assertTrue("discovery_port should be present", json.has("discovery_port"))
        assertEquals(29716, json.getInt("discovery_port"))
        assertFalse("data_port should be omitted when null", json.has("data_port"))
    }

    @Test
    fun `AutoInterface toJsonString includes only dataPort when discoveryPort is null`() {
        val config =
            InterfaceConfig.AutoInterface(
                name = "Test Auto",
                enabled = true,
                discoveryPort = null,
                dataPort = 42671,
            )

        val json = JSONObject(config.toJsonString())

        assertFalse("discovery_port should be omitted when null", json.has("discovery_port"))
        assertTrue("data_port should be present", json.has("data_port"))
        assertEquals(42671, json.getInt("data_port"))
    }

    @Test
    fun `TCPClient toJsonString contains all fields`() {
        val config =
            InterfaceConfig.TCPClient(
                name = "Test TCP",
                enabled = true,
                targetHost = "10.0.0.1",
                targetPort = 4242,
                kissFraming = true,
                mode = "full",
                networkName = "testnet",
                passphrase = "secret",
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("10.0.0.1", json.getString("target_host"))
        assertEquals(4242, json.getInt("target_port"))
        assertTrue(json.getBoolean("kiss_framing"))
        assertEquals("full", json.getString("mode"))
        assertEquals("testnet", json.getString("network_name"))
        assertEquals("secret", json.getString("passphrase"))
    }

    @Test
    fun `TCPClient toJsonString omits null networkName and passphrase`() {
        val config =
            InterfaceConfig.TCPClient(
                name = "Test TCP",
                enabled = true,
                targetHost = "10.0.0.1",
                targetPort = 4242,
                networkName = null,
                passphrase = null,
            )

        val json = JSONObject(config.toJsonString())

        assertFalse(json.has("network_name"))
        assertFalse(json.has("passphrase"))
    }

    @Test
    fun `RNode toJsonString contains all fields`() {
        val config =
            InterfaceConfig.RNode(
                name = "Test RNode",
                enabled = true,
                targetDeviceName = "RNode-BT",
                connectionMode = "ble",
                frequency = 868000000L,
                bandwidth = 250000,
                txPower = 14,
                spreadingFactor = 9,
                codingRate = 7,
                stAlock = 5.0,
                ltAlock = 10.0,
                mode = "roaming",
                enableFramebuffer = true,
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("RNode-BT", json.getString("target_device_name"))
        assertEquals("ble", json.getString("connection_mode"))
        assertEquals(868000000L, json.getLong("frequency"))
        assertEquals(250000, json.getInt("bandwidth"))
        assertEquals(14, json.getInt("tx_power"))
        assertEquals(9, json.getInt("spreading_factor"))
        assertEquals(7, json.getInt("coding_rate"))
        assertEquals(5.0, json.getDouble("st_alock"), 0.01)
        assertEquals(10.0, json.getDouble("lt_alock"), 0.01)
        assertEquals("roaming", json.getString("mode"))
        assertTrue(json.getBoolean("enable_framebuffer"))
    }

    @Test
    fun `RNode toJsonString omits null airtime limits`() {
        val config =
            InterfaceConfig.RNode(
                name = "Test RNode",
                enabled = true,
                targetDeviceName = "RNode-BT",
                stAlock = null,
                ltAlock = null,
            )

        val json = JSONObject(config.toJsonString())

        assertFalse(json.has("st_alock"))
        assertFalse(json.has("lt_alock"))
    }

    @Test
    fun `UDP toJsonString contains all fields`() {
        val config =
            InterfaceConfig.UDP(
                name = "Test UDP",
                enabled = true,
                listenIp = "192.168.1.1",
                listenPort = 5000,
                forwardIp = "192.168.1.255",
                forwardPort = 5001,
                mode = "boundary",
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("192.168.1.1", json.getString("listen_ip"))
        assertEquals(5000, json.getInt("listen_port"))
        assertEquals("192.168.1.255", json.getString("forward_ip"))
        assertEquals(5001, json.getInt("forward_port"))
        assertEquals("boundary", json.getString("mode"))
    }

    @Test
    fun `AndroidBLE toJsonString contains all fields`() {
        val config =
            InterfaceConfig.AndroidBLE(
                name = "Test BLE",
                enabled = true,
                deviceName = "MyDevice",
                maxConnections = 5,
                mode = "access_point",
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("MyDevice", json.getString("device_name"))
        assertEquals(5, json.getInt("max_connections"))
        assertEquals("access_point", json.getString("mode"))
    }

    @Test
    fun `AndroidBLE toJsonString handles empty deviceName`() {
        val config =
            InterfaceConfig.AndroidBLE(
                name = "Test BLE",
                enabled = true,
                deviceName = "",
                maxConnections = 7,
                mode = "roaming",
            )

        val json = JSONObject(config.toJsonString())

        assertEquals("", json.getString("device_name"))
    }

    // ========== typeName Tests ==========

    @Test
    fun `typeName returns correct type for AutoInterface`() {
        val config = InterfaceConfig.AutoInterface()
        assertEquals("AutoInterface", config.typeName)
    }

    @Test
    fun `typeName returns correct type for TCPClient`() {
        val config = InterfaceConfig.TCPClient(targetHost = "localhost", targetPort = 1234)
        assertEquals("TCPClient", config.typeName)
    }

    @Test
    fun `typeName returns correct type for RNode`() {
        val config = InterfaceConfig.RNode(targetDeviceName = "RNode-BT")
        assertEquals("RNode", config.typeName)
    }

    @Test
    fun `typeName returns correct type for UDP`() {
        val config = InterfaceConfig.UDP()
        assertEquals("UDP", config.typeName)
    }

    @Test
    fun `typeName returns correct type for AndroidBLE`() {
        val config = InterfaceConfig.AndroidBLE()
        assertEquals("AndroidBLE", config.typeName)
    }
}
