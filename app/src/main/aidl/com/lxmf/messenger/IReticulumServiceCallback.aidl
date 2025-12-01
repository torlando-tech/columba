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
}
