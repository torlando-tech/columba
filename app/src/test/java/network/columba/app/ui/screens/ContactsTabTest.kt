package network.columba.app.ui.screens

import network.columba.app.R
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
        assertEquals(R.string.contacts_tab_my_contacts_title, ContactsTab.MY_CONTACTS.displayNameRes)
    }

    @Test
    fun `NETWORK has correct display name`() {
        assertEquals(R.string.settings_network_title, ContactsTab.NETWORK.displayNameRes)
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
            ContactsTab.MY_CONTACTS.displayNameRes,
            ContactsTab.NETWORK.displayNameRes,
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
