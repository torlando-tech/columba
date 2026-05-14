package network.columba.app.rns.backend.kt

import android.content.Context
import java.io.InputStream
import java.io.OutputStream

/**
 * Tiny bridge interface for opening serial streams to RNode hardware.
 *
 * Background: `:rns-backend-kt` cannot depend on `:rns-host` (the
 * kotlinBackend flavor of `:rns-host` already depends on `:rns-backend-kt`,
 * so the reverse would cycle Gradle's project dep graph). [RNodeConnectionHelper]
 * still needs to reach `KotlinUSBBridge` (USB serial via mik3y's library) and
 * `BluetoothLeConnection` (RNode-over-BLE driver) which live in `:rns-host`.
 *
 * The Hilt module in `:rns-host/src/kotlinBackend/.../HostBackendModule.kt`
 * provides an adapter implementing this interface that wraps the
 * `:rns-host`-side classes; the adapter is fed into [NativeRnsBackend] at
 * construction.
 *
 * Plan deviation #8 vs the A.8 handoff: the handoff suggested either
 * (a) leaving `RNodeConnectionHelper` in `:reticulum/protocol/` or
 * (b) defining a `UsbWriteBridge` interface. This impl picks (b) but
 * generalizes the bridge to cover the USB + BLE openers AND the optional
 * RNode framebuffer image so the surface in `:rns-host` is a single
 * adapter rather than three.
 */
interface RNodeHostBridge {
    /**
     * Open a USB serial connection to the RNode at the configured vendor/product
     * IDs (or fall back to the configured device ID). Throws SecurityException if
     * the user denies the USB permission prompt.
     *
     * @param ctx Android context used to look up the USB service.
     * @param vendorId Optional USB vendor ID (config.usbVendorId).
     * @param productId Optional USB product ID (config.usbProductId).
     * @param deviceId Optional fallback USB device ID (config.usbDeviceId).
     */
    suspend fun openUsbSerial(
        ctx: Context,
        vendorId: Int?,
        productId: Int?,
        deviceId: Int?,
    ): Pair<InputStream, OutputStream>

    /**
     * Open a BLE GATT connection to the RNode at the configured address/name and
     * expose RX/TX as serial streams.
     *
     * @param ctx Android context used to acquire BluetoothAdapter.
     * @param address BLE device address (or display name) — must be non-blank.
     */
    fun openBleSerial(
        ctx: Context,
        address: String,
    ): Pair<InputStream, OutputStream>

    /**
     * Optional RNode framebuffer image data (typically the Columba logo).
     * Returned as a flat byte array sized for the RNode's small OLED panel.
     * Null disables the framebuffer feature for this connection.
     */
    fun rnodeFramebufferData(): ByteArray?
}
