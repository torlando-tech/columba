package network.columba.app.util

import android.Manifest
import android.os.Build

/**
 * The runtime permissions Columba needs to operate the AndroidBLE interface.
 * Android 12+ uses the new BLUETOOTH_* runtime permissions; older versions
 * fall back to the legacy BLUETOOTH + ACCESS_FINE_LOCATION pair.
 */
fun getBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
