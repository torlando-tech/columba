package com.lxmf.messenger.reticulum.ble.util

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Handles BLE pairing requests automatically without user interaction.
 *
 * **Background:**
 * When connecting to BlueZ-based GATT servers, the Service Changed characteristic (0x2A05)
 * can trigger bonding requests. Android 15+ devices may show pairing prompts even for
 * "Just Works" BLE connections. This handler intercepts these requests and auto-confirms
 * them to enable seamless mesh networking.
 *
 * **Security Note:**
 * Reticulum handles all cryptographic security at the application layer. BLE pairing
 * only provides transport-level encryption which is unnecessary for our use case.
 * Auto-accepting "Just Works" pairing is standard practice for IoT mesh devices.
 *
 * **How it works:**
 * 1. Registers a high-priority BroadcastReceiver for ACTION_PAIRING_REQUEST
 * 2. When a pairing request arrives, calls setPairingConfirmation(true)
 * 3. Aborts the broadcast to prevent the system pairing dialog
 *
 * @property context Application context for registering the receiver
 */
class BlePairingHandler(private val context: Context) {
    companion object {
        private const val TAG = "Columba:BLE:K:Pair"

        // Pairing variant constants (not all are public in BluetoothDevice)
        // See: https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/bluetooth/BluetoothDevice.java
        private const val PAIRING_VARIANT_PIN = 0

        @Suppress("unused") // Documented for reference
        private const val PAIRING_VARIANT_PASSKEY = 1
        private const val PAIRING_VARIANT_PASSKEY_CONFIRMATION = 2
        private const val PAIRING_VARIANT_CONSENT = 3 // "Just Works" pairing
    }

    private val registrationLock = Any()

    @Volatile
    private var isRegistered = false

    // PIN-based auto-pairing support
    @Volatile
    private var autoPairPin: String? = null

    @Volatile
    private var autoPairDeviceAddress: String? = null

    /**
     * Set the PIN to use for automatic pairing.
     * When a pairing request arrives for a device matching the address (or any RNode if address is null),
     * the PIN will be provided automatically.
     *
     * @param pin The 6-digit PIN from RNode
     * @param deviceAddress Optional Bluetooth address to match (null = match any RNode)
     */
    fun setAutoPairPin(
        pin: String?,
        deviceAddress: String? = null,
    ) {
        autoPairPin = pin
        autoPairDeviceAddress = deviceAddress
        if (pin != null) {
            Log.i(TAG, "Auto-pair PIN set: $pin for device: ${deviceAddress ?: "any RNode"}")
        } else {
            Log.i(TAG, "Auto-pair PIN cleared")
        }
    }

    /**
     * Clear the auto-pair PIN.
     */
    fun clearAutoPairPin() {
        autoPairPin = null
        autoPairDeviceAddress = null
        Log.d(TAG, "Auto-pair PIN cleared")
    }

    /**
     * BroadcastReceiver that intercepts pairing requests and auto-confirms them.
     */
    private val pairingReceiver =
        object : BroadcastReceiver() {
            @SuppressLint("MissingPermission")
            override fun onReceive(
                context: Context,
                intent: Intent,
            ) {
                if (intent.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return

                val device: BluetoothDevice? =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }

                if (device == null) {
                    Log.w(TAG, "Pairing request received but device is null")
                    return
                }

                val pairingVariant =
                    intent.getIntExtra(
                        BluetoothDevice.EXTRA_PAIRING_VARIANT,
                        BluetoothDevice.ERROR,
                    )

                val deviceName =
                    try {
                        device.name ?: device.address
                    } catch (e: SecurityException) {
                        Log.d(TAG, "Missing permission to get device name, using address", e)
                        device.address
                    }

                Log.i(TAG, "Pairing request received: device=$deviceName, variant=$pairingVariant")

                // Check if we have BLUETOOTH_CONNECT permission (required on Android 12+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    if (ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.BLUETOOTH_CONNECT,
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        Log.w(TAG, "Missing BLUETOOTH_CONNECT permission, cannot auto-confirm pairing")
                        return
                    }
                }

