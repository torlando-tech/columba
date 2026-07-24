package network.columba.app.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import network.columba.app.MainActivity
import network.columba.app.rns.host.flasher.PyxisFirmwarePackage
import network.columba.app.rns.host.flasher.RNodeFlasher
import network.columba.app.rns.host.usb.UsbDeviceInfo
import javax.inject.Inject

data class PyxisUpdaterUiState(
    val connectedDevices: List<UsbDeviceInfo> = emptyList(),
    val selectedDevice: UsbDeviceInfo? = null,
    val isRefreshingDevices: Boolean = false,
    val permissionPending: Boolean = false,
    val permissionError: String? = null,
    val packageUri: Uri? = null,
    val packageName: String? = null,
    val packageVersion: String? = null,
    val firmwareSize: Int? = null,
    val packageError: String? = null,
    val isLoadingPackage: Boolean = false,
    val isFlashing: Boolean = false,
    val flashProgress: Int = 0,
    val flashMessage: String = "",
    val flashSucceeded: Boolean = false,
    val flashError: String? = null,
)

@HiltViewModel
class PyxisUpdaterViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : ViewModel() {
        private val flasher = RNodeFlasher(context)
        private val _state = MutableStateFlow(PyxisUpdaterUiState())
        val state: StateFlow<PyxisUpdaterUiState> = _state.asStateFlow()

        private var validatedPackage: PyxisFirmwarePackage? = null

        init {
            observeFlashState()
            refreshDevices()
        }

        private fun observeFlashState() {
            viewModelScope.launch {
                flasher.flashState.collect { flashState ->
                    when (flashState) {
                        is RNodeFlasher.FlashState.Progress ->
                            _state.update {
                                it.copy(
                                    isFlashing = true,
                                    flashProgress = flashState.percent,
                                    flashMessage = flashState.message,
                                    flashError = null,
                                )
                            }
                        is RNodeFlasher.FlashState.Complete -> {
                            MainActivity.bootloaderFlashModeActive = false
                            _state.update {
                                it.copy(
                                    isFlashing = false,
                                    flashProgress = 100,
                                    flashMessage = "Pyxis update installed",
                                    flashSucceeded = true,
                                    flashError = null,
                                )
                            }
                        }
                        is RNodeFlasher.FlashState.Error -> {
                            MainActivity.bootloaderFlashModeActive = false
                            _state.update {
                                it.copy(
                                    isFlashing = false,
                                    flashSucceeded = false,
                                    flashError = flashState.message,
                                )
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }

        fun refreshDevices() {
            _state.update { it.copy(isRefreshingDevices = true, permissionError = null) }
            viewModelScope.launch {
                val devices = runCatching { flasher.getConnectedDevices() }.getOrElse { error ->
                    _state.update {
                        it.copy(
                            isRefreshingDevices = false,
                            permissionError = "Failed to scan USB devices: ${error.message}",
                        )
                    }
                    return@launch
                }
                _state.update { current ->
                    current.copy(
                        connectedDevices = devices,
                        selectedDevice = current.selectedDevice?.let { selected ->
                            devices.find { it.deviceId == selected.deviceId }
                        },
                        isRefreshingDevices = false,
                    )
                }
            }
        }

        fun selectDevice(device: UsbDeviceInfo) {
            _state.update { it.copy(selectedDevice = device, permissionError = null) }
            if (flasher.hasPermission(device.deviceId)) return

            _state.update { it.copy(permissionPending = true) }
            flasher.requestPermission(device.deviceId) { granted ->
                _state.update {
                    it.copy(
                        permissionPending = false,
                        permissionError = if (granted) null else "USB permission denied",
                    )
                }
            }
        }

        fun loadPackage(uri: Uri) {
            if (_state.value.isFlashing) return
            validatedPackage = null
            _state.update {
                it.copy(
                    packageUri = uri,
                    packageName = queryDisplayName(uri),
                    packageVersion = null,
                    firmwareSize = null,
                    packageError = null,
                    isLoadingPackage = true,
                    flashSucceeded = false,
                    flashError = null,
                )
            }

            viewModelScope.launch {
                val result =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            context.contentResolver.openInputStream(uri)?.use(PyxisFirmwarePackage::parse)
                                ?: throw IllegalArgumentException("Unable to open the selected package")
                        }
                    }
                result.onSuccess { firmwarePackage ->
                    validatedPackage = firmwarePackage
                    _state.update {
                        it.copy(
                            isLoadingPackage = false,
                            packageVersion = firmwarePackage.version,
                            firmwareSize = firmwarePackage.manifest.firmware.size,
                            packageError = null,
                        )
                    }
                }.onFailure { error ->
                    _state.update {
                        it.copy(
                            isLoadingPackage = false,
                            packageError = error.message ?: "Invalid Pyxis update package",
                        )
                    }
                }
            }
        }

        fun canFlash(): Boolean {
            val current = _state.value
            val device = current.selectedDevice ?: return false
            return validatedPackage != null &&
                !current.isLoadingPackage &&
                !current.isFlashing &&
                !current.permissionPending &&
                current.permissionError == null &&
                flasher.hasPermission(device.deviceId)
        }

        fun startFlash() {
            val device = _state.value.selectedDevice ?: return
            val firmwarePackage = validatedPackage ?: return
            if (!canFlash()) return

            MainActivity.bootloaderFlashModeActive = true
            _state.update {
                it.copy(
                    isFlashing = true,
                    flashProgress = 0,
                    flashMessage = "Starting Pyxis update...",
                    flashSucceeded = false,
                    flashError = null,
                )
            }
            viewModelScope.launch {
                runCatching { flasher.flashPyxisFirmware(device.deviceId, firmwarePackage) }
                    .onFailure { error ->
                        MainActivity.bootloaderFlashModeActive = false
                        _state.update {
                            it.copy(
                                isFlashing = false,
                                flashSucceeded = false,
                                flashError = error.message ?: "Pyxis update failed",
                            )
                        }
                    }
            }
        }

        fun clearFlashError() {
            _state.update { it.copy(flashError = null) }
        }

        private fun queryDisplayName(uri: Uri): String? =
            runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull()

        override fun onCleared() {
            MainActivity.bootloaderFlashModeActive = false
            super.onCleared()
        }
    }
