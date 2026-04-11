package com.lxmf.messenger.service.binder

import android.util.Log
import com.lxmf.messenger.IInitializationCallback
import com.lxmf.messenger.IReadinessCallback
import com.lxmf.messenger.IReticulumService
import com.lxmf.messenger.IReticulumServiceCallback
import com.lxmf.messenger.service.manager.CallbackBroadcaster
import com.lxmf.messenger.service.state.ServiceState

/**
 * AIDL binder stub for ReticulumService.
 *
 * This is a legacy stub — the native Kotlin Reticulum stack (NativeReticulumProtocol)
 * runs in-process and does not use AIDL IPC. These methods are never called in
 * the native build but must exist to satisfy the AIDL interface contract.
 *
 * The service process is retained for foreground notification, wake locks,
 * BLE coordination, and network monitoring.
 */
@Suppress("TooManyFunctions")
class ReticulumServiceBinder(
    private val state: ServiceState,
    private val broadcaster: CallbackBroadcaster,
    private val onShutdown: () -> Unit,
    private val onForceExit: () -> Unit,
) : IReticulumService.Stub() {
    companion object {
        private const val TAG = "ReticulumServiceBinder"
        private const val STUB_ERROR = """{"success": false, "error": "Service AIDL stub — use NativeReticulumProtocol"}"""
    }

    /**
     * Restart the AutoInterface (used by ReticulumService on network change).
     */
    fun restartAutoInterface() {
        // No-op in native mode — AutoInterface is managed by NativeReticulumProtocol
    }

    /**
     * Announce LXMF destination (used by ReticulumService on network change).
     */
    fun announceLxmfDestination() {
        // No-op in native mode — announces handled by NativeReticulumProtocol
    }

    /**
     * Shutdown — satisfies AIDL contract and is called directly by ReticulumService.
     */
    override fun shutdown() {
        Log.d(TAG, "Shutdown called")
        onShutdown()
    }

    // =========================================================================
    // AIDL interface stubs — never called in native build
    // =========================================================================

    override fun initialize(
        configJson: String?,
        callback: IInitializationCallback?,
    ) {
        callback?.onInitializationError("AIDL stub — use NativeReticulumProtocol")
    }

    override fun getStatus(): String = state.networkStatus.get()

    override fun isInitialized(): Boolean = state.isInitialized()

    override fun createIdentity(): String = STUB_ERROR

    override fun loadIdentity(path: String?): String = STUB_ERROR

    override fun saveIdentity(
        privateKey: ByteArray?,
        path: String?,
    ): String = STUB_ERROR

    override fun createIdentityWithName(displayName: String?): String = STUB_ERROR

    override fun deleteIdentityFile(identityHash: String?): String = STUB_ERROR

    override fun importIdentityFile(
        fileData: ByteArray?,
        displayName: String?,
    ): String = STUB_ERROR

    override fun exportIdentityFile(
        identityHash: String?,
        filePath: String?,
    ): ByteArray? = null

    override fun recoverIdentityFile(
        identityHash: String?,
        keyData: ByteArray?,
        filePath: String?,
    ): String = STUB_ERROR

    override fun createDestination(
        identityJson: String?,
        direction: String?,
        destType: String?,
        appName: String?,
        aspectsJson: String?,
    ): String = STUB_ERROR

    override fun announceDestination(
        destHash: ByteArray?,
        appData: ByteArray?,
    ): String = STUB_ERROR

    override fun sendPacket(
        destHash: ByteArray?,
        data: ByteArray?,
        packetType: String?,
    ): String = STUB_ERROR

    override fun hasPath(destHash: ByteArray?): Boolean = false

    override fun requestPath(destHash: ByteArray?): String = STUB_ERROR

    override fun persistTransportData() {
        Unit
    }

    override fun getHopCount(destHash: ByteArray?): Int = -1

    override fun getNextHopInterfaceName(destHash: ByteArray?): String? = null

    override fun getPathTableHashes(): String = "[]"

    override fun probeLinkSpeed(
        destHash: ByteArray?,
        timeoutSeconds: Float,
        deliveryMethod: String?,
    ): String = STUB_ERROR

    override fun getDebugInfo(): String = """{"status": "native_mode", "aidl": "stub"}"""

    override fun sendLxmfMessage(
        destHash: ByteArray?,
        content: String?,
        sourceIdentityPrivateKey: ByteArray?,
        imageData: ByteArray?,
        imageFormat: String?,
        fileAttachments: MutableMap<Any?, Any?>?,
    ): String = STUB_ERROR

    override fun getLxmfIdentity(): String = STUB_ERROR

    override fun getLxmfDestination(): String = STUB_ERROR

    override fun registerCallback(callback: IReticulumServiceCallback?) {
        callback?.let { broadcaster.register(it) }
    }

    override fun unregisterCallback(callback: IReticulumServiceCallback?) {
        callback?.let { broadcaster.unregister(it) }
    }

    override fun restorePeerIdentities(peerIdentitiesJson: String?): String = STUB_ERROR

    override fun restoreAnnounceIdentities(announcesJson: String?): String = STUB_ERROR

    override fun forceExit() {
        onForceExit()
    }

    override fun registerReadinessCallback(callback: IReadinessCallback?) {
        Unit
    }

    override fun setConversationActive(active: Boolean) {
        Unit
    }

    override fun getBleConnectionDetails(): String = "[]"

    override fun recallIdentity(destHash: ByteArray?): String = """{"found": false}"""

    override fun getRNodeRssi(): Int = -100

    override fun reconnectRNodeInterface() {
        Unit
    }

    override fun isSharedInstanceAvailable(): Boolean = false

    override fun getFailedInterfaces(): String = "[]"

    override fun getInterfaceStats(interfaceName: String?): String? = null

    override fun getDiscoveredInterfaces(): String = "[]"

    override fun isDiscoveryEnabled(): Boolean = false

    override fun getAutoconnectedInterfaceEndpoints(): String = "[]"

    override fun setOutboundPropagationNode(destHash: ByteArray?): String = STUB_ERROR

    override fun getOutboundPropagationNode(): String = STUB_ERROR

    override fun requestMessagesFromPropagationNode(
        identityPrivateKey: ByteArray?,
        maxMessages: Int,
    ): String = STUB_ERROR

    override fun getPropagationState(): String = STUB_ERROR

    override fun sendLxmfMessageWithMethod(
        destHash: ByteArray?,
        content: String?,
        sourceIdentityPrivateKey: ByteArray?,
        deliveryMethod: String?,
        tryPropagationOnFail: Boolean,
        imageData: ByteArray?,
        imageFormat: String?,
        imageDataPath: String?,
        fileAttachments: MutableMap<Any?, Any?>?,
        fileAttachmentPaths: MutableMap<Any?, Any?>?,
        replyToMessageId: String?,
        iconName: String?,
        iconFgColor: String?,
        iconBgColor: String?,
    ): String = STUB_ERROR

    override fun provideAlternativeRelay(relayHash: ByteArray?) {
        Unit
    }

    override fun setIncomingMessageSizeLimit(limitKb: Int) {
        Unit
    }

    override fun sendLocationTelemetry(
        destHash: ByteArray?,
        locationJson: String?,
        sourceIdentityPrivateKey: ByteArray?,
        iconName: String?,
        iconFgColor: String?,
        iconBgColor: String?,
    ): String = STUB_ERROR

    override fun sendTelemetryRequest(
        destHash: ByteArray?,
        sourceIdentityPrivateKey: ByteArray?,
        timebaseMs: Long,
        isCollectorRequest: Boolean,
    ): String = STUB_ERROR

    override fun setTelemetryCollectorMode(enabled: Boolean): String = STUB_ERROR

    override fun setTelemetryAllowedRequesters(allowedHashesJson: String?): String = STUB_ERROR

    override fun storeOwnTelemetry(
        locationJson: String?,
        iconName: String?,
        iconFgColor: String?,
        iconBgColor: String?,
    ): String = STUB_ERROR

    override fun sendReaction(
        destHash: ByteArray?,
        targetMessageId: String?,
        emoji: String?,
        sourceIdentityPrivateKey: ByteArray?,
    ): String = STUB_ERROR

    override fun establishLink(
        destHash: ByteArray?,
        timeoutSeconds: Float,
    ): String = STUB_ERROR

    override fun closeLink(destHash: ByteArray?): String = STUB_ERROR

    override fun getLinkStatus(destHash: ByteArray?): String = STUB_ERROR

    override fun getReticulumVersion(): String? = null

    override fun getLxmfVersion(): String? = null

    override fun getBleReticulumVersion(): String? = null

    override fun getRmspServers(): String = "[]"

    override fun fetchRmspTiles(
        destinationHashHex: String?,
        publicKey: ByteArray?,
        geohash: String?,
        zoomMin: Int,
        zoomMax: Int,
        timeoutMs: Long,
    ): ByteArray? = null

    override fun blockDestination(destinationHashHex: String?): String = STUB_ERROR

    override fun unblockDestination(destinationHashHex: String?): String = STUB_ERROR

    override fun restoreBlockedDestinations(hashesJson: String?): String = STUB_ERROR

    override fun blackholeIdentity(identityHashHex: String?): String = STUB_ERROR

    override fun unblackholeIdentity(identityHashHex: String?): String = STUB_ERROR

    override fun isTransportEnabled(): String = STUB_ERROR

    override fun initiateCall(
        destHash: String?,
        profileCode: Int,
    ): String = STUB_ERROR

    override fun answerCall(): String = STUB_ERROR

    override fun hangupCall() {
        Unit
    }

    override fun setCallMuted(muted: Boolean) {
        Unit
    }

    override fun setCallSpeaker(speakerOn: Boolean) {
        Unit
    }

    override fun getCallState(): String = """{"status": "idle"}"""

    override fun requestNomadnetPage(
        destHash: ByteArray?,
        path: String?,
        formDataJson: String?,
        timeoutSeconds: Float,
    ): String = STUB_ERROR

    override fun cancelNomadnetPageRequest() {
        Unit
    }

    override fun getNomadnetDownloadProgress(): Float = -1.0f

    override fun getNomadnetRequestStatus(): String = ""

    override fun identifyNomadnetLink(destHash: ByteArray?): String = STUB_ERROR
}
