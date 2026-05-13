// Root binding the UI process holds across the AIDL boundary. Mirrors the
// Kotlin RnsBackend interface — the single binding the UI process binds to,
// and the source of sub-interface IBinder references (RnsCore, RnsLxmf,
// RnsTelephony, RnsTelemetry, RnsNomadnet, RnsTransportAdmin).
//
// Every method is `oneway` with a typed callback. Sub-interface accessors use
// the same fire-once callback shape as data-returning methods, so the contract
// is uniform: nothing on the AIDL surface can ever block the caller's thread,
// eliminating an entire class of ANR/deadlock failure modes by construction.
//
// The :rns-ipc client adapter wraps each accessor in
// `suspendCancellableCoroutine` at bind time, caches the resulting binder for
// the lifetime of the service binding, and re-fetches if the service crashes
// and re-binds (DeadObjectException recovery via fresh oneway-callback round).
//
// Capability observation:
//   - getCapabilities      — fire-once snapshot via callback; client uses to
//                            seed its StateFlow<BackendCapabilities>.
//   - registerCapabilitiesObserver / unregisterCapabilitiesObserver — host
//                            pushes updates as capabilities mutate at runtime
//                            (e.g., LXST jar absence in stripped-down test
//                            builds, RNode disconnect downgrades).
package network.columba.app.rns.ipc;

import network.columba.app.rns.ipc.callback.IRnsCapabilitiesCallback;
import network.columba.app.rns.ipc.callback.IRnsCoreCallback;
import network.columba.app.rns.ipc.callback.IRnsLxmfCallback;
import network.columba.app.rns.ipc.callback.IRnsNomadnetCallback;
import network.columba.app.rns.ipc.callback.IRnsTelemetryCallback;
import network.columba.app.rns.ipc.callback.IRnsTelephonyCallback;
import network.columba.app.rns.ipc.callback.IRnsTransportAdminCallback;

oneway interface IRnsBackend {
    // ==================== Sub-interface accessors ====================
    void getCore(in IRnsCoreCallback cb);
    void getLxmf(in IRnsLxmfCallback cb);
    void getTelephony(in IRnsTelephonyCallback cb);
    void getTelemetry(in IRnsTelemetryCallback cb);
    void getNomadnet(in IRnsNomadnetCallback cb);
    void getTransportAdmin(in IRnsTransportAdminCallback cb);

    // ==================== Capabilities ====================
    void getCapabilities(in IRnsCapabilitiesCallback cb);
    void registerCapabilitiesObserver(in IRnsCapabilitiesCallback cb);
    void unregisterCapabilitiesObserver(in IRnsCapabilitiesCallback cb);
}
