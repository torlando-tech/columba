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
     * Probe link speed to a destination by checking existing links or sending
     * an empty LXMF message to establish one.
     *
     * @param destHash Destination hash bytes
     * @param timeoutSeconds How long to wait for link establishment
     * @param deliveryMethod "direct" or "propagated" - affects which link to check/establish
     * @return JSON string with probe result containing status, rates, RTT, hops
     */
    String probeLinkSpeed(in byte[] destHash, float timeoutSeconds, String deliveryMethod);

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
     * @param fileAttachments Optional map of filename -> file bytes (null if none)
     * @return JSON string with result
     */
    String sendLxmfMessage(in byte[] destHash, String content, in byte[] sourceIdentityPrivateKey, in byte[] imageData, String imageFormat, in Map fileAttachments);

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
     * Uses bulk restore for performance optimization.
     * @param peerIdentitiesJson JSON array containing objects with 'identity_hash' and 'public_key' fields
     * @return JSON string with result: {"success": true/false, "restored_count": N, "error": "..."}
     */
    String restorePeerIdentities(String peerIdentitiesJson);

    /**
     * Restore announce identities to enable message sending to announced peers.
     * Uses bulk restore with direct dict population for maximum performance.
     * For announces, we have destination_hash directly - no hash computation needed.
     * @param announcesJson JSON array containing objects with 'destination_hash' and 'public_key' fields
     * @return JSON string with result: {"success": true/false, "restored_count": N, "error": "..."}
     */
    String restoreAnnounceIdentities(String announcesJson);

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

    // ==================== PROPAGATION NODE SUPPORT ====================

    /**
     * Set the propagation node to use for PROPAGATED delivery.
     * @param destHash 16-byte destination hash of the propagation node, or null to clear
     * @return JSON string with result: {"success": true/false, "error": "..."}
     */
    String setOutboundPropagationNode(in byte[] destHash);

    /**
     * Get the currently configured propagation node.
     * @return JSON string with result: {"success": true, "propagation_node": "hex_hash" or null}
     */
    String getOutboundPropagationNode();

    /**
     * Request/sync messages from the configured propagation node.
     * This is the key method for RECEIVING messages sent via propagation.
     * Call periodically (e.g., every 30-60 seconds) to retrieve waiting messages.
     *
     * @param identityPrivateKey Optional identity private key bytes (uses default if null)
     * @param maxMessages Maximum number of messages to retrieve
     * @return JSON string with result: {"success": true, "state": 0, "state_name": "idle"}
     */
    String requestMessagesFromPropagationNode(in byte[] identityPrivateKey, int maxMessages);

    /**
     * Get the current propagation sync state and progress.
     * @return JSON string with result: {"success": true, "state": 0, "state_name": "idle", "progress": 0.0, "messages_received": 0}
     */
    String getPropagationState();

    /**
     * Send an LXMF message with explicit delivery method.
     * @param destHash Destination hash bytes
     * @param content Message content string
     * @param sourceIdentityPrivateKey Source identity private key bytes
     * @param deliveryMethod Delivery method: "opportunistic", "direct", or "propagated"
     * @param tryPropagationOnFail If true and direct fails, retry via propagation
     * @param imageData Optional image data bytes for small images (null if none or using imageDataPath)
     * @param imageFormat Optional image format string (e.g., "jpg", "png", null if none)
     * @param imageDataPath Optional file path for large images to bypass Binder IPC limits (null if using imageData)
     * @param fileAttachments Optional map of filename -> file bytes for small files (null if none)
     * @param fileAttachmentPaths Optional map of filename -> file path for large files (null if none)
     *                            Large files are written to temp files to bypass Binder IPC size limits.
     *                            Python will read from disk and delete the temp files after sending.
     * @param replyToMessageId Optional message ID being replied to (stored in LXMF field 16)
     * @param iconName Optional icon name for FIELD_ICON_APPEARANCE (Sideband/MeshChat interop)
     * @param iconFgColor Optional icon foreground color hex string (3 bytes RGB, e.g., "FFFFFF")
     * @param iconBgColor Optional icon background color hex string (3 bytes RGB, e.g., "1E88E5")
     * @return JSON string with result: {"success": true, "message_hash": "...", "delivery_method": "..."}
     */
    String sendLxmfMessageWithMethod(in byte[] destHash, String content, in byte[] sourceIdentityPrivateKey, String deliveryMethod, boolean tryPropagationOnFail, in byte[] imageData, String imageFormat, String imageDataPath, in Map fileAttachments, in Map fileAttachmentPaths, String replyToMessageId, String iconName, String iconFgColor, String iconBgColor);

    /**
     * Provide an alternative relay for message retry.
     * Called by app process in response to onAlternativeRelayRequested callback.
     * @param relayHash 16-byte destination hash of alternative relay, or null if none available
     */
    void provideAlternativeRelay(in byte[] relayHash);

    // ==================== MESSAGE SIZE LIMITS ====================

    /**
     * Set the incoming message size limit.
     * This controls the maximum size of LXMF messages that can be received.
     * Messages exceeding this limit will be rejected by the LXMF router.
     *
     * @param limitKb Size limit in KB (e.g., 1024 for 1MB, 131072 for 128MB "unlimited")
     */
    void setIncomingMessageSizeLimit(int limitKb);

    // ==================== LOCATION TELEMETRY ====================

    /**
     * Send location telemetry to a destination via LXMF field 7.
     * @param destHash Destination hash bytes (16 bytes)
     * @param locationJson JSON string with location data: {"type": "location_share", "lat": ..., "lng": ..., "acc": ..., "ts": ..., "expires": ...}
     * @param sourceIdentityPrivateKey Source identity private key bytes
     * @return JSON string with result: {"success": true, "message_hash": "...", "timestamp": ...}
     */
    String sendLocationTelemetry(in byte[] destHash, String locationJson, in byte[] sourceIdentityPrivateKey);

    // ==================== EMOJI REACTIONS ====================

    /**
     * Send an emoji reaction to a message via LXMF Field 16.
     * The reaction is sent as a new LXMF message with Field 16 containing
     * the reaction data: {"reaction": {"target_id": "...", "emoji": "..."}}.
     *
     * @param destHash Destination hash bytes (16 bytes) - the recipient
     * @param targetMessageId The message hash/ID being reacted to
     * @param emoji The emoji reaction (e.g., "👍", "❤️", "😂", "😮", "😢", "😡")
     * @param sourceIdentityPrivateKey Source identity private key bytes
     * @return JSON string with result: {"success": true, "message_hash": "...", "timestamp": ...}
     */
    String sendReaction(in byte[] destHash, String targetMessageId, String emoji, in byte[] sourceIdentityPrivateKey);

    // ==================== CONVERSATION LINK MANAGEMENT ====================

    /**
     * Establish a link to a destination for real-time connectivity.
     * Used to show "Online" status and enable instant link speed probing.
     *
     * @param destHash Destination hash bytes (16 bytes)
     * @param timeoutSeconds How long to wait for link establishment
     * @return JSON string with result: {"success": true, "link_active": true, "establishment_rate_bps": ...}
     */
    String establishLink(in byte[] destHash, float timeoutSeconds);

    /**
     * Close an active link to a destination.
     * Called when conversation has been inactive for too long.
     *
     * @param destHash Destination hash bytes (16 bytes)
     * @return JSON string with result: {"success": true, "was_active": true}
     */
    String closeLink(in byte[] destHash);

    /**
     * Check if a link is active to a destination.
     *
     * @param destHash Destination hash bytes (16 bytes)
     * @return JSON string with result: {"active": true, "establishment_rate_bps": ...}
     */
    String getLinkStatus(in byte[] destHash);

    // ==================== RMSP MAP SERVICE ====================

    /**
     * Get all discovered RMSP map servers.
     * @return JSON string containing array of server info objects
     */
    String getRmspServers();

    /**
     * Fetch map tiles from an RMSP server.
     *
     * @param destinationHashHex Server destination hash as hex string
     * @param publicKey Server's RNS identity public key (for establishing link)
     * @param geohash Geohash area to fetch
     * @param zoomMin Minimum zoom level
     * @param zoomMax Maximum zoom level
     * @param timeoutMs Request timeout in milliseconds
     * @return Raw tile data bytes in RMSP format, or null on failure
     */
    byte[] fetchRmspTiles(String destinationHashHex, in byte[] publicKey, String geohash, int zoomMin, int zoomMax, long timeoutMs);

    // ==================== GUARDIAN/PARENTAL CONTROL ====================

    /**
     * Generate a QR code data string for guardian pairing.
     * Creates a signed payload with the current identity's destination hash and public key.
     *
     * @return JSON string: {"success": true, "qr_data": "lxmf-guardian://..."} or {"success": false, "error": "..."}
     */
    String guardianGeneratePairingQr();

    /**
     * Parse and validate a guardian pairing QR code.
     * Validates the signature and timestamp.
     *
     * @param qrData The scanned QR code data
     * @return JSON string: {"valid": true, "guardian_dest_hash": "...", "guardian_public_key": "base64..."}
     *         or {"valid": false, "error": "..."}
     */
    String guardianParsePairingQr(String qrData);

    /**
     * Verify a guardian command signature.
     *
     * @param commandJson The command JSON string
     * @param signatureBase64 The signature bytes as base64
     * @param publicKeyBase64 The guardian's public key as base64
     * @return JSON string: {"valid": true/false}
     */
    String guardianVerifyCommand(String commandJson, String signatureBase64, String publicKeyBase64);

    /**
     * Sign a guardian command for sending to a child device.
     *
     * @param commandJson The command JSON string to sign
     * @return JSON string: {"success": true, "signature": "base64..."} or {"success": false, "error": "..."}
     */
    String guardianSignCommand(String commandJson);

    /**
     * Send a guardian command to a child device via LXMF.
     * Creates, signs, and sends the command.
     *
     * @param destinationHash The child's destination hash
     * @param command The command type (LOCK, UNLOCK, ALLOW_ADD, ALLOW_REMOVE, ALLOW_SET, PAIR_ACK)
     * @param payloadJson JSON string of additional payload
     * @return JSON: {"success": true} or {"success": false, "error": "message"}
     */
    String guardianSendCommand(String destinationHash, String command, String payloadJson);

    /**
     * Update guardian/parental control configuration in Python.
     * When locked, incoming links from non-allowed peers will be rejected.
     *
     * @param isLocked Whether the device is locked (filtering enabled)
     * @param guardianHash Destination hash of the guardian (always allowed), or null
     * @param allowedHashes List of allowed contact destination hashes (hex strings)
     * @return JSON: {"success": true} or {"success": false, "error": "message"}
     */
    String updateGuardianConfig(boolean isLocked, String guardianHash, in List<String> allowedHashes);
}
