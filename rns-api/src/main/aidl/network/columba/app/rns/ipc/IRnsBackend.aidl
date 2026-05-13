// Root binding the UI process holds across the AIDL boundary. Mirrors the
// Kotlin RnsBackend interface — the single binding the UI process binds to,
// and the source of sub-interface IBinder references (RnsCore, RnsLxmf,
// RnsTelephony, RnsTelemetry, RnsNomadnet, RnsTransportAdmin).
//
// Sub-interface accessors (getCore/getLxmf/...) are SYNCHRONOUS by design:
// they return pre-existing IBinder references on the host stub and complete
// in microseconds. The UI process calls each accessor once at bind time and
// caches the resulting binder; subsequent calls go directly to the sub-binder
// without traversing IRnsBackend.
//
// This is a deliberate deviation from the literal "all methods oneway" rule
// in the dual-build plan: oneway-with-callback for accessor methods would add
// real latency at bind time without ANR benefit, since the binder reference
// is immediately available on the host side. Methods that DO touch protocol
// state (the capability snapshot, observer registrations) stay oneway.
//
// Capability observation:
//   - getCapabilities      — oneway snapshot via callback; client uses to seed
//                            its StateFlow<BackendCapabilities>.
//   - registerCapabilitiesObserver / unregisterCapabilitiesObserver — oneway;
//                            host pushes updates as capabilities mutate at
//                            runtime (e.g., LXST jar absence in stripped-down
//                            test builds, RNode disconnect downgrades).
package network.columba.app.rns.ipc;

import network.columba.app.rns.ipc.IRnsCore;
import network.columba.app.rns.ipc.IRnsLxmf;
import network.columba.app.rns.ipc.IRnsNomadnet;
import network.columba.app.rns.ipc.IRnsTelemetry;
import network.columba.app.rns.ipc.IRnsTelephony;
import network.columba.app.rns.ipc.IRnsTransportAdmin;
import network.columba.app.rns.ipc.callback.IRnsCapabilitiesCallback;

interface IRnsBackend {
    // ==================== Sub-interface accessors (sync) ====================
    IRnsCore getCore();
    IRnsLxmf getLxmf();
    IRnsTelephony getTelephony();
    IRnsTelemetry getTelemetry();
    IRnsNomadnet getNomadnet();
    IRnsTransportAdmin getTransportAdmin();

    // ==================== Capabilities (oneway) ====================
    oneway void getCapabilities(in IRnsCapabilitiesCallback cb);
    oneway void registerCapabilitiesObserver(in IRnsCapabilitiesCallback cb);
    oneway void unregisterCapabilitiesObserver(in IRnsCapabilitiesCallback cb);
}
