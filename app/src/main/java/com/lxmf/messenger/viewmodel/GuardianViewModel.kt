package com.lxmf.messenger.viewmodel

import android.graphics.Bitmap
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.lxmf.messenger.data.repository.GuardianRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

private const val TAG = "GuardianViewModel"

data class GuardianState(
    val isLoading: Boolean = true,
    val hasGuardian: Boolean = false,
    val guardianName: String? = null,
    val guardianDestHash: String? = null,
    val isLocked: Boolean = false,
    val lockedTimestamp: Long = 0,
    val allowedContacts: List<Pair<String, String?>> = emptyList(),
    val qrCodeBitmap: Bitmap? = null,
    val isGeneratingQr: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class GuardianViewModel
    @Inject
    constructor(
        private val guardianRepository: GuardianRepository,
        private val identityRepository: IdentityRepository,
        private val reticulumProtocol: ReticulumProtocol,
    ) : ViewModel() {
        private val _state = MutableStateFlow(GuardianState())
        val state: StateFlow<GuardianState> = _state.asStateFlow()

        /**
         * Load the current guardian state from the repository.
         */
        fun loadGuardianState() {
            viewModelScope.launch {
                _state.update { it.copy(isLoading = true) }

                try {
                    val config = guardianRepository.getGuardianConfig()
                    val allowedContacts =
                        if (config?.hasGuardian() == true) {
                            guardianRepository.getAllowedContacts().first().map { entity ->
                                entity.contactHash to entity.displayName
                            }
                        } else {
                            emptyList()
                        }

                    _state.update {
                        it.copy(
                            isLoading = false,
                            hasGuardian = config?.hasGuardian() == true,
                            guardianName = config?.guardianName,
                            guardianDestHash = config?.guardianDestinationHash,
                            isLocked = config?.isLocked == true,
                            lockedTimestamp = config?.lockedTimestamp ?: 0,
                            allowedContacts = allowedContacts,
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
