package network.columba.app.rns.host

import android.content.Context
import android.util.Log
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.backend.kt.NativeRnsBackend
import network.columba.app.rns.backend.kt.RNodeHostBridge
import network.columba.app.rns.host.call.rnode.BluetoothLeConnection
import network.columba.app.rns.host.call.rnode.ColumbaLogo
import network.columba.app.rns.host.usb.KotlinUSBBridge
import java.io.InputStream
import java.io.OutputStream
import javax.inject.Singleton

/**
 * Kotlin-flavor backend wiring for `:rns-host`.
 *
 * Active when the `rnsImpl=kotlinBackend` flavor resolves. Provides
 * [NativeRnsBackend] (and its [RnsBackend] view) into the `:reticulum`-process
 * Hilt graph plus the [RNodeHostBridge] adapter that wraps `KotlinUSBBridge`
 * + `BluetoothLeConnection` so `:rns-backend-kt` can open serial streams to
 * RNode hardware without depending on `:rns-host` itself (which would cycle
 * Gradle's project dep graph — see the [RNodeHostBridge] kdoc).
 *
 * Plan deviation #8: the handoff sketched a single `LxstCallBridge`. This
 * impl rolled the LXST call surface into `:rns-backend-kt` directly (the
 * `NativeNetworkTransport` + `NativeCallManager` files moved with the
 * protocol body), so only the RNode USB/BLE bridge surface needs a host-side
 * adapter.
 */
@Module
@InstallIn(SingletonComponent::class)
object HostBackendModule {
    private const val TAG = "HostBackendModule"

    @Provides
    @Singleton
    fun provideRNodeHostBridge(@ApplicationContext context: Context): RNodeHostBridge =
        AndroidRNodeHostBridge(context)

    @Provides
    @Singleton
    fun provideNativeRnsBackend(
        @ApplicationContext context: Context,
        bridge: RNodeHostBridge,
    ): NativeRnsBackend = NativeRnsBackend(appContext = context, rnodeHostBridge = bridge)

    @Provides
    @Singleton
    fun provideRnsBackend(native: NativeRnsBackend): RnsBackend = native
}

/**
 * Concrete [RNodeHostBridge] implementation wrapping the `:rns-host`-side
 * `KotlinUSBBridge` (mik3y USB serial) and `BluetoothLeConnection` (RNode-over-BLE)
 * classes. Constructed by the kotlinBackend Hilt module above.
 */
internal class AndroidRNodeHostBridge(
    private val context: Context,
) : RNodeHostBridge {
    private companion object {
        const val TAG = "AndroidRNodeHostBridge"
    }

    override suspend fun openUsbSerial(
        ctx: Context,
        vendorId: Int?,
        productId: Int?,
        deviceId: Int?,
    ): Pair<InputStream, OutputStream> {
        val bridge = KotlinUSBBridge.getInstance(ctx)

        val currentDeviceId =
            if (vendorId != null && productId != null) {
                val found = bridge.findDeviceByVidPid(vendorId, productId)
                if (found >= 0) found else deviceId
            } else {
                deviceId
            }

        if (currentDeviceId != null && !bridge.hasPermission(currentDeviceId)) {
            Log.i(TAG, "Requesting USB permission for device $currentDeviceId")
            val granted = bridge.requestPermissionSuspend(currentDeviceId)
            if (!granted) {
                throw SecurityException("USB permission denied for device $currentDeviceId")
            }
        }

        return bridge.openSerialStreams(
            vendorId = vendorId,
            productId = productId,
            deviceId = deviceId,
        )
    }

    override fun openBleSerial(
        ctx: Context,
        address: String,
    ): Pair<InputStream, OutputStream> {
        val bleConn = BluetoothLeConnection(ctx, address)
        return bleConn.connect()
    }

    override fun rnodeFramebufferData(): ByteArray = ColumbaLogo.FB_DATA
}
