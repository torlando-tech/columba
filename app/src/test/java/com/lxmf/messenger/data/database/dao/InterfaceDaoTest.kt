package com.lxmf.messenger.data.database.dao

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.database.entity.InterfaceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for InterfaceDao.hasEnabledBluetoothInterface() using Robolectric.
 *
 * Tests the logic that determines which interfaces require Bluetooth permissions:
 * - AndroidBLE always requires Bluetooth
 * - RNode in TCP mode does NOT require Bluetooth (uses WiFi/network)
 * - RNode in classic/ble modes requires Bluetooth
 * - RNode with malformed JSON defaults to requiring Bluetooth (safe default)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class InterfaceDaoTest {
    private lateinit var database: InterfaceDatabase
    private lateinit var interfaceDao: InterfaceDao
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()

        // Create in-memory database for testing (no callbacks needed)
        database =
            Room
                .inMemoryDatabaseBuilder(
                    context,
                    InterfaceDatabase::class.java,
                ).allowMainThreadQueries()
                .build()

        interfaceDao = database.interfaceDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    // ========== AndroidBLE Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_true_for_AndroidBLE() =
        runTest {
            // Given - AndroidBLE interface is enabled
            val androidBle =
                InterfaceEntity(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    enabled = true,
                    configJson = """{"device_name": "RNS-Test", "max_connections": 3}""",
                )
            interfaceDao.insertInterface(androidBle)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("AndroidBLE should require Bluetooth", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_disabled_AndroidBLE() =
        runTest {
            // Given - AndroidBLE interface is disabled
            val androidBle =
                InterfaceEntity(
                    name = "Bluetooth LE",
                    type = "AndroidBLE",
                    enabled = false,
                    configJson = """{"device_name": "RNS-Test"}""",
                )
            interfaceDao.insertInterface(androidBle)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Disabled AndroidBLE should not require Bluetooth", result)
        }

    // ========== RNode TCP Mode Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_RNode_TCP_mode() =
        runTest {
            // Given - RNode in TCP mode (WiFi/network connection)
            val rnodeTcp =
                InterfaceEntity(
                    name = "RNode TCP",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "tcp", "port": "/dev/ttyUSB0"}""",
                )
            interfaceDao.insertInterface(rnodeTcp)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("RNode in TCP mode should NOT require Bluetooth", result)
        }

    // ========== RNode USB Mode Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_RNode_USB_mode() =
        runTest {
            // Given - RNode connected via USB (no Bluetooth needed)
            val rnodeUsb =
                InterfaceEntity(
                    name = "RNode USB",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "usb", "usb_device_id": 1003}""",
                )
            interfaceDao.insertInterface(rnodeUsb)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("RNode in USB mode should NOT require Bluetooth", result)
        }

    // ========== RNode Classic Mode Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_true_for_RNode_classic_mode() =
        runTest {
            // Given - RNode in classic Bluetooth mode
            val rnodeClassic =
                InterfaceEntity(
                    name = "RNode Classic",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "classic", "port": "/dev/ttyUSB0"}""",
                )
            interfaceDao.insertInterface(rnodeClassic)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("RNode in classic mode should require Bluetooth", result)
        }

    // ========== RNode BLE Mode Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_true_for_RNode_ble_mode() =
        runTest {
            // Given - RNode in BLE mode
            val rnodeBle =
                InterfaceEntity(
                    name = "RNode BLE",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "ble", "port": "/dev/ttyUSB0"}""",
                )
            interfaceDao.insertInterface(rnodeBle)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("RNode in BLE mode should require Bluetooth", result)
        }

    // ========== RNode Malformed JSON Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_true_for_RNode_with_malformed_JSON() =
        runTest {
            // Given - RNode with malformed JSON (defaults to requiring Bluetooth for safety)
            val rnodeMalformed =
                InterfaceEntity(
                    name = "RNode Malformed",
                    type = "RNode",
                    enabled = true,
                    configJson = """not valid json at all""",
                )
            interfaceDao.insertInterface(rnodeMalformed)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("RNode with malformed JSON should default to requiring Bluetooth", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_true_for_RNode_without_connection_mode() =
        runTest {
            // Given - RNode without connection_mode field
            val rnodeNoMode =
                InterfaceEntity(
                    name = "RNode No Mode",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"port": "/dev/ttyUSB0", "frequency": 868000000}""",
                )
            interfaceDao.insertInterface(rnodeNoMode)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("RNode without connection_mode should default to requiring Bluetooth", result)
        }

    // ========== Other Interface Types Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_TCPClient() =
        runTest {
            // Given - TCPClient interface
            val tcpClient =
                InterfaceEntity(
                    name = "TCP Client",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1", "target_port": 4242}""",
                )
            interfaceDao.insertInterface(tcpClient)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("TCPClient should not require Bluetooth", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_AutoInterface() =
        runTest {
            // Given - AutoInterface
            val autoInterface =
                InterfaceEntity(
                    name = "Auto Interface",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id": "reticulum", "discovery_scope": "link"}""",
                )
            interfaceDao.insertInterface(autoInterface)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("AutoInterface should not require Bluetooth", result)
        }

    // ========== Multiple Interface Tests ==========

    @Test
    fun hasEnabledBluetoothInterface_returns_true_when_any_Bluetooth_interface_enabled() =
        runTest {
            // Given - Mix of interfaces, one requires Bluetooth
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "TCP",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1"}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "RNode TCP",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "tcp"}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "BLE",
                    type = "AndroidBLE",
                    enabled = true,
                    configJson = """{"device_name": "RNS"}""",
                ),
            )

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("Should return true when ANY Bluetooth-requiring interface is enabled", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_when_only_non_Bluetooth_interfaces_enabled() =
        runTest {
            // Given - Only non-Bluetooth interfaces
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "TCP",
                    type = "TCPClient",
                    enabled = true,
                    configJson = """{"target_host": "10.0.0.1"}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "RNode TCP",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "tcp"}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "Auto",
                    type = "AutoInterface",
                    enabled = true,
                    configJson = """{"group_id": "reticulum"}""",
                ),
            )

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Should return false when no Bluetooth-requiring interfaces enabled", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_when_all_Bluetooth_interfaces_disabled() =
        runTest {
            // Given - Bluetooth interfaces exist but are disabled
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "BLE",
                    type = "AndroidBLE",
                    enabled = false,
                    configJson = """{"device_name": "RNS"}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "RNode Classic",
                    type = "RNode",
                    enabled = false,
                    configJson = """{"connection_mode": "classic"}""",
                ),
            )

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Should return false when all Bluetooth interfaces are disabled", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_when_only_USB_and_TCP_RNodes_enabled() =
        runTest {
            // Given - RNode USB and RNode TCP, both non-Bluetooth
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "RNode USB",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "usb", "usb_device_id": 1003}""",
                ),
            )
            interfaceDao.insertInterface(
                InterfaceEntity(
                    name = "RNode TCP",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "tcp", "tcp_host": "10.0.0.1"}""",
                ),
            )

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Should return false when only USB and TCP RNodes are enabled", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_returns_false_for_empty_database() =
        runTest {
            // Given - Empty database (no interfaces)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Should return false when no interfaces exist", result)
        }

    // ========== Edge Cases ==========

    @Test
    fun hasEnabledBluetoothInterface_handles_whitespace_in_connection_mode() =
        runTest {
            // Given - RNode with whitespace in JSON
            val rnodeTcp =
                InterfaceEntity(
                    name = "RNode TCP Whitespace",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode":  "tcp"  , "port": "/dev/ttyUSB0"}""",
                )
            interfaceDao.insertInterface(rnodeTcp)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertFalse("Should handle whitespace variations in JSON correctly", result)
        }

    @Test
    fun hasEnabledBluetoothInterface_is_case_sensitive_for_tcp() =
        runTest {
            // Given - RNode with uppercase TCP (should not match "tcp")
            val rnodeTcp =
                InterfaceEntity(
                    name = "RNode TCP Uppercase",
                    type = "RNode",
                    enabled = true,
                    configJson = """{"connection_mode": "TCP"}""",
                )
            interfaceDao.insertInterface(rnodeTcp)

            // When
            val result = interfaceDao.hasEnabledBluetoothInterface().first()

            // Then
            assertTrue("Should be case-sensitive: TCP != tcp, so should default to requiring Bluetooth", result)
        }
}
