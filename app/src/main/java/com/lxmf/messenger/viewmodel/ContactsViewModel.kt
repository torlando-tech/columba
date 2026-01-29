package com.lxmf.messenger.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.service.PropagationNodeManager
import com.lxmf.messenger.service.RelayInfo
import com.lxmf.messenger.util.IdentityQrCodeUtils
import com.lxmf.messenger.util.validation.IdentityInput
import com.lxmf.messenger.util.validation.InputValidator
import com.lxmf.messenger.util.validation.ValidationResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Result of adding a contact via the new unified input method.
 */
sealed class AddContactResult {
    /**
     * Contact was added successfully with full identity (immediately active).
     */
    object Success : AddContactResult()

    /**
     * Contact was added with pending identity status.
     * Network search has been initiated to resolve the public key.
     */
    object PendingIdentity : AddContactResult()

    /**
     * Contact already exists in the contact list.
     */
    data class AlreadyExists(
        val existingContact: EnrichedContact,
    ) : AddContactResult()

    /**
     * An error occurred while adding the contact.
     */
    data class Error(
        val message: String,
    ) : AddContactResult()
}

/**
 * Data class for grouping contacts by section (relay, pinned, all)
 */
@androidx.compose.runtime.Immutable
data class ContactGroups(
    // Current relay (if any)
    val relay: EnrichedContact?,
    val pinned: List<EnrichedContact>,
    val all: List<EnrichedContact>,
)

/**
 * UI state for the Contacts tab, including loading status.
 */
data class ContactsState(
    val groupedContacts: ContactGroups = ContactGroups(null, emptyList(), emptyList()),
    val isLoading: Boolean = true,
)

