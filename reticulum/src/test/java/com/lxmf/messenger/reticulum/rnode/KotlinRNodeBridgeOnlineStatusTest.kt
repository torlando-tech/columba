package com.lxmf.messenger.reticulum.rnode

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for KotlinRNodeBridge online status listener functionality.
 *
 * Tests the event-driven online status notification system that enables
 * UI refresh when RNode connects or disconnects.
 */
class KotlinRNodeBridgeOnlineStatusTest {
    private lateinit var mockContext: Context
    private lateinit var mockBluetoothManager: BluetoothManager
    private lateinit var mockBluetoothAdapter: BluetoothAdapter

    @Before
    fun setup() {
        mockContext = mockk<Context>(relaxed = true)
        mockBluetoothManager = mockk<BluetoothManager>(relaxed = true)
        mockBluetoothAdapter = mockk<BluetoothAdapter>(relaxed = true)

        every { mockContext.applicationContext } returns mockContext
        every { mockContext.getSystemService(Context.BLUETOOTH_SERVICE) } returns mockBluetoothManager
        every { mockBluetoothManager.adapter } returns mockBluetoothAdapter
        every { mockBluetoothAdapter.isEnabled } returns true
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    // ========== RNodeOnlineStatusListener Tests ==========

    @Test
    fun `addOnlineStatusListener registers listener correctly`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val receivedStatuses = mutableListOf<Boolean>()

        val listener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    receivedStatuses.add(isOnline)
                }
            }

        bridge.addOnlineStatusListener(listener)
        bridge.notifyOnlineStatusChanged(true)

        assertEquals("Listener should receive status", 1, receivedStatuses.size)
        assertTrue("Status should be online", receivedStatuses[0])
    }

    @Test
    fun `removeOnlineStatusListener stops notifications`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val receivedStatuses = mutableListOf<Boolean>()

        val listener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    receivedStatuses.add(isOnline)
                }
            }

        bridge.addOnlineStatusListener(listener)
        bridge.notifyOnlineStatusChanged(true)
        bridge.removeOnlineStatusListener(listener)
        bridge.notifyOnlineStatusChanged(false)

        assertEquals("Should only receive one notification", 1, receivedStatuses.size)
        assertTrue("First status should be online", receivedStatuses[0])
    }

    @Test
    fun `notifyOnlineStatusChanged notifies all registered listeners`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val listener1Count = AtomicInteger(0)
        val listener2Count = AtomicInteger(0)

        val listener1 =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    listener1Count.incrementAndGet()
                }
            }

        val listener2 =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    listener2Count.incrementAndGet()
                }
            }

        bridge.addOnlineStatusListener(listener1)
        bridge.addOnlineStatusListener(listener2)
        bridge.notifyOnlineStatusChanged(true)

        assertEquals("Listener 1 should be notified", 1, listener1Count.get())
        assertEquals("Listener 2 should be notified", 1, listener2Count.get())
    }

    @Test
    fun `notifyOnlineStatusChanged with true indicates online`() {
        val bridge = KotlinRNodeBridge(mockContext)
        var receivedStatus: Boolean? = null

        bridge.addOnlineStatusListener(
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    receivedStatus = isOnline
                }
            },
        )

        bridge.notifyOnlineStatusChanged(true)

        assertTrue("Status should be true (online)", receivedStatus == true)
    }

    @Test
    fun `notifyOnlineStatusChanged with false indicates offline`() {
        val bridge = KotlinRNodeBridge(mockContext)
        var receivedStatus: Boolean? = null

        bridge.addOnlineStatusListener(
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    receivedStatus = isOnline
                }
            },
        )

        bridge.notifyOnlineStatusChanged(false)

        assertFalse("Status should be false (offline)", receivedStatus == true)
    }

    @Test
    fun `duplicate listener registration is prevented`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val notificationCount = AtomicInteger(0)

        val listener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    notificationCount.incrementAndGet()
                }
            }

        // Register same listener twice
        bridge.addOnlineStatusListener(listener)
        bridge.addOnlineStatusListener(listener)
        bridge.notifyOnlineStatusChanged(true)

        assertEquals("Should only receive one notification despite duplicate registration", 1, notificationCount.get())
    }

    @Test
    fun `listener exception does not affect other listeners`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val listener2Called = AtomicBoolean(false)

        val throwingListener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    error("Test exception")
                }
            }

        val normalListener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    listener2Called.set(true)
                }
            }

        bridge.addOnlineStatusListener(throwingListener)
        bridge.addOnlineStatusListener(normalListener)

        // Should not throw and should still notify second listener
        bridge.notifyOnlineStatusChanged(true)

        assertTrue("Second listener should still be called", listener2Called.get())
    }

    @Test
    fun `multiple status changes are all delivered`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val receivedStatuses = mutableListOf<Boolean>()

        bridge.addOnlineStatusListener(
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    receivedStatuses.add(isOnline)
                }
            },
        )

        bridge.notifyOnlineStatusChanged(true)
        bridge.notifyOnlineStatusChanged(false)
        bridge.notifyOnlineStatusChanged(true)

        assertEquals("Should receive all three status changes", 3, receivedStatuses.size)
        assertTrue("First status should be online", receivedStatuses[0])
        assertFalse("Second status should be offline", receivedStatuses[1])
        assertTrue("Third status should be online", receivedStatuses[2])
    }

    @Test
    fun `no listeners registered does not cause error`() {
        val bridge = KotlinRNodeBridge(mockContext)

        // Should not throw any exception
        bridge.notifyOnlineStatusChanged(true)
        bridge.notifyOnlineStatusChanged(false)
    }

    @Test
    fun `removing non-existent listener does not cause error`() {
        val bridge = KotlinRNodeBridge(mockContext)

        val listener =
            object : RNodeOnlineStatusListener {
                override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                    // No-op listener for testing removal
                }
            }

        // Should not throw any exception
        bridge.removeOnlineStatusListener(listener)
    }

    // ========== Thread Safety Tests ==========

    @Test
    fun `concurrent listener registration is thread safe`() {
        val bridge = KotlinRNodeBridge(mockContext)
        val listenerCount = 10
        val latch = CountDownLatch(listenerCount)
        val notificationCount = AtomicInteger(0)

        // Register listeners from multiple threads
        repeat(listenerCount) {
            Thread {
                bridge.addOnlineStatusListener(
                    object : RNodeOnlineStatusListener {
                        override fun onRNodeOnlineStatusChanged(isOnline: Boolean) {
                            notificationCount.incrementAndGet()
                        }
                    },
                )
                latch.countDown()
            }.start()
        }

        assertTrue("All registrations should complete", latch.await(5, TimeUnit.SECONDS))

        bridge.notifyOnlineStatusChanged(true)

        assertEquals("All listeners should be notified", listenerCount, notificationCount.get())
    }
}
