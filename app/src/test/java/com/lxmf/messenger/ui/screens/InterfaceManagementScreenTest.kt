package com.lxmf.messenger.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class InterfaceManagementScreenTest {

    // ========== formatAddressWithPort Tests ==========

    @Test
    fun `formatAddressWithPort with IPv4 address does not use brackets`() {
        val result = formatAddressWithPort("192.168.1.100", 4242, isIpv6 = false)
        assertEquals("192.168.1.100:4242", result)
    }

    @Test
    fun `formatAddressWithPort with IPv6 address uses brackets`() {
        val result = formatAddressWithPort("2001:db8::1", 4242, isIpv6 = true)
        assertEquals("[2001:db8::1]:4242", result)
    }

    @Test
    fun `formatAddressWithPort with Yggdrasil address uses brackets`() {
        val result = formatAddressWithPort("200:abcd:1234::1", 4242, isIpv6 = true)
        assertEquals("[200:abcd:1234::1]:4242", result)
    }

    @Test
    fun `formatAddressWithPort detects IPv6 by colon even if isIpv6 is false`() {
        // If IP contains colon, it's IPv6 regardless of flag
        val result = formatAddressWithPort("fe80::1", 8080, isIpv6 = false)
        assertEquals("[fe80::1]:8080", result)
    }

    @Test
    fun `formatAddressWithPort with null IP returns no network`() {
        val result = formatAddressWithPort(null, 4242, isIpv6 = false)
        assertEquals("no network:4242", result)
    }

    @Test
    fun `formatAddressWithPort with custom port`() {
        val result = formatAddressWithPort("10.0.0.1", 8080, isIpv6 = false)
        assertEquals("10.0.0.1:8080", result)
    }

    @Test
    fun `formatAddressWithPort with localhost IPv4`() {
        val result = formatAddressWithPort("127.0.0.1", 3000, isIpv6 = false)
        assertEquals("127.0.0.1:3000", result)
    }

    @Test
    fun `formatAddressWithPort with all zeros bind address`() {
        // 0.0.0.0 is IPv4
        val result = formatAddressWithPort("0.0.0.0", 4242, isIpv6 = false)
        assertEquals("0.0.0.0:4242", result)
    }
}
