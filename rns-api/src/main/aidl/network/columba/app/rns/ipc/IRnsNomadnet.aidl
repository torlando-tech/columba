// AIDL surface mirroring the Kotlin RnsNomadnet interface.
//
// Bundle key conventions for IRnsResultCallback payloads:
//   - requestNomadnetPage → "page": NomadnetPageResult
//   - Result<Unit>        → Bundle.EMPTY
package network.columba.app.rns.ipc;

import network.columba.app.rns.ipc.callback.IRnsBoolCallback;
import network.columba.app.rns.ipc.callback.IRnsFloatCallback;
import network.columba.app.rns.ipc.callback.IRnsFloatEventCallback;
import network.columba.app.rns.ipc.callback.IRnsResultCallback;
import network.columba.app.rns.ipc.callback.IRnsStringCallback;
import network.columba.app.rns.ipc.callback.IRnsStringEventCallback;

oneway interface IRnsNomadnet {
    void requestNomadnetPage(
        String destinationHash,
        String path,
        in @nullable String formDataJson,
        float timeoutSeconds,
        in IRnsResultCallback cb);

    void cancelNomadnetPageRequest(in IRnsResultCallback cb);

    // Snapshot getters for the request status / download progress flows.
    void getNomadnetRequestStatus(in IRnsStringCallback cb);
    void getNomadnetDownloadProgress(in IRnsFloatCallback cb);

    // Result<Boolean>: use IRnsBoolCallback (carries onError for the suspend boundary).
    void identifyNomadnetLink(String destinationHash, in IRnsBoolCallback cb);

    // StateFlow<String> status: observer register/unregister.
    void registerRequestStatusObserver(in IRnsStringEventCallback cb);
    void unregisterRequestStatusObserver(in IRnsStringEventCallback cb);

    // StateFlow<Float> download progress: observer register/unregister.
    void registerDownloadProgressObserver(in IRnsFloatEventCallback cb);
    void unregisterDownloadProgressObserver(in IRnsFloatEventCallback cb);
}
