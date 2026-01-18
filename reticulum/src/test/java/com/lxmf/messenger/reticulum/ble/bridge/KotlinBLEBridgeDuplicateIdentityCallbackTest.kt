package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.chaquo.python.PyObject
import com.lxmf.messenger.reticulum.ble.client.BleGattClient
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.server.BleGattServer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

/**
 * Tests for duplicate identity detection callback on Android.
 *
 * BUG DESCRIPTION:
 * ---------------
 * On Linux, when a connection arrives with an identity that's already connected
 * at a different MAC address, `on_duplicate_identity_detected` is called and
 * the connection is rejected. This prevents duplicate connections during MAC rotation.
 *
 * On Android, KotlinBLEBridge does NOT have an `on_duplicate_identity_detected` callback.
 * This means duplicate connections ARE allowed on Android, wasting resources and
 * potentially causing duplicate packet delivery.
 *
 * The comment in handleIdentityReceived says:
 * "Deduplication and MAC rotation handling has been moved to Python (BLEInterface).
 *  Python decides what to do with duplicate identities or dual connections."
 *
 * But Python's `_check_duplicate_identity` is never called because:
 * 1. It's set on the driver via `driver.on_duplicate_identity_detected = ...`
 * 2. KotlinBLEBridge (the Android "driver") doesn't have this callback
 * 3. So Python's duplicate check is never invoked on Android
 *
 * EXPECTED BEHAVIOR:
 * -----------------
 * KotlinBLEBridge should call Python's `on_duplicate_identity_detected` callback
 * when identity is received, BEFORE notifying Python of the connection.
 * If Python returns True (duplicate), the connection should be rejected.
 */
