package com.lxmf.messenger.data.repository

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.lxmf.messenger.data.model.BleConnectionInfo
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.data.model.ConnectionType
import com.lxmf.messenger.reticulum.ble.bridge.KotlinBLEBridge
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for BLE connection status and peer information.
 * Bridges data from the Kotlin BLE layer (via IPC) to the app's UI layer.
 */
@Singleton
class BleStatusRepository
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val reticulumProtocol: ReticulumProtocol,
    ) {
        companion object {
            private const val TAG = "BleStatusRepository"
            private const val POLL_INTERVAL_MS = 3000L // 3 seconds (kept as fallback)
        }

        private val bleBridge: KotlinBLEBridge by lazy {
            KotlinBLEBridge.getInstance(context)
        }

        /**
         * Get a flow of BLE connection state that combines adapter state with connection data.
         * Emits immediately when Bluetooth adapter state changes (< 100ms latency).
         * Falls back to polling connection data every 3 seconds for connection updates.
         *
         * @return Flow emitting BleConnectionsState
         */
        fun getConnectedPeersFlow(): Flow<BleConnectionsState> =
            combine(
                bleBridge.adapterState,
                flow {
                    // Poll connection data
                    while (true) {
                        emit(Unit)
                        delay(POLL_INTERVAL_MS)
                    }
                },
            ) { adapterState, _ ->
                Log.d(TAG, "Adapter state: ${stateToString(adapterState)}")

                when (adapterState) {
                    BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_TURNING_OFF -> {
                        Log.d(TAG, "Bluetooth disabled - returning BluetoothDisabled state")
                        BleConnectionsState.BluetoothDisabled
                    }
                    BluetoothAdapter.STATE_ON -> {
                        try {
                            val connections = getConnectedPeers()
                            Log.d(TAG, "Fetched ${connections.size} BLE connections")
                            BleConnectionsState.Success(connections)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error fetching connected peers", e)
                            BleConnectionsState.Error(e.message ?: "Unknown error")
                        }
                    }
                    BluetoothAdapter.STATE_TURNING_ON -> {
                        Log.d(TAG, "Bluetooth turning on - returning Loading state")
                        BleConnectionsState.Loading
                    }
                    else -> {
                        Log.w(TAG, "Unknown adapter state: $adapterState")
                        BleConnectionsState.Loading
                    }
                }
            }

        private fun stateToString(state: Int): String =
            when (state) {
                BluetoothAdapter.STATE_OFF -> "OFF"
                BluetoothAdapter.STATE_TURNING_ON -> "TURNING_ON"
                BluetoothAdapter.STATE_ON -> "ON"
                BluetoothAdapter.STATE_TURNING_OFF -> "TURNING_OFF"
                else -> "UNKNOWN($state)"
            }

        /**
         * Get the current list of connected peers.
         *
         * @return List of BleConnectionInfo for all connected peers
         */
        suspend fun getConnectedPeers(): List<BleConnectionInfo> {
            return try {
                // Get connection details from service via IPC
                if (reticulumProtocol !is ServiceReticulumProtocol) {
                    Log.w(TAG, "ReticulumProtocol is not ServiceReticulumProtocol, cannot get BLE connections")
                    return emptyList()
                }

                Log.d(TAG, "Calling service.getBleConnectionDetails()")
                val jsonString = (reticulumProtocol as ServiceReticulumProtocol).getBleConnectionDetails()
                Log.d(TAG, "Received JSON: $jsonString")

                // Parse JSON array
                val jsonArray = JSONArray(jsonString)
                val connections = mutableListOf<BleConnectionInfo>()

                for (i in 0 until jsonArray.length()) {
                    val jsonObj = jsonArray.getJSONObject(i)
                    val identityHash = jsonObj.getString("identityHash")
                    val peerName = jsonObj.getString("peerName")
                    val currentMac = jsonObj.getString("currentMac")
                    val hasCentralConnection = jsonObj.getBoolean("hasCentralConnection")
                    val hasPeripheralConnection = jsonObj.getBoolean("hasPeripheralConnection")
                    val mtu = jsonObj.getInt("mtu")
                    val connectedAt = jsonObj.getLong("connectedAt")
                    val firstSeen = jsonObj.getLong("firstSeen")
                    val lastSeen = jsonObj.getLong("lastSeen")
                    val rssi = jsonObj.getInt("rssi")

                    val connectionType =
                        when {
                            hasCentralConnection && hasPeripheralConnection -> ConnectionType.BOTH
                            hasCentralConnection -> ConnectionType.CENTRAL
                            hasPeripheralConnection -> ConnectionType.PERIPHERAL
                            else -> ConnectionType.CENTRAL
                        }

                    Log.v(TAG, "  Peer: $peerName (${identityHash.take(8)}), RSSI: $rssi")

                    connections.add(
                        BleConnectionInfo(
                            identityHash = identityHash,
                            peerName = peerName,
                            currentMac = currentMac,
                            connectionType = connectionType,
                            rssi = rssi,
                            mtu = mtu,
                            connectedAt = connectedAt,
                            firstSeen = firstSeen,
                            lastSeen = lastSeen,
                            bytesReceived = 0, // Per-peer stats not yet implemented
                            bytesSent = 0,
                            packetsReceived = 0,
                            packetsSent = 0,
                            successRate = 1.0, // Connection success rate tracking not yet implemented
                        ),
                    )
                }

                Log.d(TAG, "Parsed ${connections.size} BLE connections from JSON")
                connections
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connected peers", e)
                emptyList()
            }
        }

        /**
         * Get the total count of connected peers.
         *
         * @return Number of currently connected peers
         */
        suspend fun getConnectedPeerCount(): Int {
            return try {
                getConnectedPeers().size
            } catch (e: Exception) {
                Log.e(TAG, "Error getting connected peer count", e)
                0
            }
        }

        /**
         * Disconnect from a specific peer.
         *
         * @param address The MAC address of the peer to disconnect
         */
        suspend fun disconnectPeer(address: String) {
            try {
                // Manual disconnect not yet implemented
                Log.w(TAG, "Manual peer disconnect not yet supported")
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting peer $address", e)
            }
        }
    }
