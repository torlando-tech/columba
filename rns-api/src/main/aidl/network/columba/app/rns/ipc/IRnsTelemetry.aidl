// AIDL surface mirroring the Kotlin RnsTelemetry interface.
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - sendLocationTelemetry / sendTelemetryRequest → "receipt": MessageReceipt
//   - Result<Unit>  → Bundle.EMPTY
//
// timebase is a nullable long — represented as (value, hasValue) since AIDL
// has no boxed-primitive support. allowedHashes is `in String[]` because Set
// isn't a direct AIDL type; :rns-ipc converts Set↔Array on each side.
package network.columba.app.rns.ipc;

import network.columba.app.rns.api.model.IconAppearance;
import network.columba.app.rns.api.model.Identity;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback;

oneway interface IRnsTelemetry {
    void sendLocationTelemetry(
        in byte[] destinationHash,
        String locationJson,
        in Identity sourceIdentity,
        in @nullable IconAppearance iconAppearance,
        in IRnsResultCallback cb);

    void sendTelemetryRequest(
        in byte[] destinationHash,
        in Identity sourceIdentity,
        long timebase,
        boolean hasTimebase,
        boolean isCollectorRequest,
        in IRnsResultCallback cb);

    void setTelemetryCollectorMode(boolean enabled, in IRnsResultCallback cb);

    void storeOwnTelemetry(
        String locationJson,
        in @nullable IconAppearance iconAppearance,
        in IRnsResultCallback cb);

    void setTelemetryAllowedRequesters(in String[] allowedHashes, in IRnsResultCallback cb);

    // SharedFlow<String> (JSON payloads): observer register/unregister.
    void registerTelemetryObserver(in IRnsStringEventCallback cb);
    void unregisterTelemetryObserver(in IRnsStringEventCallback cb);
}
