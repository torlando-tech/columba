package network.columba.app.rns.api

/**
 * Seam exposing live BLE peer-connection details from the host-side BLE bridge
 * (`:rns-host`'s `KotlinBLEBridge`) to whichever backend `RnsTransportAdmin`
 * is active.
 *
 * The bridge lives in `:rns-host`, which depends on the backend modules — not
 * the reverse — so a backend can't reference `KotlinBLEBridge` directly. It
 * holds the bridge only as `Any?` (to forward to Python via Chaquopy) and casts
 * to this interface to surface connection state for the Network Status "BLE
 * Connections" card. The bridge implements this; the active backend's
 * transport-admin observes it (push) and queries it (pull), then republishes
 * over the AIDL `bleConnectionsFlow` / `getBleConnectionDetails()` seam.
 */
interface BleConnectionSource {
    /**
     * Current connected peers as a JSON array string in the bridge's
     * connection-details shape (`[{identityHash,address,hasCentralConnection,
     * hasPeripheralConnection,mtu,rssi,...}]`). `"[]"` when no peers.
     */
    fun currentBleConnectionsJson(): String

    /** Register [listener]; invoked with the JSON on every connection change. */
    fun addBleConnectionsListener(listener: BleConnectionsListener)

    /** Unregister a previously-added [listener]. */
    fun removeBleConnectionsListener(listener: BleConnectionsListener)
}

/** Callback invoked with the connection-details JSON whenever BLE peers change. */
fun interface BleConnectionsListener {
    fun onBleConnectionsChanged(connectionsJson: String)
}
