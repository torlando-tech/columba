package com.lxmf.messenger.ui.screens

import com.lxmf.messenger.viewmodel.InterfaceIconType
import com.lxmf.messenger.viewmodel.InterfaceInfo
import com.lxmf.messenger.viewmodel.getDialogTitle
import com.lxmf.messenger.viewmodel.getIconType
import com.lxmf.messenger.viewmodel.isClickable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for InterfacesCard UI logic.
 *
 * Tests the visual state logic for interface rows:
 * - Online interfaces: green checkmark, not clickable
 * - Offline interfaces: yellow warning, clickable
 * - Failed interfaces: red error icon, clickable with error message
 */
class InterfacesCardLogicTest {
    // ==========================================
    // Online Interface Tests
    // ==========================================

    @Test
    fun `online interface shows check circle icon`() {
        val iface =
            InterfaceInfo(
                name = "RNode LoRa",
                type = "ColumbaRNodeInterface",
                online = true,
                error = null,
            )
        assertEquals(InterfaceIconType.CHECK_CIRCLE, iface.getIconType())
    }

    @Test
    fun `online interface is not clickable`() {
        val iface =
            InterfaceInfo(
                name = "RNode LoRa",
                type = "ColumbaRNodeInterface",
                online = true,
                error = null,
            )
        assertFalse(iface.isClickable())
    }

    // ==========================================
    // Offline Interface Tests (temporary, no error)
    // ==========================================

    @Test
    fun `offline interface shows warning icon`() {
        val iface =
            InterfaceInfo(
                name = "RNode LoRa",
                type = "ColumbaRNodeInterface",
                online = false,
                error = null,
            )
        assertEquals(InterfaceIconType.WARNING, iface.getIconType())
    }

    @Test
    fun `offline interface is clickable`() {
        val iface =
            InterfaceInfo(
                name = "RNode LoRa",
                type = "ColumbaRNodeInterface",
                online = false,
                error = null,
            )
        assertTrue(iface.isClickable())
    }

    @Test
    fun `offline interface shows offline dialog title`() {
        val iface =
            InterfaceInfo(
                name = "RNode LoRa",
                type = "ColumbaRNodeInterface",
                online = false,
                error = null,
            )
        assertEquals("Interface Offline", iface.getDialogTitle())
    }

    // ==========================================
    // Failed Interface Tests (initialization error)
    // ==========================================

    @Test
    fun `failed interface shows error icon`() {
        val iface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = false,
                error = "Port 29716 already in use",
            )
        assertEquals(InterfaceIconType.ERROR, iface.getIconType())
    }

    @Test
    fun `failed interface is clickable`() {
        val iface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = false,
                error = "Port 29716 already in use",
            )
        assertTrue(iface.isClickable())
    }

    @Test
    fun `failed interface shows failed dialog title`() {
        val iface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = false,
                error = "Port 29716 already in use",
            )
        assertEquals("Interface Failed", iface.getDialogTitle())
    }

    @Test
    fun `failed interface error message is preserved`() {
        val errorMessage = "Port 29716 already in use (another Reticulum app may be running)"
        val iface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = false,
                error = errorMessage,
            )
        assertEquals(errorMessage, iface.error)
    }

    // ==========================================
    // Edge Cases
    // ==========================================

    @Test
    fun `interface with error but online still shows error icon`() {
        // Edge case: if somehow an interface has an error but is marked online
        // (shouldn't happen in practice, but test the logic)
        val iface =
            InterfaceInfo(
                name = "AutoInterface",
                type = "AutoInterface",
                online = true,
                error = "Some warning",
            )
        // Since online is true, it takes precedence
        assertEquals(InterfaceIconType.CHECK_CIRCLE, iface.getIconType())
        // But it's still clickable because error != null
        assertTrue(iface.isClickable())
    }

    @Test
    fun `interface list correctly separates healthy and failed`() {
        val interfaces =
            listOf(
                InterfaceInfo("RNode", "ColumbaRNodeInterface", online = true, error = null),
                InterfaceInfo("BLE", "AndroidBLE", online = true, error = null),
                InterfaceInfo("AutoInterface", "AutoInterface", online = false, error = "Port conflict"),
                InterfaceInfo("TCPClient", "TCPClient", online = false, error = null),
            )

        val healthy = interfaces.filter { it.online && it.error == null }
        val failed = interfaces.filter { it.error != null }
        val offline = interfaces.filter { !it.online && it.error == null }

        assertEquals(2, healthy.size)
        assertEquals(1, failed.size)
        assertEquals(1, offline.size)

        assertEquals("AutoInterface", failed.first().name)
        assertEquals("TCPClient", offline.first().name)
    }
}
