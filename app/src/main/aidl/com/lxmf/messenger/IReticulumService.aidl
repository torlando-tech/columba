// IReticulumService.aidl
package com.lxmf.messenger;

import com.lxmf.messenger.IReticulumServiceCallback;
import com.lxmf.messenger.IInitializationCallback;
import com.lxmf.messenger.IReadinessCallback;

/**
 * AIDL interface for IPC communication with ReticulumService.
 * This allows the UI to communicate with the background service that runs RNS.
 */
interface IReticulumService {
    /**
     * Initialize Reticulum with the given configuration JSON (ASYNC).
     *
     * Phase 1, Task 1.2: Async initialization with callback
     *
     * This method returns immediately (< 1ms) without blocking the binder thread.
     * The service launches a coroutine to perform initialization in the background.
     * When initialization completes (success or failure), the callback is invoked.
     *
     * @param configJson JSON string containing ReticulumConfig
     * @param callback Callback to invoke when initialization completes or fails
     */
    void initialize(String configJson, IInitializationCallback callback);

    /**
     * Shutdown Reticulum and release resources.
     */
    void shutdown();

    /**
     * Get current network status.
     * @return Status string: "SHUTDOWN", "INITIALIZING", "READY", or "ERROR:message"
     */
    String getStatus();

    /**
     * Check if the service is initialized and ready to use.
     * @return true if wrapper is initialized and ready, false otherwise
     */
    boolean isInitialized();

    /**
     * Create a new identity.
     * @return JSON string with identity data
     */
    String createIdentity();

    /**
     * Load identity from file.
     * @param path File path to load from
     * @return JSON string with identity data
     */
    String loadIdentity(String path);

    /**
     * Save identity to file.
     * @param privateKey Private key bytes
     * @param path File path to save to
     * @return JSON string with result
     */
    String saveIdentity(in byte[] privateKey, String path);

    /**
     * Create a new identity with display name.
     * @param displayName Display name for the identity
     * @return JSON string with identity data
     */
    String createIdentityWithName(String displayName);

    /**
     * Delete an identity file.
     * @param identityHash Identity hash to delete
     * @return JSON string with result
     */
    String deleteIdentityFile(String identityHash);

    /**
     * Import an identity from file data.
     * @param fileData Identity file data bytes
     * @param displayName Display name for the identity
     * @return JSON string with identity data
     */
    String importIdentityFile(in byte[] fileData, String displayName);

    /**
     * Export an identity file.
     * @param identityHash Identity hash to export
     * @param filePath Direct path to identity file
     * @return Identity file data bytes
     */
    byte[] exportIdentityFile(String identityHash, String filePath);

    /**
     * Recover an identity file from backup key data.
     * @param identityHash Expected identity hash
     * @param keyData Raw 64-byte identity key data from database backup
     * @param filePath Path where identity file should be restored
     * @return JSON string with result (success, file_path, or error)
     */
    String recoverIdentityFile(String identityHash, in byte[] keyData, String filePath);

    /**
     * Create a destination.
     * @param identityJson JSON string with identity data
     * @param direction Direction string ("IN" or "OUT")
     * @param destType Destination type string ("SINGLE", "GROUP", "PLAIN")
     * @param appName Application name
     * @param aspectsJson JSON array of aspect strings
     * @return JSON string with destination data
     */
    String createDestination(String identityJson, String direction, String destType, String appName, String aspectsJson);

    /**
     * Announce a destination on the network.
     * @param destHash Destination hash bytes
     * @param appData Optional application data bytes (null if none)
     * @return JSON string with result
     */
    String announceDestination(in byte[] destHash, in byte[] appData);

    /**
     * Send a packet to a destination.
     * @param destHash Destination hash bytes
     * @param data Packet data bytes
     * @param packetType Packet type string
     * @return JSON string with packet receipt
     */
    String sendPacket(in byte[] destHash, in byte[] data, String packetType);

    /**
     * Check if a path to destination exists.
     * @param destHash Destination hash bytes
     * @return true if path exists
     */
    boolean hasPath(in byte[] destHash);

    /**
     * Request a path to destination.
     * @param destHash Destination hash bytes
     * @return JSON string with result
     */
    String requestPath(in byte[] destHash);

