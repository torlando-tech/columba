---
name: columba-python-migration
description: This skill should be used when working on the Strangler Fig migration of reticulum_wrapper.py, creating or modifying rns_api.py, working on RnsApiClient or ChaquopyRnsApiClient in Kotlin, extracting business logic from Python to Kotlin, modifying PythonWrapperManager, working on health monitoring, telemetry collection, RMSP, delivery state machines, message reception, link speed probing, identity file management, event/callback extraction, or any task that touches the Python-to-Kotlin migration boundary. It provides the full migration plan, phase dependencies, thin API surface, and anti-patterns to avoid.
---

# Columba Python Migration Skill — Strangler Fig

## Overview

`reticulum_wrapper.py` is 8,032 lines and has grown from a thin RNS/LXMF wrapper into a massive orchestration layer. ~70% of its code is business logic (delivery state machines, telemetry collection, health monitoring, RMSP, event routing) that doesn't need Python — it only needs to *call* RNS/LXMF APIs. The remaining ~30% genuinely wraps RNS/LXMF Python APIs.

**Goal**: Invert the abstraction — create a thin Python API (~15 core RNS/LXMF operations), move all business logic to Kotlin, and eventually delete `reticulum_wrapper.py`.

**Strategy**: Strangler Fig — leave `reticulum_wrapper.py` untouched, build the new system alongside it, redirect callers one piece at a time, delete when dead.

## When to Use This Skill

This skill should be used when:
- Creating or modifying `python/rns_api.py`
- Working on `RnsApiClient`, `ChaquopyRnsApiClient`, or `RnsResult` in Kotlin
- Extracting business logic from `reticulum_wrapper.py` into Kotlin
- Modifying `PythonWrapperManager.kt` to redirect calls to `RnsApiClient`
- Working on any of Phases 0-8 of the migration
- Adding feature flags for dual-path (old wrapper vs new API) coexistence
- Writing tests for migrated Kotlin business logic
- Modifying callback/event routing between Python and Kotlin

## Migration Status

Check this section to see what has been completed. Update it as phases are finished.

| Phase | Description | Status | PR |
|-------|-------------|--------|----|
| 0 | Foundation — Thin Python API + Kotlin Interface | Not Started | — |
| 1 | Health Monitoring (~130 lines) | Not Started | — |
| 2 | Event/Callback System (~300 lines) | Not Started | — |
| 3 | Telemetry Collection (~400 lines) | Not Started | — |
| 4 | RMSP Map Client (~200 lines) | Not Started | — |
| 5 | Identity File Management (~300 lines) | Not Started | — |
| 6 | Link Speed Probing (~450 lines) | Not Started | — |
| 7 | Delivery State Machine (~1,500 lines) | Not Started | — |
| 8 | Message Reception Pipeline (~800 lines) | Not Started | — |
| Final | Delete reticulum_wrapper.py | Not Started | — |

## Phase 0: Foundation — Thin Python API + Kotlin Interface

**Goal**: Create the new API layer without changing anything existing. Both paths coexist.

### New Files

| File | Purpose |
|------|---------|
| `python/rns_api.py` (~250-400 lines) | Thin Python class exposing only raw RNS/LXMF operations |
| `app/.../service/rns/RnsApiClient.kt` | Kotlin interface mirroring the thin API |
| `app/.../service/rns/RnsResult.kt` | Simple sealed result type |
| `app/.../service/rns/ChaquopyRnsApiClient.kt` | Implementation calling `rns_api.py` via Chaquopy |

### Thin API Surface (~15 Core Operations)

```
Category        Methods
─────────       ──────────────────────────────────────────────────────────
Lifecycle       initialize(config), shutdown(), get_heartbeat()
Identity        create_identity(), recall_identity(hash),
                load_identity_from_file(path), save_identity_to_file(key, path)
Path/Transport  has_path(hash), request_path(hash), hops_to(hash),
                get_next_hop_bitrate(hash)
LXMF            send_lxmf_message(dest, key, content, fields, method),
                set_outbound_propagation_node(hash),
                sync_propagation_messages()
Link            establish_link(hash, timeout), get_link_stats(hash)
Announce        announce_destination(hash, app_data),
                register_announce_handler(aspect)
Callbacks       set_delivery_callbacks(on_delivered, on_failed, on_sent),
                set_message_received_callback(cb),
                set_announce_callback(cb)
```

### Changes to Existing Files

**None.** Old path untouched. This is the foundation — both paths coexist.

### Testing

- Unit test `ChaquopyRnsApiClient` with MockK
- Integration test: call `rns_api.get_heartbeat()` through Chaquopy, verify response

## Phase 1: Health Monitoring (~130 lines extracted)

**Why first**: Smallest extraction, very low risk, validates the dual-path pattern.

