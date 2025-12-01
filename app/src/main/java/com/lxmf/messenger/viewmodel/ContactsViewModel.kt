package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.util.IdentityQrCodeUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Data class for grouping contacts by pinned status
 */
@androidx.compose.runtime.Immutable
data class ContactGroups(
    val pinned: List<EnrichedContact>,
    val all: List<EnrichedContact>,
)

@HiltViewModel
class ContactsViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
    ) : ViewModel() {
        companion object {
            private const val TAG = "ContactsViewModel"
        }

        // Search query state
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery

        // All enriched contacts (includes network status and conversation data)
        val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = emptyList(),
                )

        // Filtered contacts based on search query
        val filteredContacts: StateFlow<List<EnrichedContact>> =
            combine(
                contacts,
                searchQuery,
            ) { contacts, query ->
                if (query.isBlank()) {
                    contacts
                } else {
                    contacts.filter { contact ->
                        contact.displayName.contains(query, ignoreCase = true) ||
                            contact.destinationHash.contains(query, ignoreCase = true) ||
                            contact.announceName?.contains(query, ignoreCase = true) == true ||
                            contact.getTagsList().any { it.contains(query, ignoreCase = true) }
                    }
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = emptyList(),
            )

        // Grouped contacts for section headers (pinned vs all)
        val groupedContacts: StateFlow<ContactGroups> =
            filteredContacts
                .combine(MutableStateFlow(Unit)) { contacts, _ ->
                    ContactGroups(
                        pinned = contacts.filter { it.isPinned },
                        all = contacts.filterNot { it.isPinned },
                    )
                }
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = ContactGroups(emptyList(), emptyList()),
                )

        // Contact count
        val contactCount: StateFlow<Int> =
            contactRepository.getContactCountFlow()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.Eagerly,
                    initialValue = 0,
                )

        /**
         * Update search query
         */
        fun onSearchQueryChanged(query: String) {
            _searchQuery.value = query
        }

        /**
         * Add a contact from an announce (when user stars an announce)
         */
        fun addContactFromAnnounce(
            destinationHash: String,
            publicKey: ByteArray,
            announceName: String? = null,
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.addContactFromAnnounce(
                        destinationHash = destinationHash,
                        publicKey = publicKey,
                        announceName = announceName,
                    )
                    Log.d(TAG, "Added contact from announce: $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add contact from announce: $destinationHash", e)
                }
            }
        }

        /**
         * Add a contact from a QR code scan
         */
        fun addContactFromQrCode(
            qrData: String,
            nickname: String? = null,
        ) {
            viewModelScope.launch {
                try {
                    val decoded = IdentityQrCodeUtils.decodeFromQrString(qrData)
                    if (decoded != null) {
                        val hashHex = decoded.destinationHash.joinToString("") { "%02x".format(it) }
                        contactRepository.addContactFromQrCode(
                            destinationHash = hashHex,
                            publicKey = decoded.publicKey,
                            nickname = nickname,
                        )
                        Log.d(TAG, "Added contact from QR code: $hashHex")
                    } else {
                        Log.e(TAG, "Failed to decode QR code data")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add contact from QR code", e)
                }
            }
        }

        /**
         * Add a contact manually (paste lxma:// string or enter hash + key separately)
         */
        fun addContactManually(
            destinationHash: String,
            publicKey: ByteArray,
            nickname: String? = null,
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.addContactManually(
                        destinationHash = destinationHash,
                        publicKey = publicKey,
                        nickname = nickname,
                    )
                    Log.d(TAG, "Added contact manually: $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add contact manually: $destinationHash", e)
                }
            }
        }

        /**
         * Delete a contact
         */
        fun deleteContact(destinationHash: String) {
            viewModelScope.launch {
                try {
                    contactRepository.deleteContact(destinationHash)
                    Log.d(TAG, "Deleted contact: $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to delete contact: $destinationHash", e)
                }
            }
        }

        /**
         * Update contact nickname
         */
        fun updateNickname(
            destinationHash: String,
            nickname: String?,
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.updateNickname(destinationHash, nickname)
                    Log.d(TAG, "Updated nickname for $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update nickname for $destinationHash", e)
                }
            }
        }

        /**
         * Update contact notes
         */
        fun updateNotes(
            destinationHash: String,
            notes: String?,
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.updateNotes(destinationHash, notes)
                    Log.d(TAG, "Updated notes for $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to update notes for $destinationHash", e)
                }
            }
        }

        /**
         * Toggle pin status for a contact
         */
        fun togglePin(destinationHash: String) {
            viewModelScope.launch {
                try {
                    contactRepository.togglePin(destinationHash)
                    Log.d(TAG, "Toggled pin for $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to toggle pin for $destinationHash", e)
                }
            }
        }

        /**
         * Check if a contact already exists by destination hash.
         * Returns the existing contact if found, null otherwise.
         *
         * This method queries the database directly to avoid timing issues with the StateFlow.
         * If the contact exists in database but not in StateFlow, it fetches and creates a minimal EnrichedContact.
         */
        suspend fun checkContactExists(destinationHash: String): EnrichedContact? {
            return try {
                // First check the database directly (avoids timing/race conditions with StateFlow)
                val existsInDb = contactRepository.hasContact(destinationHash)
                if (!existsInDb) {
                    return null
                }

                // Contact exists in database, try to find it in the loaded contacts
                // Use case-insensitive comparison for safety
                val normalizedHash = destinationHash.lowercase()
                val fromStateFlow =
                    contacts.value.find {
                        it.destinationHash.lowercase() == normalizedHash
                    }

                // If found in StateFlow, return it
                if (fromStateFlow != null) {
                    return fromStateFlow
                }

                // Contact exists in DB but not in StateFlow yet (timing issue)
                // Fetch from database and create a minimal EnrichedContact
                val contactEntity = contactRepository.getContact(destinationHash)
                if (contactEntity != null) {
                    // Create a minimal EnrichedContact with just the basic info
                    EnrichedContact(
                        destinationHash = contactEntity.destinationHash,
                        publicKey = contactEntity.publicKey,
                        displayName = contactEntity.customNickname ?: contactEntity.destinationHash,
                        customNickname = contactEntity.customNickname,
                        announceName = null,
                        lastSeenTimestamp = null,
                        hops = null,
                        isOnline = false,
                        hasConversation = false,
                        unreadCount = 0,
                        lastMessageTimestamp = null,
                        notes = contactEntity.notes,
                        tags = contactEntity.tags,
                        addedTimestamp = contactEntity.addedTimestamp,
                        addedVia = contactEntity.addedVia,
                        isPinned = contactEntity.isPinned,
                    )
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to check if contact exists: $destinationHash", e)
                null
            }
        }

        /**
         * Decode QR code data and return the destination hash if valid.
         * This is used for checking duplicates before showing the confirmation dialog.
         */
        fun decodeQrCode(qrData: String): Pair<String, ByteArray>? {
            return try {
                val decoded = IdentityQrCodeUtils.decodeFromQrString(qrData)
                if (decoded != null) {
                    val hashHex = decoded.destinationHash.joinToString("") { "%02x".format(it) }
                    Pair(hashHex, decoded.publicKey)
                } else {
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to decode QR code data", e)
                null
            }
        }
    }
