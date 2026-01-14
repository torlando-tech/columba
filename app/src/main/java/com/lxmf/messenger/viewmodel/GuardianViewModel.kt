package com.lxmf.messenger.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lxmf.messenger.data.db.entity.PairedChildEntity
import com.lxmf.messenger.data.model.EnrichedContact
import com.lxmf.messenger.data.repository.ContactRepository
import com.lxmf.messenger.data.repository.GuardianRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "GuardianViewModel"

data class GuardianState(
    // Child side state
    val isLoading: Boolean = true,
    val hasGuardian: Boolean = false,
    val guardianName: String? = null,
    val guardianDestHash: String? = null,
    val isLocked: Boolean = false,
    val lockedTimestamp: Long = 0,
    val allowedContacts: List<Pair<String, String?>> = emptyList(),
    // QR generation state
    val qrCodeBitmap: Bitmap? = null,
    val isGeneratingQr: Boolean = false,
    // Parent side state
    val pairedChildren: List<PairedChildEntity> = emptyList(),
    val isSendingCommand: Boolean = false,
    // Common
    val error: String? = null,
)

@HiltViewModel
class GuardianViewModel
    @Inject
    constructor(
        private val guardianRepository: GuardianRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
        private val contactRepository: ContactRepository,
    ) : ViewModel() {
        private val _state = MutableStateFlow(GuardianState())
        val state: StateFlow<GuardianState> = _state.asStateFlow()

        // Parent's saved contacts - for picking contacts to add to child's allow list
        val parentContacts: StateFlow<List<EnrichedContact>> =
            contactRepository
                .getEnrichedContacts()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(5000L),
                    initialValue = emptyList(),
                )

        init {
            // Continuously observe paired children so UI updates immediately when PAIR_ACK is received
            viewModelScope.launch {
                guardianRepository.getPairedChildren().collect { children ->
                    Log.d(TAG, "Paired children updated: ${children.size} children")
                    _state.update { it.copy(pairedChildren = children) }
                }
            }
        }

        /**
         * Load the current guardian state from the repository.
         * Loads both child-side state (guardian config) and parent-side state (paired children).
         */
        fun loadGuardianState() {
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true) }

                try {
                    // Load child-side state
                    val config = guardianRepository.getGuardianConfig()
                    val allowedContacts =
                        if (config?.hasGuardian() == true) {
                            guardianRepository.getAllowedContacts().first().map { entity ->
                                entity.contactHash to entity.displayName
                            }
                        } else {
                            emptyList()
                        }

                    // Load parent-side state (paired children)
                    val pairedChildren = guardianRepository.getPairedChildren().first()

                    _state.update {
                        it.copy(
                            isLoading = false,
                            hasGuardian = config?.hasGuardian() == true,
                            guardianName = config?.guardianName,
                            guardianDestHash = config?.guardianDestinationHash,
                            isLocked = config?.isLocked == true,
                            lockedTimestamp = config?.lockedTimestamp ?: 0,
                            allowedContacts = allowedContacts,
                            pairedChildren = pairedChildren,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to load guardian state", e)
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = e.message,
                        )
                    }
                }
            }
        }

        /**
         * Generate a QR code for pairing as a parent/guardian.
         * Uses the Python guardian_crypto module to create a signed pairing payload.
         */
        fun generatePairingQr() {
            Log.d(TAG, "generatePairingQr() called")
            viewModelScope.launch {
                Log.d(TAG, "generatePairingQr: Starting QR generation")
                _state.update { it.copy(isGeneratingQr = true, error = null) }

                try {
                    // Get active identity
                    val activeIdentity = identityRepository.getActiveIdentitySync()
                    Log.d(TAG, "generatePairingQr: activeIdentity=${activeIdentity?.displayName}")
                    if (activeIdentity == null) {
                        _state.update {
                            it.copy(
                                isGeneratingQr = false,
                                error = "No active identity",
                            )
                        }
                        return@launch
                    }

                    // Call Python to generate the signed QR data
                    Log.d(TAG, "generatePairingQr: Calling reticulumProtocol.generateGuardianPairingQr()")
                    val qrData = reticulumProtocol.generateGuardianPairingQr()
                    Log.d(TAG, "generatePairingQr: qrData=${qrData?.take(50)}")
                    if (qrData == null) {
                        _state.update {
                            it.copy(
                                isGeneratingQr = false,
                                error = "Failed to generate QR code",
                            )
                        }
                        return@launch
                    }

                    // Generate QR code bitmap
                    val bitmap = generateQrCodeBitmap(qrData)

                    _state.update {
                        it.copy(
                            isGeneratingQr = false,
                            qrCodeBitmap = bitmap,
                        )
                    }

                    Log.i(TAG, "Generated guardian pairing QR code")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to generate pairing QR", e)
                    _state.update {
                        it.copy(
                            isGeneratingQr = false,
                            error = e.message,
                        )
                    }
                }
            }
        }

        /**
         * Parse and validate a scanned guardian QR code, then pair with the guardian.
         * Also sends a PAIR_ACK message to the guardian so they know pairing succeeded.
         */
        suspend fun pairWithGuardian(qrData: String): Boolean {
            Log.d(TAG, "pairWithGuardian called with qrData: ${qrData.take(50)}...")
            return try {
                // Parse and validate the QR data using Python
                Log.d(TAG, "Calling parseGuardianPairingQr...")
                val result = reticulumProtocol.parseGuardianPairingQr(qrData)
                Log.d(TAG, "parseGuardianPairingQr result: $result")
                if (result == null) {
                    Log.e(TAG, "Invalid guardian QR code - parseGuardianPairingQr returned null")
                    _state.update { it.copy(error = "Invalid QR code format") }
                    return false
                }

                val (guardianDestHash, guardianPubKey) = result

                // Save the guardian info
                guardianRepository.setGuardian(
                    guardianDestinationHash = guardianDestHash,
                    guardianPublicKey = guardianPubKey,
                    guardianName = null, // Can be updated later
                )

                Log.i(TAG, "Paired with guardian: $guardianDestHash")

                // Send PAIR_ACK to guardian so they know we paired
                try {
                    val sent = reticulumProtocol.sendGuardianCommand(
                        destinationHash = guardianDestHash,
                        command = "PAIR_ACK",
                        payload = emptyMap(),
                    )
                    if (sent) {
                        Log.i(TAG, "Sent PAIR_ACK to guardian")
                    } else {
                        Log.w(TAG, "Failed to send PAIR_ACK to guardian (non-fatal)")
                    }
                } catch (e: Exception) {
                    // Non-fatal - pairing is still valid
                    Log.w(TAG, "Failed to send PAIR_ACK: ${e.message}")
                }

                // Refresh state
                loadGuardianState()
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pair with guardian", e)
                _state.update { it.copy(error = e.message) }
                false
            }
        }

        /**
         * Remove parental controls from this device.
         */
        suspend fun unpairFromGuardian() {
            try {
                guardianRepository.removeGuardian()
                Log.i(TAG, "Unpaired from guardian")
                loadGuardianState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unpair from guardian", e)
                _state.update { it.copy(error = e.message) }
            }
        }

        /**
         * Add a contact to the allowed list.
         */
        suspend fun addAllowedContact(
            contactHash: String,
            displayName: String?,
        ) {
            try {
                guardianRepository.addAllowedContact(contactHash, displayName)
                loadGuardianState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add allowed contact", e)
                _state.update { it.copy(error = e.message) }
            }
        }

        /**
         * Remove a contact from the allowed list.
         */
        suspend fun removeAllowedContact(contactHash: String) {
            try {
                guardianRepository.removeAllowedContact(contactHash)
                loadGuardianState()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove allowed contact", e)
                _state.update { it.copy(error = e.message) }
            }
        }

        // ========== Parent Side Methods ==========

        /**
         * Lock a paired child's device.
         */
        fun lockChild(childDestHash: String) {
            viewModelScope.launch {
                _state.update { it.copy(isSendingCommand = true, error = null) }
                try {
                    val sent = reticulumProtocol.sendGuardianCommand(
                        destinationHash = childDestHash,
                        command = "LOCK",
                        payload = emptyMap(),
                    )
                    if (sent) {
                        guardianRepository.updateChildLockState(childDestHash, true)
                        Log.i(TAG, "Sent LOCK command to $childDestHash")
                        loadGuardianState()
                    } else {
                        _state.update { it.copy(error = "Failed to send lock command") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to lock child", e)
                    _state.update { it.copy(error = e.message) }
                } finally {
                    _state.update { it.copy(isSendingCommand = false) }
                }
            }
        }

        /**
         * Unlock a paired child's device.
         */
        fun unlockChild(childDestHash: String) {
            viewModelScope.launch {
                _state.update { it.copy(isSendingCommand = true, error = null) }
                try {
                    val sent = reticulumProtocol.sendGuardianCommand(
                        destinationHash = childDestHash,
                        command = "UNLOCK",
                        payload = emptyMap(),
                    )
                    if (sent) {
                        guardianRepository.updateChildLockState(childDestHash, false)
                        Log.i(TAG, "Sent UNLOCK command to $childDestHash")
                        loadGuardianState()
                    } else {
                        _state.update { it.copy(error = "Failed to send unlock command") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unlock child", e)
                    _state.update { it.copy(error = e.message) }
                } finally {
                    _state.update { it.copy(isSendingCommand = false) }
                }
            }
        }

        /**
         * Add a contact to a child's allow list.
         */
        fun addChildAllowedContact(childDestHash: String, contactHash: String, displayName: String?) {
            viewModelScope.launch {
                _state.update { it.copy(isSendingCommand = true, error = null) }
                try {
                    // Build contact object with hash and optional name
                    val contact = mutableMapOf<String, Any>("hash" to contactHash)
                    displayName?.let { contact["name"] = it }

                    // Payload format expected by GuardianCommandProcessor.executeAllowAdd:
                    // { "contacts": [{"hash": "...", "name": "..."}] }
                    val payload = mapOf("contacts" to listOf(contact))

                    val sent = reticulumProtocol.sendGuardianCommand(
                        destinationHash = childDestHash,
                        command = "ALLOW_ADD",
                        payload = payload,
                    )
                    if (sent) {
                        Log.i(TAG, "Sent ALLOW_ADD command to $childDestHash for $contactHash")
                    } else {
                        _state.update { it.copy(error = "Failed to send allow add command") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to add allowed contact on child", e)
                    _state.update { it.copy(error = e.message) }
                } finally {
                    _state.update { it.copy(isSendingCommand = false) }
                }
            }
        }

        /**
         * Remove a contact from a child's allow list.
         */
        fun removeChildAllowedContact(childDestHash: String, contactHash: String) {
            viewModelScope.launch {
                _state.update { it.copy(isSendingCommand = true, error = null) }
                try {
                    // Payload format expected by GuardianCommandProcessor.executeAllowRemove:
                    // { "contacts": ["hash1", "hash2"] }
                    val payload = mapOf("contacts" to listOf(contactHash))

                    val sent = reticulumProtocol.sendGuardianCommand(
                        destinationHash = childDestHash,
                        command = "ALLOW_REMOVE",
                        payload = payload,
                    )
                    if (sent) {
                        Log.i(TAG, "Sent ALLOW_REMOVE command to $childDestHash for $contactHash")
                    } else {
                        _state.update { it.copy(error = "Failed to send allow remove command") }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove allowed contact on child", e)
                    _state.update { it.copy(error = e.message) }
                } finally {
                    _state.update { it.copy(isSendingCommand = false) }
                }
            }
        }

        /**
         * Remove a paired child (unpair from parent side).
         */
        fun removePairedChild(childDestHash: String) {
            viewModelScope.launch {
                try {
                    guardianRepository.removePairedChild(childDestHash)
                    Log.i(TAG, "Removed paired child: $childDestHash")
                    loadGuardianState()
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to remove paired child", e)
                    _state.update { it.copy(error = e.message) }
                }
            }
        }

        private fun generateQrCodeBitmap(data: String): Bitmap {
            val size = 512
            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(data, BarcodeFormat.QR_CODE, size, size)
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap.setPixel(
                        x,
                        y,
                        if (bitMatrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE,
                    )
                }
            }
            return bitmap
        }
    }