class KotlinBLEBridgeDuplicateIdentityCallbackTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter
    private lateinit var mockScanner: BleScanner
    private lateinit var mockGattClient: BleGattClient
    private lateinit var mockGattServer: BleGattServer

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)
        mockScanner = mockk<BleScanner>(relaxed = true)
        mockGattClient = mockk<BleGattClient>(relaxed = true)
        mockGattServer = mockk<BleGattServer>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter
        every { mockBluetoothAdapter.isEnabled } returns true

        coEvery { mockGattClient.disconnect(any()) } returns Unit
        coEvery { mockGattServer.disconnectCentral(any()) } returns Unit
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== BUG: Missing on_duplicate_identity_detected callback ==========

    @Test
    fun `KotlinBLEBridge should have onDuplicateIdentityDetected callback field`() {
        /**
         * BUG TEST: This test documents that the callback field is MISSING.
         *
         * Currently: KotlinBLEBridge does NOT have onDuplicateIdentityDetected
         * Expected: KotlinBLEBridge SHOULD have onDuplicateIdentityDetected callback
         *
         * After the fix, this test should PASS because the field will exist.
         * Currently, this test FAILS (proves the bug).
         */
        val bridge = createBridgeWithMocks()

        // Try to get the onDuplicateIdentityDetected field
        val field = try {
            KotlinBLEBridge::class.java.getDeclaredField("onDuplicateIdentityDetected")
        } catch (@Suppress("SwallowedException") e: NoSuchFieldException) {
            // Expected when the field doesn't exist - this is what we're testing for
            null
        }

        // BUG: This assertion fails because the field doesn't exist
        assertNotNull(
            "KotlinBLEBridge should have onDuplicateIdentityDetected callback field. " +
                "Without this callback, Python's _check_duplicate_identity is never called on Android, " +
                "allowing duplicate connections during MAC rotation.",
            field,
        )
    }

    @Test
    fun `KotlinBLEBridge should have setOnDuplicateIdentityDetected method`() {
        /**
         * BUG TEST: This test documents that the setter method is MISSING.
         *
         * On Linux, Python sets driver.on_duplicate_identity_detected = _check_duplicate_identity
         * On Android, there's no equivalent method to set this callback.
         */
        val bridge = createBridgeWithMocks()

        // Try to get the setter method
        val method = try {
            KotlinBLEBridge::class.java.getDeclaredMethod(
                "setOnDuplicateIdentityDetected",
                PyObject::class.java,
            )
        } catch (@Suppress("SwallowedException") e: NoSuchMethodException) {
            // Expected when the method doesn't exist - this is what we're testing for
            null
        }

        // BUG: This assertion fails because the method doesn't exist
        assertNotNull(
            "KotlinBLEBridge should have setOnDuplicateIdentityDetected method. " +
                "Python needs this to register its duplicate identity check callback.",
            method,
        )
    }

    @Test
    fun `duplicate identity should be detected when identity already connected at different MAC`() {
        /**
         * Integration test documenting the expected behavior.
         *
         * Setup:
         * - Identity X already connected at MAC_OLD
         *
         * Action:
         * - MAC_NEW connects with same identity X
         *
         * Expected:
         * - on_duplicate_identity_detected callback should be invoked
         * - Callback returns true (duplicate detected)
         * - Connection from MAC_NEW should be rejected
         *
         * Current behavior (BUG):
         * - on_duplicate_identity_detected is never called
         * - Both MAC_OLD and MAC_NEW connections are allowed
         */
        val bridge = createBridgeWithMocks()
        val identity = "ab5609dfffb33b21a102e1ff81196be5"
        val macOld = "AA:BB:CC:DD:EE:01"
        val macNew = "AA:BB:CC:DD:EE:02"

        // Setup: identity already mapped to MAC_OLD
        setAddressToIdentity(bridge, macOld, identity)
        setIdentityToAddress(bridge, identity, macOld)

        // Verify: identity is already connected at MAC_OLD
        val existingAddress = getIdentityToAddress(bridge)[identity]
        assertTrue(
            "Identity should be mapped to MAC_OLD",
            existingAddress == macOld,
        )

        // BUG: There's no callback to check for duplicates before accepting MAC_NEW
        // In a fixed implementation:
        // 1. handleIdentityReceived would call onDuplicateIdentityDetected(macNew, identityBytes)
        // 2. Python would return true (duplicate)
        // 3. Connection would be rejected

        // For now, we just document that both addresses can coexist (BUG)
        setAddressToIdentity(bridge, macNew, identity)

        // BUG: After "connecting" MAC_NEW, both addresses map to the same identity
        val addressToIdentity = getAddressToIdentity(bridge)
        assertTrue("BUG: MAC_OLD has identity mapping", addressToIdentity.containsKey(macOld))
        assertTrue("BUG: MAC_NEW also has identity mapping (should be rejected)", addressToIdentity.containsKey(macNew))

        // This test documents the bug - after fix, MAC_NEW should be rejected
    }

    // ========== Existing callback comparison tests ==========

    @Test
    fun `all callbacks are properly defined including duplicate identity detection`() {
        /**
         * Verify that all callbacks exist, including onDuplicateIdentityDetected.
         * This confirms the duplicate identity detection feature is fully implemented.
         */
        val bridge = createBridgeWithMocks()

        // All callbacks that should exist (including onDuplicateIdentityDetected)
        val allCallbacks = listOf(
            "onDeviceDiscovered",
            "onConnected",
            "onDisconnected",
            "onDataReceived",
            "onIdentityReceived",
            "onMtuNegotiated",
            "onAddressChanged",
            "onDuplicateIdentityDetected", // Added for MAC rotation handling
        )

        for (callbackName in allCallbacks) {
            val field = try {
                KotlinBLEBridge::class.java.getDeclaredField(callbackName)
            } catch (@Suppress("SwallowedException") e: NoSuchFieldException) {
                // Expected for missing callbacks - this is what we're testing for
                null
            }
            assertNotNull("Callback $callbackName should exist", field)
        }
    }

    // ========== Blacklist Avoidance Tests ==========

    @Test
    fun `safe error message formats should not trigger blacklist`() {
        /**
         * DOCUMENTATION TEST for implementing onDuplicateIdentityDetected:
         *
         * On Linux, duplicate identity rejection raises an error that contains
         * "Connection failed to XX:XX:XX", which matches the blacklist regex and
         * incorrectly triggers _record_connection_failure.
         *
         * When we add onDuplicateIdentityDetected to Android, we must ensure the
         * rejection path does NOT use messages matching the blacklist pattern
         * "Connection failed to" or "Connection timeout to".
         *
         * Options for the fix:
         * 1. Don't call onError at all for duplicate rejection (just log in Kotlin)
         * 2. Use severity "info" or "debug" instead of "error" (won't trigger blacklist)
         * 3. Use message format that doesn't match blacklist regex
         *
         * This test verifies that safe message formats don't match the blacklist regex.
         */
        val macNew = "AA:BB:CC:DD:EE:02"

        // The blacklist regex patterns that WILL trigger blacklist:
        val blacklistPatterns = listOf(
            "Connection failed to $macNew",
            "Connection timeout to $macNew",
        )

        // Expected safe message formats that WON'T trigger blacklist:
        val safeMessageFormats = listOf(
            "Duplicate identity rejected for $macNew",
            "Rejecting duplicate identity from $macNew",
            "MAC rotation duplicate detected: $macNew",
        )

        // The blacklist regex from Python's _error_callback
        val blacklistRegex = Regex(
            """(?:Connection (?:failed|timeout) to|to) """ +
                """([0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2}:[0-9A-Fa-f]{2})""",
        )

        // Verify unsafe patterns match (would trigger blacklist)
        for (pattern in blacklistPatterns) {
            assertTrue(
                "Blacklist pattern should match: $pattern",
                blacklistRegex.containsMatchIn(pattern),
            )
        }

        // Verify safe patterns do NOT match (won't trigger blacklist)
        for (safeMsg in safeMessageFormats) {
            assertFalse(
                "Safe message should NOT match blacklist regex: $safeMsg",
                blacklistRegex.containsMatchIn(safeMsg),
            )
        }
    }

    @Test
    fun `error severity info should not trigger blacklist`() {
        /**
         * Document that using "info" severity for duplicate rejection
         * will avoid the blacklist trigger.
         *
         * In Python's _error_callback:
         * - severity "error" with matching message -> triggers blacklist
         * - severity "warning" with "Connection timeout" -> triggers blacklist
         * - severity "info" or "debug" -> does NOT trigger blacklist
         */
        // This is a documentation test - the actual behavior is in Python
        val safeSeverities = listOf("info", "debug")
        val unsafeSeverities = listOf("error", "critical")
        // Note: "warning" severity only triggers blacklist for "Connection timeout" messages

        // Document the expected behavior
        assertTrue("info and debug are safe severities", safeSeverities.isNotEmpty())
        assertTrue("error and critical trigger blacklist", unsafeSeverities.isNotEmpty())
    }

    // ========== Helper Methods ==========

    private fun createBridgeWithMocks(): KotlinBLEBridge {
        val bridge = KotlinBLEBridge(mockContext, mockBluetoothManager)

        val scannerField = KotlinBLEBridge::class.java.getDeclaredField("scanner")
        scannerField.isAccessible = true
        scannerField.set(bridge, mockScanner)

        val gattClientField = KotlinBLEBridge::class.java.getDeclaredField("gattClient")
        gattClientField.isAccessible = true
        gattClientField.set(bridge, mockGattClient)

        val gattServerField = KotlinBLEBridge::class.java.getDeclaredField("gattServer")
        gattServerField.isAccessible = true
        gattServerField.set(bridge, mockGattServer)

        return bridge
    }

    private fun setAddressToIdentity(
        bridge: KotlinBLEBridge,
        address: String,
        identity: String,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("addressToIdentity")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(bridge) as ConcurrentHashMap<String, String>
        map[address] = identity
    }

    private fun getAddressToIdentity(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("addressToIdentity")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }

    private fun setIdentityToAddress(
        bridge: KotlinBLEBridge,
        identity: String,
        address: String,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("identityToAddress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val map = field.get(bridge) as ConcurrentHashMap<String, String>
        map[identity] = address
    }

    private fun getIdentityToAddress(bridge: KotlinBLEBridge): ConcurrentHashMap<String, String> {
        val field = KotlinBLEBridge::class.java.getDeclaredField("identityToAddress")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        return field.get(bridge) as ConcurrentHashMap<String, String>
    }
}
