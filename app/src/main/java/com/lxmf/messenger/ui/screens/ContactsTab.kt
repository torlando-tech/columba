package com.lxmf.messenger.ui.screens

/**
 * Tabs for the Contacts screen.
 *
 * MY_CONTACTS: Shows saved contacts with location sharing indicators
 * NETWORK: Shows network announces (discovered peers)
 */
enum class ContactsTab(val displayName: String) {
    MY_CONTACTS("My Contacts"),
    NETWORK("Network"),
}
