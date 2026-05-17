# `:rns-host` — Reticulum host service + UI-side AIDL proxy

This module owns the `:reticulum` foreground service that runs the live RNS
stack, plus the UI-side glue that talks to it over AIDL.

## Process topology — RNS runs in `:reticulum` only

The dual-build runs **two** processes per APK:

| Process | Hilt-provided `RnsBackend` | What it owns |
|---|---|---|
| `network.columba.app[.debug]` (UI) | `BoundRnsBackend` (AIDL proxy) | UI, ViewModels, Room writes, DataStore |
| `network.columba.app[.debug]:reticulum` (FGS) | flavor-local backend (`ChaquopyRnsBackend` or `NativeRnsBackend`) | Python interpreter, `RNS.Reticulum()` + `LXMRouter`, `KotlinBLEBridge`, all interface sockets, all LXST audio I/O |

Both processes resolve the same `@InstallIn(SingletonComponent::class)`
Hilt graph, but Hilt's `SingletonComponent` is **per-process** — each process
runs every `@Provides` once. The single canonical `RnsBackend` binding is
provided by [`ProcessAwareBackendModule`](src/main/kotlin/network/columba/app/rns/host/di/ProcessAwareBackendModule.kt),
which branches at provider time on [`ProcessDetector.detect(context)`](src/main/kotlin/network/columba/app/rns/host/process/ProcessType.kt):

- In `:reticulum` → returns the `@LocalBackend`-qualified flavor-local
  backend. Constructing it loads CPython (python flavor) or starts
  reticulum-kt (kotlin flavor) — intentional pre-warm.
- In UI / TEST → returns [`BoundRnsBackend`](src/main/kotlin/network/columba/app/rns/host/ipc/BoundRnsBackend.kt),
  which delegates every call to the `:reticulum`-process backend via
  [`ReticulumServiceConnection`](src/main/kotlin/network/columba/app/rns/host/ReticulumServiceConnection.kt)
  + [`RnsBackendClient`](../rns-ipc/src/main/kotlin/network/columba/app/rns/ipc/RnsBackendClient.kt).
  The `@LocalBackend`-qualified provider is never resolved on this side, so
  no Python loads + no sockets bind in the UI pid.

Why this matters: backgrounding the UI, swiping away the activity, or
Android OOM-killing the UI process leaves the mesh node running. That's the
whole point of running on Android — the LXMF stack is always-on
infrastructure for the local network, not a foreground app.

### `BoundRns*` republishing pattern

Each `BoundRns*` sub-wrapper holds the same `StateFlow<RnsBackend?>` (sourced
from `ReticulumServiceConnection.bind(context, scope).stateIn(...)`) and
delegates per shape:

- **Suspend methods** → `backendFlow.filterNotNull().first().<sub>.method(args)` —
  awaits a live binding before forwarding.
- **`Flow<T>` accessors** → `backendFlow.filterNotNull().flatMapLatest { it.<sub>.method() }` —
  rebinds cancel the previous subscription and resubscribe to the fresh
  backend without a terminal completion reaching UI collectors.
- **`StateFlow<T>` / `SharedFlow<T>` vals** → same `flatMapLatest` pattern
  fed into `stateIn`/`shareIn` with a sensible seed so initial reads don't
  block.
- **Non-suspend mutators** (e.g. `setBatteryProfile`,
  `setConversationActive`) → `scope.launch { awaitBound().method(args) }` —
  fire-and-forget after awaiting bind.
- **Non-suspend getters** (e.g. `getRNodeRssi`, `getBleConnectionDetails`)
  → read `backendFlow.value?.<sub>.method()` with a documented sentinel
  fallback (`-100` / `"[]"`) when no backend is bound yet. The underlying
  `ClientRns*` already caches these from observer flows, so we pass through
  rather than introducing a second cache layer.

`SharingStarted.Eagerly` is intentional throughout `BoundRnsBackend` — lazy
sharing would mean the first observer pays the bind latency. Eager keeps the
binding in-flight as soon as Hilt resolves the singleton at app start.

## Defense-in-depth: backend ctor process assertion

Both [`ChaquopyRnsBackend`](../rns-backend-py/src/main/kotlin/network/columba/app/rns/backend/py/ChaquopyRnsBackend.kt)
and [`NativeRnsBackend`](../rns-backend-kt/src/main/kotlin/network/columba/app/rns/backend/kt/NativeRnsBackend.kt)
assert in their `init {}` blocks (DEBUG builds only) that
`Application.getProcessName()` contains `:reticulum`. A bug in Hilt wiring
that lands the local backend in the UI process trips this check immediately
— without it the symptom is silent (Python loads in the wrong pid;
AutoInterface fights itself for the multicast bind).

Release builds skip the check so a defensive bug never crashes the FGS.

## `ReticulumService` pre-warm

[`ReticulumService.onCreate`](src/main/kotlin/network/columba/app/rns/host/ReticulumService.kt)
injects `RnsBackend` (Hilt-resolved to the local backend in `:reticulum`)
and logs `"Pre-warmed RnsBackend in :reticulum pid=<pid> -> <class>"` on
each service start. Touching the field triggers eager Hilt construction; the
ctor assertion above ensures it's the right process. The log line is the
success signal — grep for it during on-device verification.

## `:reticulum` self-init from persisted snapshot

When `:reticulum` is OOM-killed (or force-stopped via `kill -9`), Android
`START_STICKY` restarts the service in a new pid. The UI's `BoundRnsBackend`
detects binder death and rebinds via `onServiceConnected`. Flow observers
resubscribe cleanly via `flatMapLatest`.

To bring the live stack back up without requiring the UI process to be alive,
[`ReticulumConfigSnapshot`](src/main/kotlin/network/columba/app/rns/host/persistence/ReticulumConfigSnapshot.kt)
+ [`BackendInitializer`](src/main/kotlin/network/columba/app/rns/host/persistence/BackendInitializer.kt)
implement a snapshot-driven self-init path:

1. After every successful UI-driven `rnsCore.initialize(config)`, the UI
   writes a sanitized snapshot of the [ReticulumConfig] to
   `filesDir/rns_config_snapshot.bin`. **Identity key is intentionally
   stripped** — only the identity hash is recorded, so the plaintext key
   never lives on disk.
2. On `ReticulumService.onCreate`, after pre-warm + foreground start,
   `BackendInitializer.initializeFromSnapshot(rnsBackend)` runs in
   `serviceScope`:
   - Reads the snapshot file (returns null if absent — first launch path).
   - Looks up the active identity in Room via `IdentityRepository` (already
     in `:data`).
   - Decrypts the 64-byte delivery key via Keystore-backed
     `IdentityKeyProvider` (also `:data`).
   - Composes the full `ReticulumConfig` and invokes
     `rnsBackend.core.initialize(config)`.
3. **Idempotent under races**: if UI happens to be alive and beats us to
   `initialize()`, the local backend's `runtime.start()` early-returns when
   `running.get()` is true. Both call paths converge to the same READY
   state.

Wire format is Android [Parcel] bytes (the same encoding the AIDL boundary
trusts), versioned with a leading int so a format change discards stale
snapshots cleanly instead of crashing on `BadParcelableException`. Use
`writeParcelable` (not raw `writeToParcel`) when persisting so the
classloader prefix is included — the matching `readParcelable` on the
reader side relies on it.

First-launch path stays the same: no snapshot exists yet, so the snapshot
reader returns null and `:reticulum` waits for UI to drive `initialize()`.
The successful UI initialize then writes the first snapshot, and every
subsequent FGS restart self-recovers without UI involvement.