                // Auto-confirm pairing based on variant type
                when (pairingVariant) {
                    PAIRING_VARIANT_CONSENT -> {
                        // "Just Works" pairing - auto-confirm
                        confirmPairing(device, deviceName)
                    }

                    PAIRING_VARIANT_PIN -> {
                        // PIN required - check if we have an auto-pair PIN
                        val pin = autoPairPin
                        val targetAddress = autoPairDeviceAddress

                        // Check if this is the device we want to auto-pair with
                        val isMatchingDevice =
                            targetAddress == null || // Match any device
                                targetAddress.equals(device.address, ignoreCase = true) || // Exact match
                                deviceName.startsWith("RNode", ignoreCase = true) // RNode device

                        if (pin != null && isMatchingDevice) {
                            Log.i(TAG, "Auto-pairing with PIN for $deviceName")
                            setPinAndConfirm(device, deviceName, pin)
                        } else {
                            Log.i(TAG, "PIN pairing required for $deviceName - showing system dialog")
                            // Don't abort broadcast - let system show the PIN entry dialog
                        }
                    }

                    PAIRING_VARIANT_PASSKEY_CONFIRMATION -> {
                        // Passkey confirmation - let system dialog handle it
                        Log.i(TAG, "Passkey confirmation required for $deviceName - showing system dialog")
                    }

                    else -> {
                        // Unknown variant - let system handle it to be safe
                        Log.d(TAG, "Unknown pairing variant $pairingVariant, letting system handle")
                    }
                }
            }
        }

    /**
     * Confirm the pairing request and abort the broadcast to prevent system dialog.
     */
    @SuppressLint("MissingPermission")
    private fun BroadcastReceiver.confirmPairing(
        device: BluetoothDevice,
        deviceName: String,
    ) {
        try {
            val confirmed = device.setPairingConfirmation(true)
            if (confirmed) {
                Log.i(TAG, "Auto-confirmed pairing for $deviceName")
            } else {
                Log.w(TAG, "setPairingConfirmation returned false for $deviceName")
            }

            // Abort broadcast to prevent system dialog from appearing
            abortBroadcast()
            Log.d(TAG, "Aborted pairing broadcast to suppress system dialog")
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during pairing confirmation", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to auto-confirm pairing for $deviceName", e)
        }
    }

    /**
     * Set the PIN for pairing and abort the broadcast to prevent system dialog.
     */
    @SuppressLint("MissingPermission")
    private fun BroadcastReceiver.setPinAndConfirm(
        device: BluetoothDevice,
        deviceName: String,
        pin: String,
    ) {
        try {
            // Convert PIN string to UTF-8 byte array (standard Bluetooth PIN format)
            val pinBytes = pin.toByteArray(Charsets.UTF_8)

            Log.d(TAG, "Setting PIN for $deviceName: $pin (${pinBytes.size} bytes: ${pinBytes.joinToString()})")

            // Set the PIN
            val pinSet = device.setPin(pinBytes)
            if (pinSet) {
                Log.i(TAG, "PIN set successfully for $deviceName")
            } else {
                Log.w(TAG, "setPin returned false for $deviceName")
            }

            // Abort broadcast to prevent system dialog from appearing
            abortBroadcast()
            Log.d(TAG, "Aborted pairing broadcast after setting PIN")

            // Clear the auto-pair PIN after use
            autoPairPin = null
            autoPairDeviceAddress = null
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException during PIN pairing", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set PIN for $deviceName", e)
        }
    }

    /**
     * Register the pairing handler to start intercepting pairing requests.
     *
     * Call this when BLE operations start.
     */
    fun register() {
        synchronized(registrationLock) {
            if (isRegistered) {
                Log.d(TAG, "Pairing handler already registered")
                return
            }

            try {
                val filter =
                    IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
                        // High priority to receive before system handler
                        priority = IntentFilter.SYSTEM_HIGH_PRIORITY - 1
                    }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        pairingReceiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    @Suppress("UnspecifiedRegisterReceiverFlag")
                    context.registerReceiver(pairingReceiver, filter)
                }

                isRegistered = true
                Log.i(TAG, "Pairing handler registered - will auto-confirm BLE pairing requests")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to register pairing handler", e)
            }
        }
    }

    /**
     * Unregister the pairing handler.
     *
     * Call this when BLE operations stop.
     */
    fun unregister() {
        synchronized(registrationLock) {
            if (!isRegistered) {
                Log.d(TAG, "Pairing handler not registered")
                return
            }

            try {
                context.unregisterReceiver(pairingReceiver)
                isRegistered = false
                Log.i(TAG, "Pairing handler unregistered")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Receiver was not registered", e)
                isRegistered = false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to unregister pairing handler", e)
            }
        }
    }
}