- Redirect `HealthCheckManager.getHeartbeat()` to call `rnsApiClient.getHeartbeat()` instead of `wrapperManager.withWrapper{}`
- Create `PropagationStateMonitor.kt` — Kotlin coroutine that polls propagation state from `rns_api`, detects changes, broadcasts via `CallbackBroadcaster`
- New thin API method: `get_propagation_state()` -> `{state, state_name, progress, messages_received}`
- **Dead code** in reticulum_wrapper.py: `_heartbeat_loop()`, `get_heartbeat()`, `_maintenance_loop()`, `_check_propagation_state_change()` (~130 lines)
- **Depends on**: Phase 0

## Phase 2: Event/Callback System (~300 lines extracted)

**Why second**: The 13 callback registrations are the main coupling mechanism. Extracting them early means every subsequent phase benefits.

- Create `RnsEventDispatcher.kt` with typed listener interfaces (delivery status, message received, announce, location, reaction, propagation state, alternative relay)
- `rns_api.py` receives a single `RnsEventDispatcher` reference at init, calls it for all events
- Remove individual `set_*_callback()` calls from `PythonWrapperManager`
- **Dead code** in reticulum_wrapper.py: All 13 `set_*_callback` methods and callback storage (~300 lines)
- **Depends on**: Phase 0

## Phase 3: Telemetry Collection (~400 lines extracted)

- Create `TelemetryCollector.kt` — stores peer telemetry, handles TTL cleanup, access control, stream responses
- Telemetry pack/unpack stays in Python (uses `umsgpack`, called during message send/receive)
- Collector state management (ConcurrentHashMap) and business rules move to Kotlin
- **Dead code**: `set_telemetry_collector_enabled()`, `_cleanup_expired_telemetry()`, `_send_telemetry_stream_response()`, `_store_telemetry_for_collector()` (~400 lines)
- **Depends on**: Phase 0, Phase 2

## Phase 4: RMSP Map Client (~200 lines from wrapper + partial rmsp_client.py)

- Create `RmspServerRegistry.kt` — stores discovered servers, queries by geohash/proximity (pure Kotlin)
- Create `RmspClient.kt` — orchestrates server queries using `rnsApiClient.establish_link()`
- Link-based RNS operations stay in `rns_api.py`
- **Dead code**: Wrapper's RMSP methods (~200 lines), registry logic in `rmsp_client.py` (~300 lines)
- **Depends on**: Phase 0, Phase 2 (for announce events)

## Phase 5: Identity File Management (~300 lines extracted)

- Create `IdentityFileManager.kt` — file scanning, path resolution, secure wipe, import/export orchestration
- Core RNS.Identity creation/loading stays in `rns_api.py`
- Redirect `IdentityManager.kt` to use `IdentityFileManager` instead of `wrapperManager.withWrapper{}`
- **Dead code**: `create_identity()`, `list_identity_files()`, `delete_identity_file()`, `import_identity_file()`, `export_identity_file()`, `recover_identity_file()` (~300 lines)
- **Depends on**: Phase 0

## Phase 6: Link Speed Probing (~450 lines extracted)

- Create `LinkSpeedProber.kt` — orchestration logic (try backchannel -> direct -> establish new -> heuristic fallback)
- Raw link operations (`establish_link`, `get_link_stats`) stay in `rns_api.py`
- Redirect `RoutingManager.probeLinkSpeed()` to `LinkSpeedProber`
- **Dead code**: `probe_link_speed()`, link finding/stat helpers (~450 lines)
- **Depends on**: Phase 0

## Phase 7: Delivery State Machine (~1,500 lines extracted) — LARGEST

- Create `DeliveryStateMachine.kt` — opportunistic timeout -> propagation fallback -> relay retry -> alternative relay
- Kotlin builds normalized fields map, `rns_api.send_lxmf_message()` converts to LXMF format
- All delivery tracking state (ConcurrentHashMaps for opportunistic messages, relay fallback, propagated tracking) moves to Kotlin
- **Dead code**: `send_lxmf_message()`, `send_lxmf_message_with_method()`, `_on_message_delivered()`, `_on_message_failed()`, `_on_message_sent()`, `on_alternative_relay_received()`, opportunistic timer, file notification helpers (~1,500 lines)
- **Depends on**: Phase 0, Phase 2, Phase 3

## Phase 8: Message Reception Pipeline (~800 lines extracted)

- Create `MessageReceptionRouter.kt` — routes incoming messages by type (telemetry, reaction, cease signal, regular message)
- `rns_api.py`'s `_on_lxmf_delivery` simplified to: extract raw fields -> convert to JSON -> call single Kotlin callback
- Business logic (collector storage, reaction routing, deduplication) moves to Kotlin handlers
- **Dead code**: Business logic from `_on_lxmf_delivery()`, field routing, buffering (~800 lines)
- **Depends on**: Phase 0, Phase 2, Phase 3

