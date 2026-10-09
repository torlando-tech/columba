package network.columba.app.rns.host.ble.util

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * Dispatchers for Android Bluetooth framework calls.
 *
 * Since Android 13, many framework Bluetooth APIs (`BluetoothLeScanner.startScan`/`stopScan`,
 * `BluetoothGatt.discoverServices`/`requestConnectionPriority`/`disconnect`,
 * `BluetoothLeAdvertiser.startAdvertising`, `BluetoothGattServer.sendResponse`, ...) are
 * synchronous binder calls that block the caller via `SynchronousResultReceiver` until the
 * Bluetooth process replies. When the Bluetooth stack is congested (e.g. heavy RNode GATT
 * writes) these calls can block for seconds, so they must NEVER run on the main thread or the
 * process gets a (Background) ANR.
 *
 * [ble] is a single-threaded view of [Dispatchers.IO]: it keeps the serialized, one-at-a-time
 * semantics the code previously got from [Dispatchers.Main], without blocking the main looper.
 */
object BleDispatchers {
    val ble: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
}