@HiltViewModel
class ContactsViewModel
    @Inject
    constructor(
        private val contactRepository: ContactRepository,
        private val propagationNodeManager: PropagationNodeManager,
    ) : ViewModel() {
        companion object {
            private const val TAG = "ContactsViewModel"
        }

        // Search query state
        private val _searchQuery = MutableStateFlow("")
        val searchQuery: StateFlow<String> = _searchQuery

        // Current relay info (includes isAutoSelected for showing "(auto)" badge)
        val currentRelayInfo: StateFlow<RelayInfo?> = propagationNodeManager.currentRelay

        // All enriched contacts (includes network status and conversation data)
        val contacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        init {
            Log.d(TAG, "ContactsViewModel created")
            viewModelScope.launch {
                contacts.collect { list ->
                    Log.d(TAG, "Contacts flow emitted ${list.size} contacts")
                    list.forEach { c ->
                        Log.d(TAG, "  - ${c.displayName} (${c.destinationHash.take(8)}), isMyRelay=${c.isMyRelay}")
                    }
                }
            }
        }

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
                started = SharingStarted.WhileSubscribed(5000L),
                initialValue = emptyList(),
            )

        // Grouped contacts for section headers (relay, pinned, all), with loading state
        // onStart emits loading state each time flow is collected (tab switch, screen entry)
        val contactsState: StateFlow<ContactsState> =
            filteredContacts
                .map { contacts ->
                    val relay = contacts.find { it.isMyRelay }
                    Log.d(TAG, "ContactsState: ${contacts.size} filtered, relay=${relay?.displayName}")
                    ContactsState(
                        groupedContacts =
                            ContactGroups(
                                relay = relay,
                                pinned = contacts.filter { it.isPinned && !it.isMyRelay },
                                all = contacts.filterNot { it.isPinned || it.isMyRelay },
                            ),
                        isLoading = false,
                    )
                }.onStart {
                    emit(ContactsState(isLoading = true))
                }.stateIn(
                    scope = viewModelScope,
                    started =
                        SharingStarted.WhileSubscribed(
                            stopTimeoutMillis = 0,
                            replayExpirationMillis = 0,
                        ),
                    initialValue = ContactsState(isLoading = true),
                )

        // Contact count
        val contactCount: StateFlow<Int> =
            contactRepository
                .getContactCountFlow()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
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
        ) {
            viewModelScope.launch {
                try {
                    contactRepository.addContactFromAnnounce(
                        destinationHash = destinationHash,
                        publicKey = publicKey,
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
         * Unset a relay contact and delete it, then trigger auto-selection of a new relay.
         * This should be called when the user confirms removing their current relay.
         */
        fun unsetRelayAndDelete(destinationHash: String) {
            viewModelScope.launch {
                try {
                    // IMPORTANT: Set exclusion BEFORE delete to prevent immediate re-selection
                    // The delete triggers a Room Flow emission that could cause auto-selection
                    // before onRelayDeleted() is called
                    propagationNodeManager.excludeFromAutoSelect(destinationHash)

                    // Delete the contact (this also clears isMyRelay flag in database)
                    contactRepository.deleteContact(destinationHash)

                    // Notify PropagationNodeManager to trigger auto-selection of new relay
                    propagationNodeManager.onRelayDeleted()

                    Log.d(TAG, "Unset relay and deleted: $destinationHash")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unset relay: $destinationHash", e)
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
                        status = contactEntity.status,
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
        fun decodeQrCode(qrData: String): Pair<String, ByteArray>? =
            try {
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

        // ========== SIDEBAND IMPORT SUPPORT ==========

        /**
         * Add a contact from user input (supports both full lxma:// URL and hash-only).
         *
         * This method handles the unified input flow:
         * 1. Parses the input to determine format
         * 2. Checks for duplicate contacts
         * 3. For hash-only input, checks local caches for existing identity
         * 4. Adds contact as ACTIVE (if identity known) or PENDING_IDENTITY (if not)
         *
         * @param input The user's input (lxma:// URL or 32-char hex hash)
         * @param nickname Optional display name for the contact
         * @return AddContactResult indicating the outcome
         */
        suspend fun addContactFromInput(
            input: String,
            nickname: String? = null,
        ): AddContactResult {
            return try {
                // Step 1: Parse the input
                val parsed = InputValidator.parseIdentityInput(input)
                if (parsed is ValidationResult.Error) {
                    Log.w(TAG, "Input validation failed: ${parsed.message}")
                    return AddContactResult.Error(parsed.message)
                }

                val identityInput = (parsed as ValidationResult.Success).value

                // Step 2: Get destination hash for duplicate check
                val destinationHash =
                    when (identityInput) {
                        is IdentityInput.FullIdentity -> identityInput.destinationHash
                        is IdentityInput.DestinationHashOnly -> identityInput.destinationHash
                    }

                // Step 3: Check for duplicate
                val existingContact = checkContactExists(destinationHash)
                if (existingContact != null) {
                    Log.d(TAG, "Contact already exists: $destinationHash")
                    return AddContactResult.AlreadyExists(existingContact)
                }

                // Step 4: Handle based on input type
                when (identityInput) {
                    is IdentityInput.FullIdentity -> {
                        // Full identity - add immediately as ACTIVE
                        val result =
                            contactRepository.addContactManually(
                                destinationHash = identityInput.destinationHash,
                                publicKey = identityInput.publicKey,
                                nickname = nickname,
                            )
                        if (result.isSuccess) {
                            Log.d(TAG, "Added contact with full identity: $destinationHash")
                            AddContactResult.Success
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "Unknown error"
                            Log.e(TAG, "Failed to add contact: $error")
                            AddContactResult.Error(error)
                        }
                    }

                    is IdentityInput.DestinationHashOnly -> {
                        // Hash only - check announces first, then add as PENDING_IDENTITY if needed
                        val result =
                            contactRepository.addPendingContact(
                                destinationHash = identityInput.destinationHash,
                                nickname = nickname,
                            )
                        if (result.isSuccess) {
                            when (result.getOrNull()) {
                                is ContactRepository.AddPendingResult.ResolvedImmediately -> {
                                    Log.d(TAG, "Contact resolved from existing announce: $destinationHash")
                                    AddContactResult.Success
                                }
                                is ContactRepository.AddPendingResult.AddedAsPending -> {
                                    Log.d(TAG, "Added pending contact: $destinationHash")
                                    // TODO: Trigger network path request here when service integration is ready
                                    AddContactResult.PendingIdentity
                                }
                                null -> {
                                    Log.e(TAG, "Unexpected null result from addPendingContact")
                                    AddContactResult.Error("Unexpected error")
                                }
                            }
                        } else {
                            val error = result.exceptionOrNull()?.message ?: "Unknown error"
                            Log.e(TAG, "Failed to add pending contact: $error")
                            AddContactResult.Error(error)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error adding contact from input", e)
                AddContactResult.Error(e.message ?: "Unknown error")
            }
        }

        /**
         * Retry identity resolution for a pending or unresolved contact.
         * Resets status to PENDING_IDENTITY and triggers a new network search.
         *
         * @param destinationHash The contact's destination hash
         */
        fun retryIdentityResolution(destinationHash: String) {
            viewModelScope.launch {
                try {
                    val result = contactRepository.resetContactForRetry(destinationHash)
                    if (result.isSuccess) {
                        Log.d(TAG, "Reset contact for retry: $destinationHash")
                        // TODO: Trigger network path request here when service integration is ready
                    } else {
                        Log.e(TAG, "Failed to reset contact for retry: ${result.exceptionOrNull()?.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error retrying identity resolution", e)
                }
            }
        }
    }