    /**
     * Get hop count to destination.
     * @param destHash Destination hash bytes
     * @return Hop count, or -1 if unknown
     */
    int getHopCount(in byte[] destHash);

    /**
     * Get list of destination hashes from RNS path table.
     * @return JSON string containing array of hex-encoded destination hashes
     */
    String getPathTableHashes();

    /**
     * Get debug information.
     * @return JSON string with debug info
     */
    String getDebugInfo();

    /**
     * Send an LXMF message to a destination.
     * @param destHash Destination hash bytes
     * @param content Message content string
     * @param sourceIdentityPrivateKey Source identity private key bytes
     * @param imageData Optional image data bytes (null if none)
     * @param imageFormat Optional image format string (e.g., "jpg", "png", null if none)
     * @return JSON string with result
     */
    String sendLxmfMessage(in byte[] destHash, String content, in byte[] sourceIdentityPrivateKey, in byte[] imageData, String imageFormat);

    /**
     * Get the LXMF router's identity.
     * This should be used for announces and messaging to ensure consistency.
     * @return JSON string with identity data
     */
    String getLxmfIdentity();

    /**
     * Get the local LXMF destination hash.
     * @return JSON string with destination data
     */
    String getLxmfDestination();

    /**
     * Register a callback for receiving announces and events.
     * @param callback The callback interface
     */
    void registerCallback(IReticulumServiceCallback callback);

    /**
     * Unregister a previously registered callback.
     * @param callback The callback interface
     */
    void unregisterCallback(IReticulumServiceCallback callback);

    /**
     * Restore peer identities to enable message sending to previously known peers.
     * @param peerIdentitiesJson JSON array containing objects with 'hash' and 'public_key' fields
     * @return JSON string with result: {"success": true/false, "restored_count": N, "error": "..."}
     */
    String restorePeerIdentities(String peerIdentitiesJson);

    /**
     * Force service process to exit (for clean restart).
     * This shutdowns RNS and exits the process immediately.
     */
    void forceExit();

    /**
     * Register a callback to be notified when the service is ready.
     * This eliminates the need for arbitrary delays after service binding.
     *
     * Phase 2, Task 2.3: Explicit Service Readiness
     *
     * @param callback The callback to invoke when service is ready
     */
    void registerReadinessCallback(IReadinessCallback callback);

    /**
     * Set conversation active state for context-aware message polling.
     * When a conversation is active, message polling uses a faster 1-second interval
     * for lower latency. When inactive, standard adaptive polling (2-30s) is used.
     *
     * @param active true if a conversation screen is currently open and active
     */
    void setConversationActive(boolean active);

    /**
     * Get BLE connection details for all currently connected peers.
     * @return JSON string containing array of connection details
     */
    String getBleConnectionDetails();

    /**
     * Recall an identity from local cache by destination hash.
     *
     * This checks Reticulum's known_destinations cache for a previously
     * seen identity matching the destination hash.
     *
     * @param destHash Destination hash bytes (16 bytes)
     * @return JSON string: {"found": true, "public_key": "hex..."} or {"found": false}
     */
    String recallIdentity(in byte[] destHash);

    /**
     * Get the current RSSI of the active RNode BLE connection.
     * Triggers an RSSI read and returns the current value.
     *
     * @return RSSI in dBm, or -100 if not connected or not available
     */
    int getRNodeRssi();

    /**
     * Reconnect to the RNode interface.
     * Called when CompanionDeviceManager detects the RNode has reappeared
     * after going out of BLE range.
     */
    void reconnectRNodeInterface();

    /**
     * Check if a shared Reticulum instance is available.
     * This queries the Python layer's port probe to detect if another app
     * (e.g., Sideband) is running a shared RNS instance on localhost:37428.
     *
     * @return true if a shared instance is available and responding, false otherwise
     */
    boolean isSharedInstanceAvailable();

    /**
     * Get list of interfaces that failed to initialize.
     * This is used to surface warnings to the user when an interface (like AutoInterface)
     * couldn't start due to port conflicts with another Reticulum app.
     *
     * @return JSON string containing array of failed interface details:
     *         [{"name": "AutoInterface", "error": "Port 29716 already in use", "recoverable": true}]
     */
    String getFailedInterfaces();
}
