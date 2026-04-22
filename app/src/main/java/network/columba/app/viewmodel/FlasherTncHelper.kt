package network.columba.app.viewmodel

import android.content.Context
import android.util.Log
import network.columba.app.reticulum.flasher.FirmwareDownloader
import network.columba.app.reticulum.flasher.FirmwareSource
import network.columba.app.reticulum.flasher.FrequencyBand
import network.columba.app.reticulum.flasher.RNodeBoard
import network.columba.app.reticulum.flasher.RNodeFlasher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Extracted helper for TNC configuration and custom firmware operations in FlasherViewModel.
 * Reduces the ViewModel class size by handling these cohesive feature groups externally.
 */
internal class FlasherTncHelper(
    private val context: Context,
    private val flasher: RNodeFlasher,
    private val state: MutableStateFlow<FlasherUiState>,
) {
    companion object {
        private const val TAG = "Columba:FlasherTnc"
    }

    // ==================== TNC Configuration ====================

    fun selectTncRegion(region: network.columba.app.data.model.FrequencyRegion) {
        state.update {
            it.copy(
                tncSelectedRegion = region,
                tncFrequencyMhz = String.format(java.util.Locale.US, "%.3f", region.frequency / 1_000_000.0),
                tncTxPower = region.defaultTxPower.toString(),
            )
        }
    }

    fun selectTncPreset(preset: network.columba.app.data.model.ModemPreset) {
        state.update {
            it.copy(
                tncSelectedPreset = preset,
                tncBandwidthKhz = (preset.bandwidth / 1000).toString(),
                tncSpreadingFactor = preset.spreadingFactor.toString(),
                tncCodingRate = preset.codingRate.toString(),
            )
        }
    }

    fun updateTncFrequency(value: String) {
        state.update { it.copy(tncFrequencyMhz = value) }
    }

    fun updateTncBandwidth(value: String) {
        state.update { it.copy(tncBandwidthKhz = value) }
    }

    fun updateTncSpreadingFactor(value: String) {
        state.update { it.copy(tncSpreadingFactor = value) }
    }

    fun updateTncCodingRate(value: String) {
        state.update { it.copy(tncCodingRate = value) }
    }

    fun updateTncTxPower(value: String) {
        state.update { it.copy(tncTxPower = value) }
    }

    fun skipTncConfiguration() {
        state.update {
            it.copy(
                currentStep = FlasherStep.COMPLETE,
                flashResult = FlashResult.Success(null),
            )
        }
    }

    // ==================== Custom Firmware ====================

    suspend fun flashCustomFirmware(
        deviceId: Int,
        board: RNodeBoard,
        band: FrequencyBand,
    ): Boolean {
        val currentState = state.value
        val uri = currentState.customFirmwareUri
        val url = currentState.customFirmwareUrl

        val firmwareBytes: ByteArray? =
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to read custom firmware URI", e)
                    null
                }
            } else if (url.isNotEmpty()) {
                var downloaded: ByteArray? = null
                flasher.firmwareDownloader.downloadFromUrl(
                    url,
                    object : FirmwareDownloader.DownloadCallback {
                        override fun onProgress(
                            bytesDownloaded: Long,
                            totalBytes: Long,
                        ) {
                            val percent =
                                if (totalBytes > 0) (bytesDownloaded * 20 / totalBytes).toInt() else 5
                            state.update {
                                it.copy(
                                    flashProgress = percent,
                                    flashMessage = "Downloading: ${bytesDownloaded / 1024}KB / ${totalBytes / 1024}KB",
                                )
                            }
                        }

                        override fun onComplete(data: ByteArray) {
                            downloaded = data
                        }

                        override fun onError(error: String) {
                            Log.e(TAG, "Custom URL download error: $error")
                        }
                    },
                )
                downloaded
            } else {
                null
            }

        if (firmwareBytes == null) {
            state.update {
                it.copy(
                    currentStep = FlasherStep.COMPLETE,
                    isFlashing = false,
                    flashResult = FlashResult.Failure("Failed to read custom firmware"),
                )
            }
            return false
        }

        val firmwarePackage =
            flasher.firmwareRepository.saveFirmware(
                FirmwareSource.Custom,
                board,
                "custom",
                band,
                firmwareBytes,
            )

        if (firmwarePackage == null) {
            state.update {
                it.copy(
                    currentStep = FlasherStep.COMPLETE,
                    isFlashing = false,
                    flashResult = FlashResult.Failure("Failed to save custom firmware"),
                )
            }
            return false
        }

        return flasher.flashFirmware(
            deviceId = deviceId,
            firmwarePackage = firmwarePackage,
            consoleImage = flasher.getConsoleImageStream(),
        )
    }

    // ==================== Source Management ====================

    fun selectFirmwareSource(source: FirmwareSource) {
        state.update {
            it.copy(
                selectedFirmwareSource = source,
                selectedFirmware = null,
                selectedVersion = null,
                customFirmwareUri = null,
                customFirmwareUrl = "",
                availableFirmware = emptyList(),
                availableVersions = emptyList(),
            )
        }
    }

    fun setCustomFirmware(
        uri: android.net.Uri? = null,
        url: String = "",
    ) {
        state.update {
            it.copy(
                customFirmwareUri = uri,
                customFirmwareUrl = if (uri != null) "" else url,
                selectedFirmware = null,
            )
        }
    }

    // ==================== Flash Completion ====================

    fun handleFlashComplete(
        deviceInfo: network.columba.app.reticulum.flasher.RNodeDeviceInfo?,
        tncConfigOnlyMode: Boolean,
    ): Boolean {
        val currentState = state.value

        if (currentState.currentStep == FlasherStep.TNC_CONFIGURATION) {
            if (tncConfigOnlyMode) {
                state.update {
                    it.copy(
                        tncConfiguring = false,
                        tncConfigComplete = true,
                    )
                }
            } else {
                state.update {
                    it.copy(
                        currentStep = FlasherStep.COMPLETE,
                        tncConfiguring = false,
                        flashResult = FlashResult.Success(deviceInfo),
                    )
                }
            }
            return true
        }

        if (currentState.selectedFirmwareSource is FirmwareSource.MicroReticulum) {
            val defaultRegion =
                when (currentState.selectedBand) {
                    FrequencyBand.BAND_433 ->
                        network.columba.app.data.model.FrequencyRegions.regions
                            .find { it.id == "eu_433" }
                    else ->
                        network.columba.app.data.model.FrequencyRegions.regions
                            .find { it.id == "us_915" }
                }
            val defaultPreset = network.columba.app.data.model.ModemPreset.DEFAULT
            state.update {
                it.copy(
                    currentStep = FlasherStep.TNC_CONFIGURATION,
                    isFlashing = false,
                    needsManualReset = false,
                    isProvisioning = false,
                    tncSelectedRegion = defaultRegion,
                    tncSelectedPreset = defaultPreset,
                    tncFrequencyMhz =
                        defaultRegion?.let { r ->
                            String.format(java.util.Locale.US, "%.3f", r.frequency / 1_000_000.0)
                        } ?: "868.0",
                    tncTxPower = (defaultRegion?.defaultTxPower ?: 17).toString(),
                    tncBandwidthKhz = (defaultPreset.bandwidth / 1000).toString(),
                    tncSpreadingFactor = defaultPreset.spreadingFactor.toString(),
                    tncCodingRate = defaultPreset.codingRate.toString(),
                )
            }
            return true
        }

        return false
    }

    // ==================== TNC Apply ====================

    suspend fun applyTncConfiguration(
        deviceId: Int,
        band: FrequencyBand,
    ) {
        val currentState = state.value
        val frequencyHz = ((currentState.tncFrequencyMhz.toDoubleOrNull() ?: 868.0) * 1_000_000).toInt()
        val bandwidthHz = ((currentState.tncBandwidthKhz.toDoubleOrNull() ?: 125.0) * 1_000).toInt()
        val sf = currentState.tncSpreadingFactor.toIntOrNull() ?: 8
        val cr = currentState.tncCodingRate.toIntOrNull() ?: 5
        val txp = currentState.tncTxPower.toIntOrNull() ?: 17

        Log.i(TAG, "Applying TNC config: freq=${frequencyHz}Hz bw=${bandwidthHz}Hz sf=$sf cr=$cr txp=$txp")
        state.update { it.copy(tncConfiguring = true, tncConfigError = null) }
        flasher.tncModeController.enableTncMode(
            deviceId = deviceId,
            band = band,
            frequency = frequencyHz,
            bandwidth = bandwidthHz,
            spreadingFactor = sf,
            codingRate = cr,
            txPower = txp,
        )
    }

    // ==================== Source Checking ====================

    suspend fun checkAvailableSources(board: RNodeBoard?) {
        if (board == null) {
            state.update {
                it.copy(
                    availableFirmwareSources =
                        listOf(
                            FirmwareSource.Official,
                            FirmwareSource.MicroReticulum,
                            FirmwareSource.CommunityEdition,
                            FirmwareSource.Custom,
                        ),
                )
            }
            return
        }

        val githubSources =
            listOf(
                FirmwareSource.Official,
                FirmwareSource.MicroReticulum,
                FirmwareSource.CommunityEdition,
            )

        val available = mutableListOf<FirmwareSource>()

        for (source in githubSources) {
            try {
                val hasCached =
                    flasher.firmwareRepository
                        .getFirmwareForBoard(source, board)
                        .isNotEmpty()
                if (hasCached) {
                    available.add(source)
                    continue
                }

                val release = flasher.firmwareDownloader.getLatestRelease(source)
                if (release != null && flasher.firmwareDownloader.hasFirmwareForBoard(release, board)) {
                    available.add(source)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check source ${source.id} for ${board.displayName}", e)
                available.add(source)
            }
        }
        available.add(FirmwareSource.Custom)

        state.update { currentState ->
            val selectedSource =
                if (currentState.selectedFirmwareSource in available) {
                    currentState.selectedFirmwareSource
                } else {
                    FirmwareSource.Official
                }
            currentState.copy(
                availableFirmwareSources = available,
                selectedFirmwareSource = selectedSource,
            )
        }
    }
}
