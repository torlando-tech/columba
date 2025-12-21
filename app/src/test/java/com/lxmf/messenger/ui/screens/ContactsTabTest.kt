package com.lxmf.messenger.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for ContactsTab enum.
 */
class ContactsTabTest {
    @Test
    fun `ContactsTab has two entries`() {
        assertEquals(2, ContactsTab.entries.size)
    }

    @Test
    fun `MY_CONTACTS has correct display name`() {
        assertEquals("My Contacts", ContactsTab.MY_CONTACTS.displayName)
    }

    @Test
    fun `NETWORK has correct display name`() {
        assertEquals("Network", ContactsTab.NETWORK.displayName)
    }

    @Test
    fun `entries are in correct order`() {
        val entries = ContactsTab.entries
        assertEquals(ContactsTab.MY_CONTACTS, entries[0])
        assertEquals(ContactsTab.NETWORK, entries[1])
    }

    @Test
    fun `tabs have distinct display names`() {
        assertNotEquals(
            ContactsTab.MY_CONTACTS.displayName,
            ContactsTab.NETWORK.displayName,
        )
    }

    @Test
    fun `valueOf returns correct tab for MY_CONTACTS`() {
        assertEquals(ContactsTab.MY_CONTACTS, ContactsTab.valueOf("MY_CONTACTS"))
    }

    @Test
    fun `valueOf returns correct tab for NETWORK`() {
        assertEquals(ContactsTab.NETWORK, ContactsTab.valueOf("NETWORK"))
    }
}
