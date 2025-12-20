package com.lxmf.messenger.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for InterfaceBandwidth bandwidth estimates and calculations.
 */
class InterfaceBandwidthTest {
    @Test
    fun `bandwidth constants are positive`() {
        assertTrue(InterfaceBandwidth.RNODE_SF12_125KHZ_BPS > 0)
        assertTrue(InterfaceBandwidth.RNODE_SF7_500KHZ_BPS > 0)
        assertTrue(InterfaceBandwidth.RNODE_DEFAULT_BPS > 0)
        assertTrue(InterfaceBandwidth.ANDROID_BLE_BPS > 0)
        assertTrue(InterfaceBandwidth.TCP_CLIENT_BPS > 0)
        assertTrue(InterfaceBandwidth.AUTO_INTERFACE_BPS > 0)
        assertTrue(InterfaceBandwidth.UDP_INTERFACE_BPS > 0)
    }

    @Test
    fun `RNode SF7 500kHz is faster than SF12 125kHz`() {
        assertTrue(
            InterfaceBandwidth.RNODE_SF7_500KHZ_BPS > InterfaceBandwidth.RNODE_SF12_125KHZ_BPS,
        )
    }

    @Test
    fun `calculateRNodeBandwidth returns reasonable values for SF7 500kHz`() {
        val bandwidth = InterfaceBandwidth.calculateRNodeBandwidth(
            spreadingFactor = 7,
            bandwidthHz = 500000,
        )
        // Should be in the ballpark of 5000 bps
        assertTrue("SF7/500kHz should be > 1000 bps", bandwidth > 1000)
        assertTrue("SF7/500kHz should be < 50000 bps", bandwidth < 50000)
    }

    @Test
    fun `calculateRNodeBandwidth returns reasonable values for SF12 125kHz`() {
        val bandwidth = InterfaceBandwidth.calculateRNodeBandwidth(
            spreadingFactor = 12,
            bandwidthHz = 125000,
        )
        // Should be in the ballpark of 50-200 bps
        assertTrue("SF12/125kHz should be >= minimum", bandwidth >= InterfaceBandwidth.RNODE_SF12_125KHZ_BPS)
        assertTrue("SF12/125kHz should be < 500 bps", bandwidth < 500)
    }

    @Test
    fun `calculateRNodeBandwidth higher SF means lower bandwidth`() {
        val sf7 = InterfaceBandwidth.calculateRNodeBandwidth(7, 125000)
        val sf10 = InterfaceBandwidth.calculateRNodeBandwidth(10, 125000)
        val sf12 = InterfaceBandwidth.calculateRNodeBandwidth(12, 125000)

        assertTrue("SF7 should be faster than SF10", sf7 > sf10)
        assertTrue("SF10 should be faster than SF12", sf10 > sf12)
    }

    @Test
    fun `calculateRNodeBandwidth higher BW means higher bandwidth`() {
        val bw125 = InterfaceBandwidth.calculateRNodeBandwidth(10, 125000)
        val bw250 = InterfaceBandwidth.calculateRNodeBandwidth(10, 250000)
        val bw500 = InterfaceBandwidth.calculateRNodeBandwidth(10, 500000)

        assertTrue("250kHz should be faster than 125kHz", bw250 > bw125)
        assertTrue("500kHz should be faster than 250kHz", bw500 > bw250)
    }

    @Test
    fun `getBandwidthForInterfaceType returns correct values`() {
        assertEquals(
            InterfaceBandwidth.RNODE_DEFAULT_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("RNodeInterface"),
        )
        assertEquals(
            InterfaceBandwidth.ANDROID_BLE_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("AndroidBLE"),
        )
        assertEquals(
            InterfaceBandwidth.TCP_CLIENT_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("TCPClient"),
        )
        assertEquals(
            InterfaceBandwidth.AUTO_INTERFACE_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("AutoInterface"),
        )
        assertEquals(
            InterfaceBandwidth.UDP_INTERFACE_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("UDPInterface"),
        )
    }

    @Test
    fun `getBandwidthForInterfaceType is case insensitive`() {
        assertEquals(
            InterfaceBandwidth.RNODE_DEFAULT_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("rnodeinterface"),
        )
        assertEquals(
            InterfaceBandwidth.RNODE_DEFAULT_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("RNODE"),
        )
    }

    @Test
    fun `getBandwidthForInterfaceType returns TCP default for unknown`() {
        assertEquals(
            InterfaceBandwidth.TCP_CLIENT_BPS,
            InterfaceBandwidth.getBandwidthForInterfaceType("UnknownInterface"),
        )
    }

    @Test
    fun `isSlowInterface returns true for RNode`() {
        assertTrue(InterfaceBandwidth.isSlowInterface("RNodeInterface"))
        assertTrue(InterfaceBandwidth.isSlowInterface("rnode"))
    }

    @Test
    fun `isSlowInterface returns true for BLE`() {
        assertTrue(InterfaceBandwidth.isSlowInterface("AndroidBLE"))
        assertTrue(InterfaceBandwidth.isSlowInterface("ble"))
    }

    @Test
    fun `isSlowInterface returns false for fast interfaces`() {
        assertFalse(InterfaceBandwidth.isSlowInterface("TCPClient"))
        assertFalse(InterfaceBandwidth.isSlowInterface("AutoInterface"))
        assertFalse(InterfaceBandwidth.isSlowInterface("UDP"))
    }
}