## Final Cleanup

Once all phases complete:
1. Verify `reticulum_wrapper.py` has no live callers
2. Redirect remaining `PythonWrapperManager.withWrapper{}` calls to `ChaquopyRnsApiClient`
3. Delete `reticulum_wrapper.py` (8,032 lines)
4. Rename `PythonWrapperManager` -> `ChaquopyLifecycleManager` (~300 remaining lines for bridge setup)
5. Delete dead code in `rmsp_client.py`

**End state**: Python layer is a single thin file (`rns_api.py`, ~250-400 lines), all business logic in testable Kotlin.

## Key Files

| File | Role |
|------|------|
| `python/reticulum_wrapper.py` (8,032 lines) | The monolith being strangled |
| `app/.../service/manager/PythonWrapperManager.kt` (896 lines) | Kotlin call site; shrinks per phase |
| `app/.../service/di/ServiceModule.kt` | DI container; new managers wired here |
| `app/.../service/manager/EventHandler.kt` | Reference pattern for event extraction |
| `python/rmsp_client.py` (646 lines) | Partially extracted in Phase 4 |
| `python/lxst_modules/call_manager.py` (730 lines) | Stays in Python (raw RNS.Link) |

## Dependency Graph

```
Phase 0 (Foundation)
  ├── Phase 1 (Health)
  ├── Phase 2 (Events/Callbacks)
  │     ├── Phase 3 (Telemetry)
  │     │     ├── Phase 7 (Delivery SM)
  │     │     └── Phase 8 (Reception)
  │     └── Phase 4 (RMSP)
  ├── Phase 5 (Identity)
  └── Phase 6 (Link Speed)
```

Phases 1, 5, 6 can run in parallel after Phase 0.
Phases 3, 4 require Phase 2.
Phases 7, 8 require Phases 2 and 3.

## Testing Strategy Per Phase

1. **Before**: Write characterization tests capturing current Python behavior
2. **During**: Kotlin unit tests with MockK for `RnsApiClient`
3. **After**: Feature flag to run both paths; compare results for one release
4. **Integration**: On-device tests exercising Kotlin -> `rns_api.py` -> RNS

## Anti-Patterns to Avoid

- **Never modify `reticulum_wrapper.py`** until final cleanup (Strangler Fig rule)
- **Never call `wrapperManager.withWrapper{}` from new Kotlin managers** — use `RnsApiClient`
- **Never put business logic in `rns_api.py`** — it's a thin pass-through only
- **Never skip feature flags** — every phase must be toggleable for rollback
- **Never import PyObject in new Kotlin managers** — use `RnsApiClient` interface
- **Remember Chaquopy list conversion** — Kotlin Lists must be converted to Python lists via `builtins.callAttr("list", list.toTypedArray())` before passing to Python

## Chaquopy Patterns for rns_api.py

### Calling rns_api.py methods from Kotlin

```kotlin
// In ChaquopyRnsApiClient.kt
override suspend fun getHeartbeat(): RnsResult<HeartbeatData> =
    withContext(Dispatchers.IO) {
        try {
            val result = rnsApi.callAttr("get_heartbeat")
            RnsResult.Success(parseHeartbeat(result))
        } catch (e: Exception) {
            RnsResult.Failure(e)
        }
    }
```

### Python thin API pattern

```python
# In rns_api.py — THIN, no business logic
class RnsApi:
    def get_heartbeat(self):
        """Returns raw heartbeat data from RNS."""
        if not self._reticulum:
            return {"status": "not_initialized"}
        return {
            "status": "ok",
            "uptime": time.time() - self._start_time,
            "transport_enabled": self._reticulum.is_transport_instance,
        }
```

### RnsResult sealed class

```kotlin
sealed class RnsResult<out T> {
    data class Success<T>(val data: T) : RnsResult<T>()
    data class Failure(val error: Throwable) : RnsResult<Nothing>()
}
```

## Migration Summary

| Phase | Lines Extracted | Risk | Size | Dependencies |
|-------|----------------|------|------|--------------|
| 0: Foundation | 0 (new code) | Low | Medium | None |
| 1: Health monitoring | ~130 | Very Low | Small | P0 |
| 2: Event/callbacks | ~300 | Low | Medium | P0 |
| 3: Telemetry | ~400 | Low | Medium | P0, P2 |
| 4: RMSP | ~500 | Low | Medium | P0, P2 |
| 5: Identity files | ~300 | Low | Small | P0 |
| 6: Link speed | ~450 | Medium | Medium | P0 |
| 7: Delivery SM | ~1,500 | High | Large | P0, P2, P3 |
| 8: Reception | ~800 | High | Large | P0, P2, P3 |
| **Total** | **~4,380** | | | |
