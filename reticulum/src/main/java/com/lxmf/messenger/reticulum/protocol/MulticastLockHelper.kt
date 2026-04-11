package com.lxmf.messenger.reticulum.protocol

import android.content.Context
import android.util.Log

internal object MulticastLockHelper {
    private const val TAG = "NativeInterfaceFactory"
    private var multicastLock: android.net.wifi.WifiManager.MulticastLock? = null

    fun acquire(appContext: Context?) {
        if (multicastLock?.isHeld == true) return
        val ctx = appContext ?: return
        val wifiManager = ctx.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
        multicastLock =
            wifiManager.createMulticastLock("ReticulumAutoInterface").apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(TAG, "MulticastLock acquired (AutoInterface started)")
    }

    fun release() {
        if (multicastLock?.isHeld == true) {
            multicastLock?.release()
            Log.i(TAG, "MulticastLock released (no AutoInterface running)")
        }
        multicastLock = null
    }
}
