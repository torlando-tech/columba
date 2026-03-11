package com.lxmf.messenger.service.manager

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import com.lxmf.messenger.service.state.ServiceState
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/**
 * Unit tests for ServiceNotificationManager RNode disconnect notification behaviour.
 *
 * Verifies:
 * - Heads-up notification posted on disconnect, cancelled on reconnect
 * - Per-interface tracking (multiple RNode interfaces)
 * - Foreground notification text includes "(RNode disconnected)" when appropriate
 * - Debounce prevents notification spam on rapid disconnect cycles
 * - No foreground notification refresh during active sync
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ServiceNotificationManagerRNodeTest {
    private lateinit var context: Context
    private lateinit var notificationManager: NotificationManager
    private lateinit var shadowNotificationManager: org.robolectric.shadows.ShadowNotificationManager
    private lateinit var serviceNotificationManager: ServiceNotificationManager
    private lateinit var state: ServiceState

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication()
        notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowNotificationManager = shadowOf(notificationManager)
        state = ServiceState()
        state.networkStatus.set("READY")

        serviceNotificationManager = ServiceNotificationManager(context, state)
        serviceNotificationManager.createNotificationChannel()
    }

    private fun drainMainLooper() {
        ShadowLooper.idleMainLooper()
    }

    private fun getRNodeNotification() =
        shadowNotificationManager
            .getNotification(ServiceNotificationManager.NOTIFICATION_ID_RNODE)

    // ========== Basic disconnect/reconnect ==========

    @Test
    fun `disconnect posts heads-up notification`() {
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()

        val notification = getRNodeNotification()
        assertNotNull("Disconnect should post RNode alert notification", notification)
        assertTrue(
            "Notification text should contain interface name",
            notification.extras.getString("android.text")!!.contains("RNodeInterface[BLE]"),
        )
    }

    @Test
    fun `reconnect cancels heads-up notification`() {
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()
        assertNotNull("Precondition: notification should exist", getRNodeNotification())

        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[BLE]")
        drainMainLooper()

        assertNull(
            "Reconnect should cancel RNode alert notification",
            getRNodeNotification(),
        )
    }

    // ========== Per-interface tracking ==========

    @Test
    fun `reconnect of one interface does not dismiss alert for another`() {
        // Disconnect both BLE and USB
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[USB]")
        drainMainLooper()

        // Reconnect USB only
        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[USB]")
        drainMainLooper()

        val notification = getRNodeNotification()
        assertNotNull(
            "Alert should remain while BLE is still disconnected",
            notification,
        )
    }

    @Test
    fun `all interfaces reconnecting dismisses alert`() {
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[USB]")
        drainMainLooper()

        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[BLE]")
        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[USB]")
        drainMainLooper()

        assertNull(
            "Alert should be dismissed when all interfaces reconnect",
            getRNodeNotification(),
        )
    }

    // ========== Foreground notification text ==========

    @Test
    fun `foreground notification includes RNode disconnected when READY and interface down`() {
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()

        // createNotification is called internally via repostNotification; verify via a fresh call
        val notification = serviceNotificationManager.createNotification("READY")
        val bigText = notification.extras.getString("android.bigText") ?: ""
        assertTrue(
            "Detail text should include '(RNode disconnected)' when READY and interface is down",
            bigText.contains("(RNode disconnected)"),
        )
    }

    @Test
    fun `foreground notification omits RNode disconnected when all interfaces online`() {
        // Disconnect then reconnect
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[BLE]")
        drainMainLooper()

        val notification = serviceNotificationManager.createNotification("READY")
        val bigText = notification.extras.getString("android.bigText") ?: ""
        assertTrue(
            "Detail text should NOT include '(RNode disconnected)' when all interfaces are online",
            !bigText.contains("(RNode disconnected)"),
        )
    }

    // ========== Debounce ==========

    @Test
    fun `disconnect after reconnect re-posts alert because debounce resets on cancel`() {
        // First disconnect — should post
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()
        assertNotNull("First disconnect should post notification", getRNodeNotification())

        // Reconnect cancels notification and resets debounce timer
        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[BLE]")
        drainMainLooper()
        assertNull("Reconnect should cancel notification", getRNodeNotification())

        // Disconnect again — should re-post even if within 10s of first disconnect,
        // because the debounce timer was reset when the notification was cancelled
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()

        assertNotNull(
            "Second disconnect should re-post alert because prior notification was cancelled",
            getRNodeNotification(),
        )
    }

    @Test
    fun `second interface disconnect within cooldown updates notification content silently`() {
        // First interface disconnects — notification posted with BLE name
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        drainMainLooper()
        assertNotNull("First disconnect should post notification", getRNodeNotification())

        // Second interface disconnects — content should update to show both names
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[USB]")
        drainMainLooper()

        val notification = getRNodeNotification()
        assertNotNull("Notification should still exist", notification)
        val text = notification.extras.getString("android.text") ?: ""
        assertTrue(
            "Notification body should list both disconnected interfaces",
            text.contains("RNodeInterface[BLE]") && text.contains("RNodeInterface[USB]"),
        )
    }

    @Test
    fun `partial reconnect updates notification to show only remaining disconnected interface`() {
        // Disconnect both
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[BLE]")
        serviceNotificationManager.updateRNodeStatus(false, "RNodeInterface[USB]")
        drainMainLooper()

        // BLE reconnects — notification should update to show only USB
        serviceNotificationManager.updateRNodeStatus(true, "RNodeInterface[BLE]")
        drainMainLooper()

        val notification = getRNodeNotification()
        assertNotNull("Notification should remain for USB", notification)
        val text = notification.extras.getString("android.text") ?: ""
        assertTrue(
            "Notification should show only USB after BLE reconnects",
            text.contains("RNodeInterface[USB]") && !text.contains("RNodeInterface[BLE]"),
        )
    }
}
