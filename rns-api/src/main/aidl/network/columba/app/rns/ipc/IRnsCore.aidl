// AIDL surface mirroring the Kotlin RnsCore interface.
//
// All methods are oneway with result-delivery callbacks. Suspend functions on
// the Kotlin side map to oneway-callback pairs; :rns-ipc's RnsBackendClient
// wraps each call in suspendCancellableCoroutine to restore the suspend shape.
//
// Bundle key conventions for IRnsResultCallback payloads (per-method):
//   - createIdentity / loadIdentity / recallIdentity → "identity": Identity
//   - createIdentityWithName / importIdentityFile   → "key_data": byte[],
//                                                      "display_name": String,
//                                                      "identity_hash_hex": String
//   - createDestination → "destination": Destination
//   - sendPacket        → "receipt": PacketReceipt
//   - establishLink     → "link": Link
//   - probeLinkSpeed    → "probe": LinkSpeedProbeResult
//   - establishConversationLink / getConversationLinkStatus
//                       → "link_result": ConversationLinkResult
//   - restorePeerIdentities / restoreAnnounceIdentities
//                       → "count": int
//   - Result<Unit>      → Bundle.EMPTY
package network.columba.app.rns.ipc;

import network.columba.app.rns.api.model.AnnounceRestoreEntry;
import network.columba.app.rns.api.model.Destination;
import network.columba.app.rns.api.model.DestinationType;
import network.columba.app.rns.api.model.Direction;
import network.columba.app.rns.api.model.Identity;
import network.columba.app.rns.api.model.Link;
import network.columba.app.rns.api.model.PacketType;
import network.columba.app.rns.api.model.PeerIdentityEntry;
import network.columba.app.rns.api.model.ReticulumConfig;
import network.columba.app.rns.ipc.callback.IRnsAnnounceCallback;
import network.columba.app.rns.ipc.callback.IRnsBoolCallback;
import network.columba.app.rns.ipc.callback.IRnsByteArrayCallback;
import network.columba.app.rns.ipc.callback.IRnsIntCallback;
import network.columba.app.rns.ipc.callback.IRnsLinkEventCallback;
import network.columba.app.rns.ipc.callback.IRnsNetworkStatusCallback;
import network.columba.app.rns.ipc.callback.IRnsPacketCallback;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;
import network.columba.app.rns.ipc.callback.IRnsStringCallback;
import network.columba.app.rns.ipc.callback.IRnsStringListCallback;

