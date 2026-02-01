// NoVerifyOnlyTests: Tests verify correct send path by checking which method was/wasn't called
// NoRelaxedMocks: Context, BluetoothManager, BluetoothAdapter are Android framework classes
@file:Suppress("NoVerifyOnlyTests", "NoRelaxedMocks")

package com.lxmf.messenger.reticulum.ble.bridge

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import com.lxmf.messenger.reticulum.ble.client.BleGattClient
import com.lxmf.messenger.reticulum.ble.client.BleScanner
import com.lxmf.messenger.reticulum.ble.server.BleGattServer
import io.mockk.clearAllMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tests for TOCTOU race condition in KotlinBLEBridge.send().
 *
 * BUG DESCRIPTION:
 * ---------------
 * The send() function reads peer.deduplicationState to compute useCentral/usePeripheral,
 * then uses those values in the if/else chain. If deduplicationState changes between
 * the read and the actual send, the function uses stale state:
 *
 * ```kotlin
 * val useCentral = peer.isCentral && peer.deduplicationState != CLOSING_CENTRAL  // READ
 * val usePeripheral = peer.isPeripheral && peer.deduplicationState != CLOSING_PERIPHERAL
 * // <-- RACE WINDOW: deduplicationState could change here
 * if (useCentral) {
 *     gattClient?.sendData(...)  // USE stale value - might send via closing path
 * }
 * ```
 *
 * EXPECTED BEHAVIOR:
 * -----------------
 * The send() function should use a per-peer mutex to ensure that state reads and
 * the actual send operation are atomic. This prevents sending via a path that's
 * being closed due to deduplication.
 *
 * TEST APPROACH:
 * -------------
 * We can't directly inject code between the state read and send. Instead, we:
 * 1. Mock gattClient.sendData to introduce a delay, simulating slow GATT operation
 * 2. During that delay, change deduplicationState from another coroutine
 * 3. Verify the mutex prevents state changes during send (after fix)
 */
class KotlinBLEBridgeSendRaceConditionTest {
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

    // ========== Race Condition Documentation Tests ==========

    @Test
    fun `send should not use central path when state changes to CLOSING_CENTRAL during send`() {
        /**
         * TDD TEST: Asserts CORRECT behavior - should FAIL until bug is fixed.
         *
         * EXPECTED BEHAVIOR (after fix):
         * If deduplicationState changes to CLOSING_CENTRAL during send(),
         * the send should NOT use the central path. It should either:
         * - Use the peripheral path instead, OR
         * - Block state changes until send completes (mutex)
         *
         * CURRENT BEHAVIOR (bug):
         * Send reads state (NONE), computes useCentral=true, then state changes
         * to CLOSING_CENTRAL, but send still uses the stale useCentral=true.
         *
         * Timeline:
         * 1. send() computes useCentral = true (state is NONE)
         * 2. TEST HOOK: delay 200ms  <-- race window widened
         * 3. Test changes state to CLOSING_CENTRAL (at 50ms)
         * 4. send() should see updated state and use peripheral instead
         *
         * This test FAILS now (proving bug exists).
         * After fix, test PASSES.
         */
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:01"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"
        val data = byteArrayOf(0x01, 0x02, 0x03)

        // Setup: dual connection peer with NONE state
        addMockPeer(bridge, address, identityHash, isCentral = true, isPeripheral = true)
        setAddressToIdentity(bridge, address, identityHash)

        // ENABLE TEST HOOK: Widen the race window to 200ms
        setTestDelayAfterStateRead(bridge, 200)

        val centralPathUsed = AtomicBoolean(false)
        val peripheralPathUsed = AtomicBoolean(false)

        // Mock both paths to track which was used
        coEvery { mockGattClient.sendData(any(), any()) } coAnswers {
            centralPathUsed.set(true)
            Result.success(Unit)
        }

        coEvery { mockGattServer.notifyCentrals(any(), any()) } coAnswers {
            peripheralPathUsed.set(true)
            Result.success(Unit)
        }

        runBlocking {
            // Start send in background - it will:
            // 1. Read state (NONE) -> compute useCentral=true
            // 2. Hit the 200ms delay from test hook
            bridge.sendAsync(address, data)

            // Wait for send to start and hit the delay
            delay(50)

            // NOW change the state while send is in the test hook delay
            // This simulates deduplication starting mid-send
            val peer = getPeer(bridge, address)
            if (peer != null) {
                setDeduplicationState(peer, "CLOSING_CENTRAL")
            }

            // Wait for send to complete
            delay(300)
        }

        // EXPECTED BEHAVIOR (test should PASS after fix):
        // Central path should NOT be used because state is CLOSING_CENTRAL
        assertFalse(
            "Central path should NOT be used when state is CLOSING_CENTRAL. " +
                "BUG: Send used stale useCentral=true computed before state changed.",
            centralPathUsed.get(),
        )

        // Peripheral path SHOULD be used instead
        assertTrue(
            "Peripheral path should be used when central is closing",
            peripheralPathUsed.get(),
        )
    }

