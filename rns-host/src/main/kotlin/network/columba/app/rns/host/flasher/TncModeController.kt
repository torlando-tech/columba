package network.columba.app.rns.host.flasher

import android.util.Log
import network.columba.app.rns.host.R
import network.columba.app.rns.host.usb.KotlinUSBBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/**
 * Handles TNC (Terminal Node Controller) mode configuration on RNode devices.
 *
 * Manages enabling standalone transport mode (saving radio parameters to EEPROM)
 * and disabling it (clearing saved config and resetting).
 */
class TncModeController(
    private val usbBridge: KotlinUSBBridge,
    private val detector: RNodeDetector,
    private val flashState: MutableStateFlow<RNodeFlasher.FlashState>,
) {
    companion object {
        private const val TAG = "Columba:TncMode"
    }

    /**
     * Enable TNC mode on a connected device (used for microReticulum transport configuration).
     *
     * Connects to the device, sends radio configuration commands, and saves to EEPROM.
     *
     * @return true if configuration succeeded
     */
    suspend fun enableTncMode(
        deviceId: Int,
        band: FrequencyBand,
        frequency: Int? = null,
        bandwidth: Int = 125000,
        spreadingFactor: Int = 8,
        codingRate: Int = 5,
        txPower: Int = 17,
    ): Boolean =
        withContext(Dispatchers.IO) {
            try {
                flashState.value = RNodeFlasher.FlashState.Progress(50, flashText(R.string.flash_connecting_device))

                if (!usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                    flashState.value = RNodeFlasher.FlashState.Error(flashText(R.string.flash_connect_failed))
                    return@withContext false
                }

                flashState.value = RNodeFlasher.FlashState.Progress(60, flashText(R.string.tnc_configuring))

                val success =
                    detector.enableTncMode(
                        band = band,
                        frequency = frequency,
                        bandwidth = bandwidth,
                        spreadingFactor = spreadingFactor,
                        codingRate = codingRate,
                        txPower = txPower,
                    )

                usbBridge.disconnect()

                if (success) {
                    flashState.value = RNodeFlasher.FlashState.Complete(null)
                } else {
                    flashState.value = RNodeFlasher.FlashState.Error(flashText(R.string.tnc_config_failed))
                }
                success
            } catch (e: kotlinx.coroutines.CancellationException) {
                usbBridge.disconnect()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "TNC mode configuration failed", e)
                usbBridge.disconnect()
                flashState.value = RNodeFlasher.FlashState.Error(
                    flashText(R.string.tnc_config_failed_error, e.message ?: "null"),
                )
                false
            }
        }

    /**
     * Disable TNC mode on a connected device, returning it to normal host-controlled mode.
     *
     * Connects to the device, sends CMD_CONF_DELETE to clear saved radio config,
     * then resets the device. After this, the device will show "Missing Config"
     * and expect radio parameters from the host app at runtime.
     *
     * @return true if the command succeeded
     */
    suspend fun disableTncMode(deviceId: Int): Boolean =
        withContext(Dispatchers.IO) {
            try {
                flashState.value = RNodeFlasher.FlashState.Progress(50, flashText(R.string.flash_connecting_device))

                if (!usbBridge.connect(deviceId, RNodeConstants.BAUD_RATE_DEFAULT)) {
                    flashState.value = RNodeFlasher.FlashState.Error(flashText(R.string.flash_connect_failed))
                    return@withContext false
                }

                flashState.value = RNodeFlasher.FlashState.Progress(70, flashText(R.string.tnc_disabling_transport))

                val success = detector.disableTncMode()

                if (success) {
                    // Reset the device so it reboots into normal mode
                    flashState.value = RNodeFlasher.FlashState.Progress(90, flashText(R.string.flash_resetting_device))
                    val resetFrame =
                        KISSCodec.createFrame(
                            RNodeConstants.CMD_RESET,
                            byteArrayOf(RNodeConstants.CMD_RESET_BYTE),
                        )
                    usbBridge.write(resetFrame)
                    delay(2000)
                }

                usbBridge.disconnect()

                if (success) {
                    flashState.value = RNodeFlasher.FlashState.Complete(null)
                } else {
                    flashState.value = RNodeFlasher.FlashState.Error(flashText(R.string.tnc_disable_failed))
                }
                success
            } catch (e: kotlinx.coroutines.CancellationException) {
                usbBridge.disconnect()
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Disable TNC mode failed", e)
                usbBridge.disconnect()
                flashState.value = RNodeFlasher.FlashState.Error(
                    flashText(R.string.tnc_disable_failed_error, e.message ?: "null"),
                )
                false
            }
        }
}
