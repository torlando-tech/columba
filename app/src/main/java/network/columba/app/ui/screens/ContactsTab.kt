package network.columba.app.ui.screens

import androidx.annotation.StringRes
import network.columba.app.R

/**
 * Tabs for the Contacts screen.
 *
 * MY_CONTACTS: Shows saved contacts with location sharing indicators
 * NETWORK: Shows network announces (discovered peers)
 */
enum class ContactsTab(
    @param:StringRes val displayNameRes: Int,
) {
    MY_CONTACTS(R.string.contacts_tab_my_contacts_title),
    NETWORK(R.string.settings_network_title),
}