    @Test
    fun `send should not use central path when deduplicationState is CLOSING_CENTRAL`() {
        /**
         * Basic test: verify that when deduplicationState is CLOSING_CENTRAL,
         * send uses peripheral path instead of central.
         */
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:01"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"
        val data = byteArrayOf(0x01, 0x02, 0x03)

        // Setup: dual connection peer with CLOSING_CENTRAL state
        addMockPeer(
            bridge,
            address,
            identityHash,
            isCentral = true,
            isPeripheral = true,
            deduplicationState = "CLOSING_CENTRAL",
        )
        setAddressToIdentity(bridge, address, identityHash)

        coEvery { mockGattServer.notifyCentrals(any(), any()) } returns Result.success(Unit)

        runBlocking {
            bridge.sendAsync(address, data)
            delay(50) // Allow coroutine to complete
        }

        // Should use peripheral path, NOT central
        coVerify(exactly = 0) { mockGattClient.sendData(any(), any()) }
        coVerify { mockGattServer.notifyCentrals(data, address) }
    }

    @Test
    fun `send should not use peripheral path when deduplicationState is CLOSING_PERIPHERAL`() {
        /**
         * Basic test: verify that when deduplicationState is CLOSING_PERIPHERAL,
         * send uses central path instead of peripheral.
         */
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:01"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"
        val data = byteArrayOf(0x01, 0x02, 0x03)

        // Setup: dual connection peer with CLOSING_PERIPHERAL state
        addMockPeer(
            bridge,
            address,
            identityHash,
            isCentral = true,
            isPeripheral = true,
            deduplicationState = "CLOSING_PERIPHERAL",
        )
        setAddressToIdentity(bridge, address, identityHash)

        coEvery { mockGattClient.sendData(any(), any()) } returns Result.success(Unit)

        runBlocking {
            bridge.sendAsync(address, data)
            delay(50) // Allow coroutine to complete
        }

        // Should use central path, NOT peripheral
        coVerify { mockGattClient.sendData(address, data) }
        coVerify(exactly = 0) { mockGattServer.notifyCentrals(any(), any()) }
    }

    @Test
    fun `send should drop packet and log when both paths blocked during deduplication`() {
        /**
         * Edge case: peer only has central connection, and it's being closed.
         * This shouldn't normally happen (deduplication requires dual connection),
         * but we test the defensive handling.
         */
        val bridge = createBridgeWithMocks()
        val address = "AA:BB:CC:DD:EE:01"
        val identityHash = "ab5609dfffb33b21a102e1ff81196be5"
        val data = byteArrayOf(0x01, 0x02, 0x03)

        // Setup: central-only connection with CLOSING_CENTRAL state
        // This blocks central (closing) and peripheral (not connected)
        addMockPeer(
            bridge,
            address,
            identityHash,
            isCentral = true,
            isPeripheral = false,
            deduplicationState = "CLOSING_CENTRAL",
        )
        setAddressToIdentity(bridge, address, identityHash)

        runBlocking {
            bridge.sendAsync(address, data)
            delay(50) // Allow coroutine to complete
        }

        // Neither path should be used - packet dropped
        coVerify(exactly = 0) { mockGattClient.sendData(any(), any()) }
        coVerify(exactly = 0) { mockGattServer.notifyCentrals(any(), any()) }
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

    private fun addMockPeer(
        bridge: KotlinBLEBridge,
        address: String,
        identityHash: String?,
        isCentral: Boolean,
        isPeripheral: Boolean = false,
        mtu: Int = 185,
        deduplicationState: String = "NONE",
    ) {
        val peerConnectionClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$PeerConnection",
            )
        val deduplicationStateClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$DeduplicationState",
            )
        val constructor =
            peerConnectionClass.getDeclaredConstructor(
                String::class.java,
                Int::class.java,
                Boolean::class.java,
                Boolean::class.java,
                String::class.java,
                Long::class.java,
                Int::class.java,
                Long::class.java,
                deduplicationStateClass,
            )
        constructor.isAccessible = true

        val stateEnum = deduplicationStateClass.enumConstants!!.first { it.toString() == deduplicationState }
        val peer =
            constructor.newInstance(
                address,
                mtu,
                isCentral,
                isPeripheral,
                identityHash,
                System.currentTimeMillis(),
                -100, // rssi
                System.currentTimeMillis(), // lastActivity
                stateEnum,
            )

        val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        connectedPeersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connectedPeers = connectedPeersField.get(bridge) as ConcurrentHashMap<String, Any>
        connectedPeers[address] = peer
    }

    private fun getPeer(
        bridge: KotlinBLEBridge,
        address: String,
    ): Any? {
        val connectedPeersField = KotlinBLEBridge::class.java.getDeclaredField("connectedPeers")
        connectedPeersField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val connectedPeers = connectedPeersField.get(bridge) as ConcurrentHashMap<String, Any>
        return connectedPeers[address]
    }

    private fun setDeduplicationState(
        peer: Any,
        stateName: String,
    ) {
        val deduplicationStateClass =
            Class.forName(
                "com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge\$DeduplicationState",
            )
        val stateEnum = deduplicationStateClass.enumConstants!!.first { it.toString() == stateName }

        val field = peer::class.java.getDeclaredField("deduplicationState")
        field.isAccessible = true
        field.set(peer, stateEnum)
    }

    private fun setTestDelayAfterStateRead(
        bridge: KotlinBLEBridge,
        delayMs: Long,
    ) {
        val field = KotlinBLEBridge::class.java.getDeclaredField("testDelayAfterStateReadMs")
        field.isAccessible = true
        field.set(bridge, delayMs)
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
}
