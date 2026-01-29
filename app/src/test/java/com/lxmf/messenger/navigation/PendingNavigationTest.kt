package com.lxmf.messenger.navigation

import com.lxmf.messenger.PendingNavigation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for PendingNavigation sealed class variants.
 *
 * Tests verify the data class behavior including equality, hashcode,
 * and property access for USB device navigation types.
 */
class PendingNavigationTest {
    // ==================== UsbDeviceAction Tests ====================

    @Test
    fun `UsbDeviceAction has correct properties`() {
        val action =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "CH340 Serial",
            )

        assertEquals(123, action.usbDeviceId)
        assertEquals(0x1A86, action.vendorId)
        assertEquals(0x7523, action.productId)
        assertEquals("CH340 Serial", action.deviceName)
    }

    @Test
    fun `UsbDeviceAction instances with same values are equal`() {
        val action1 =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "CH340 Serial",
            )
        val action2 =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "CH340 Serial",
            )

        assertEquals(action1, action2)
        assertEquals(action1.hashCode(), action2.hashCode())
    }

    @Test
    fun `UsbDeviceAction instances with different values are not equal`() {
        val action1 =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "CH340 Serial",
            )
        val action2 =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 456, // Different device ID
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "CH340 Serial",
            )
        val action3 =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x10C4, // Different vendor (CP2102)
                productId = 0xEA60,
                deviceName = "CP2102 USB to UART",
            )

        assertNotEquals(action1, action2)
        assertNotEquals(action1, action3)
    }

    @Test
    fun `UsbDeviceAction is PendingNavigation`() {
        val action =
            PendingNavigation.UsbDeviceAction(
                usbDeviceId = 123,
                vendorId = 0x1A86,
                productId = 0x7523,
                deviceName = "Test Device",
            )

        assertTrue(action is PendingNavigation)
    }

    // ==================== RNodeWizardWithUsb Tests ====================

    @Test
    fun `RNodeWizardWithUsb has correct properties`() {
        val navigation =
            PendingNavigation.RNodeWizardWithUsb(
                usbDeviceId = 456,
                vendorId = 0x303A,
                productId = 0x1001,
                deviceName = "ESP32-S3",
            )

        assertEquals(456, navigation.usbDeviceId)
        assertEquals(0x303A, navigation.vendorId)
        assertEquals(0x1001, navigation.productId)
        assertEquals("ESP32-S3", navigation.deviceName)
    }

    @Test
    fun `RNodeWizardWithUsb instances with same values are equal`() {
        val nav1 =
            PendingNavigation.RNodeWizardWithUsb(
                usbDeviceId = 456,
                vendorId = 0x303A,
                productId = 0x1001,
                deviceName = "ESP32-S3",
            )
        val nav2 =
            PendingNavigation.RNodeWizardWithUsb(
                usbDeviceId = 456,
                vendorId = 0x303A,
                productId = 0x1001,
                deviceName = "ESP32-S3",
            )

        assertEquals(nav1, nav2)
        assertEquals(nav1.hashCode(), nav2.hashCode())
    }

    // ==================== DirectFlash Tests ====================

    @Test
    fun `DirectFlash has correct properties`() {
        val flash =
            PendingNavigation.DirectFlash(
                usbDeviceId = 789,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "FTDI Serial",
            )

        assertEquals(789, flash.usbDeviceId)
        assertEquals(0x0403, flash.vendorId)
        assertEquals(0x6001, flash.productId)
        assertEquals("FTDI Serial", flash.deviceName)
    }

    @Test
    fun `DirectFlash instances with same values are equal`() {
        val flash1 =
            PendingNavigation.DirectFlash(
                usbDeviceId = 789,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "FTDI Serial",
            )
        val flash2 =
            PendingNavigation.DirectFlash(
                usbDeviceId = 789,
                vendorId = 0x0403,
                productId = 0x6001,
                deviceName = "FTDI Serial",
            )

        assertEquals(flash1, flash2)
        assertEquals(flash1.hashCode(), flash2.hashCode())
    }

    // ==================== InterfaceStats Tests ====================

    @Test
    fun `InterfaceStats has correct interfaceId`() {
        val stats = PendingNavigation.InterfaceStats(interfaceId = 42L)

        assertEquals(42L, stats.interfaceId)
    }

    @Test
    fun `InterfaceStats instances with same id are equal`() {
        val stats1 = PendingNavigation.InterfaceStats(interfaceId = 42L)
        val stats2 = PendingNavigation.InterfaceStats(interfaceId = 42L)

        assertEquals(stats1, stats2)
        assertEquals(stats1.hashCode(), stats2.hashCode())
    }

    @Test
    fun `InterfaceStats instances with different ids are not equal`() {
        val stats1 = PendingNavigation.InterfaceStats(interfaceId = 42L)
        val stats2 = PendingNavigation.InterfaceStats(interfaceId = 99L)

        assertNotEquals(stats1, stats2)
    }

    // ==================== Type Hierarchy Tests ====================

    @Test
    fun `all USB navigation types are distinct`() {
        val usbAction = PendingNavigation.UsbDeviceAction(1, 0x1A86, 0x7523, "Device")
        val rnodeWizard = PendingNavigation.RNodeWizardWithUsb(1, 0x1A86, 0x7523, "Device")
        val directFlash = PendingNavigation.DirectFlash(1, 0x1A86, 0x7523, "Device")

        // Even with same parameters, different types should not be equal
        assertNotEquals(usbAction, rnodeWizard)
        assertNotEquals(usbAction, directFlash)
        assertNotEquals(rnodeWizard, directFlash)
    }

    @Test
    fun `all PendingNavigation variants are PendingNavigation`() {
        val navigations =
            listOf<PendingNavigation>(
                PendingNavigation.AnnounceDetail("abc123"),
                PendingNavigation.Conversation("abc123", "Test User"),
                PendingNavigation.AddContact("lxma://test"),
                PendingNavigation.IncomingCall("def456"),
                PendingNavigation.AnswerCall("def456"),
                PendingNavigation.InterfaceStats(1L),
                PendingNavigation.UsbDeviceAction(1, 0x1A86, 0x7523, "Device"),
                PendingNavigation.RNodeWizardWithUsb(1, 0x1A86, 0x7523, "Device"),
                PendingNavigation.DirectFlash(1, 0x1A86, 0x7523, "Device"),
            )

        for (navigation in navigations) {
            assertTrue(
                "${navigation::class.simpleName} should be PendingNavigation",
                navigation is PendingNavigation,
            )
        }
    }
}
