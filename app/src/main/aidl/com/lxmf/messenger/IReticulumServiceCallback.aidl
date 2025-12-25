// IReticulumServiceCallback.aidl
package com.lxmf.messenger;

/**
 * Callback interface for receiving events from ReticulumService.
 * Clients implement this to receive announces, packets, and other events.
 */
interface IReticulumServiceCallback {
    /**
     * Called when an announce is received.
     * @param announceJson JSON string with announce event data
     */
    void onAnnounce(String announceJson);

    /**
     * Called when a packet is received.
     * @param packetJson JSON string with packet data
     */
    void onPacket(String packetJson);

    /**
     * Called when an LXMF message is received.
     * @param messageJson JSON string with message data
     */
    void onMessage(String messageJson);

    /**
     * Called when a link event occurs.
     * @param linkEventJson JSON string with link event data
     */
    void onLinkEvent(String linkEventJson);

    /**
     * Called when network status changes.
     * @param status Status string
     */
    void onStatusChanged(String status);

    /**
     * Called when a sent message's delivery status changes.
     * @param statusJson JSON string with delivery status data
     */
    void onDeliveryStatus(String statusJson);

    /**
     * Called when Python needs an alternative relay for message retry.
     * The app should query PropagationNodeManager and call provideAlternativeRelay().
     * @param requestJson JSON string with request data:
     *        {"current_relay": "hex_hash", "exclude_relays": ["hex1", "hex2"]}
     */
    void onAlternativeRelayRequested(String requestJson);

    /**
     * Called when BLE connection state changes (connect/disconnect).
     * @param connectionDetailsJson JSON string with connection details:
     *        [{"address": "XX:XX:XX:XX:XX:XX", "name": "device_name", "rssi": -50}]
     */
    void onBleConnectionChanged(String connectionDetailsJson);

    /**
     * Called when debug info changes (lock state, interface status, etc.).
     * @param debugInfoJson JSON string with debug info data
     */
    void onDebugInfoChanged(String debugInfoJson);

    /**
     * Called when interface online/offline status changes.
     * @param interfaceStatusJson JSON string with interface status map:
     *        {"InterfaceName1": true, "InterfaceName2": false}
     */
    void onInterfaceStatusChanged(String interfaceStatusJson);

    /**
     * Called when location telemetry is received from a contact.
     * @param locationJson JSON string with location telemetry data:
     *        {"sender_hash": "...", "lat": ..., "lng": ..., "acc": ..., "ts": ..., "expires": ...}
     */
    void onLocationTelemetry(String locationJson);

    /**
     * Called when an emoji reaction to a message is received.
     * @param reactionJson JSON string with reaction data:
     *        {"reaction_to": "msg_id", "emoji": "👍", "sender": "sender_hash", "source_hash": "...", "timestamp": ...}
     */
    void onReactionReceived(String reactionJson);
}