oneway interface IRnsCore {
    // ==================== Initialization & lifecycle ====================

    void initialize(in ReticulumConfig config, in IRnsResultCallback cb);
    void shutdown(in IRnsResultCallback cb);

    // StateFlow<NetworkStatus>: snapshot getter + observer register/unregister.
    void getCurrentNetworkStatus(in IRnsNetworkStatusCallback cb);
    void registerNetworkStatusObserver(in IRnsNetworkStatusCallback cb);
    void unregisterNetworkStatusObserver(in IRnsNetworkStatusCallback cb);

    // ==================== Identity management ====================

    void createIdentity(in IRnsResultCallback cb);
    void loadIdentity(String path, in IRnsResultCallback cb);
    void saveIdentity(in Identity identity, String path, in IRnsResultCallback cb);

    // recallIdentity returns Identity? — non-Result suspend on Kotlin side.
    // onSuccess payload Bundle: optional "identity" key (absent ↔ null).
    void recallIdentity(in byte[] hash, in IRnsResultCallback cb);

    void createIdentityWithName(String displayName, in IRnsResultCallback cb);
    void importIdentityFile(in byte[] fileData, String displayName, in IRnsResultCallback cb);
    void exportIdentityFile(in byte[] keyData, String filePath, in IRnsByteArrayCallback cb);

    // Synchronous-on-Kotlin getter; oneway+callback across AIDL.
    // Returns nullable byte[] via IRnsByteArrayCallback.onSuccess(@nullable byte[]).
    void getFullIdentityKey(in IRnsByteArrayCallback cb);

    // ==================== Destination management ====================

    void createDestination(
        in Identity identity,
        in Direction direction,
        in DestinationType type,
        String appName,
        in List<String> aspects,
        in IRnsResultCallback cb);

    void announceDestination(in Destination destination, in @nullable byte[] appData, in IRnsResultCallback cb);

    void triggerAutoAnnounce(String displayName, in IRnsResultCallback cb);

    // ==================== Packet operations ====================

    void sendPacket(in Destination destination, in byte[] data, in PacketType packetType, in IRnsResultCallback cb);

    // Flow<ReceivedPacket>: observer register/unregister.
    void registerPacketObserver(in IRnsPacketCallback cb);
    void unregisterPacketObserver(in IRnsPacketCallback cb);

    // ==================== Link operations ====================

    void establishLink(in Destination destination, in IRnsResultCallback cb);
    void closeLink(in Link link, in IRnsResultCallback cb);
    void sendOverLink(in Link link, in byte[] data, in IRnsResultCallback cb);

    // Flow<LinkEvent>: observer register/unregister.
    void registerLinkObserver(in IRnsLinkEventCallback cb);
    void unregisterLinkObserver(in IRnsLinkEventCallback cb);

    // ==================== Path & transport ====================

    void hasPath(in byte[] destinationHash, in IRnsBoolCallback cb);
    void requestPath(in byte[] destinationHash, in IRnsResultCallback cb);
    void persistTransportData(in IRnsResultCallback cb);

    // Synchronous-on-Kotlin getters; oneway+callback across AIDL.
    // Both return nullable values — IRnsIntCallback / IRnsStringCallback express null via the convention.
    void getHopCount(in byte[] destinationHash, in IRnsIntCallback cb);
    void getNextHopInterfaceName(in byte[] destinationHash, in IRnsStringCallback cb);

    void getPathTableHashes(in IRnsStringListCallback cb);

    // probeLinkSpeed returns LinkSpeedProbeResult (non-Result suspend on Kotlin).
    // Bundle key: "probe".
    void probeLinkSpeed(in byte[] destinationHash, float timeoutSeconds, String deliveryMethod, in IRnsResultCallback cb);

    void isTransportEnabled(in IRnsBoolCallback cb);

    // ==================== Conversation Link Management ====================

    void establishConversationLink(in byte[] destinationHash, float timeoutSeconds, in IRnsResultCallback cb);

    // closeConversationLink returns Result<Boolean>; success payload uses IRnsBoolCallback.
    void closeConversationLink(in byte[] destinationHash, in IRnsBoolCallback cb);

    void getConversationLinkStatus(in byte[] destinationHash, in IRnsResultCallback cb);

    // ==================== Announce handling ====================

    // Flow<AnnounceEvent>: observer register/unregister.
    void registerAnnounceObserver(in IRnsAnnounceCallback cb);
    void unregisterAnnounceObserver(in IRnsAnnounceCallback cb);

    // ==================== Peer / Announce identity restoration ====================
    //
    // AIDL has no Pair type and List<byte[]> is unsupported, so both methods
    // accept small Parcelable wrappers. :rns-ipc converts between these and
    // the Kotlin List<Pair<String, ByteArray>> signature at the seam.

    void restorePeerIdentities(in List<PeerIdentityEntry> entries, in IRnsResultCallback cb);
    void restoreAnnounceIdentities(in List<AnnounceRestoreEntry> entries, in IRnsResultCallback cb);

    // ==================== Peer Blocking & Blackhole ====================

    void blockDestination(String destinationHashHex, in IRnsResultCallback cb);
    void unblockDestination(String destinationHashHex, in IRnsResultCallback cb);
    void blackholeIdentity(String identityHashHex, in IRnsResultCallback cb);
    void unblackholeIdentity(String identityHashHex, in IRnsResultCallback cb);

    // TODO(A.10): registerAlternativeRelayHandler + registerServiceInitListener
    // land here when the ColumbaApplication mutable closures are replaced with
    // AIDL callback registrations. Deferred until the Kotlin RnsCore interface
    // gains the matching register* methods.
}
