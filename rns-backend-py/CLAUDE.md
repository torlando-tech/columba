# `:rns-backend-py` — slim Chaquopy RnsBackend

This module is the **Python flavor** of the RNS backend seam: upstream Python
RNS/LXMF as the protocol stack, driven by Kotlin sub-impls through Chaquopy.

## The slim-Python rule (do not break this)

The `src/main/python/` tree contains **only**:

1. **Upstream RNS/LXMF wheels** — pip-installed via the `chaquopy { pip { ... } }`
   block in `build.gradle.kts`. Pinned in `PINNED_VERSIONS.md`.
2. **Architecturally-forced `RNS.Interface` adapters** — `ble_modules/`,
   `drivers/`, `rnode_interface.py`, `usb_bridge.py`. These exist in Python
   because `Transport.find_interfaces()` only discovers Python `RNS.Interface`
   subclasses — they cannot be replaced by Kotlin.
3. **Chaquopy environment stubs** — `jnius/`, `usb4a/`, `usbserial4a/`. Tiny
   shims that satisfy upstream import-time checks.
4. **`event_bridge.py`** — the ONE Columba-authored Python file with logic
   (~150 lines). A callback receiver that flattens RNS/LXMF events to dicts
   of primitives before they cross JNI.

**Adding a `rns_*.py` facade is a regression.** The Kotlin sub-impls
(`PythonRnsCore`, `PythonRnsLxmf`, …) call upstream RNS/LXMF methods *directly*
via `PyObject.callAttr(...)`. There is no `reticulum_wrapper.py` and there will
not be one. The `NoRnsFacadeInPythonBackend` Detekt rule enforces this — any
new `src/main/python/rns_*.py` fails the build.

**App-logic helpers live in Kotlin in `:rns-host`** (health monitoring,
link-speed probing, signal-quality parsing, identity file management, blocking,
interface-name formatting, RMSP propagation client, AutoInterface hot-add,
telemetry collection, event dispatch) and are **shared by both backends**.
Re-introducing their Python counterparts (`health_monitor.py`,
`link_speed.py`, `blocking_manager.py`, …) is a regression — they were
deliberately *not* restored from `66d983f^`.

The sanctioned escape valve for "this genuinely needs to run Python-side for
perf" is to extend `event_bridge.py` (it already runs on RNS internal threads
with the GIL held) — never a new facade file.

## Chaquopy footgun: Kotlin/Java Lists → Python lists

When passing a Kotlin/Java `List`/`ArrayList` to a Python function via
Chaquopy, you **must** convert it to a real Python list first, or Python sees
`'ArrayList' object is not iterable`.

Use the `PythonExt.toPyList()` helper in this module:

```kotlin
pyObj.callAttr("some_python_function", kotlinList.toPyList())
```

Never pass a raw Kotlin collection into a `callAttr` list parameter.

## Why this module applies the `com.chaquo.python` plugin (not `:app`)

Applying Chaquopy at module level keeps the Chaquopy runtime + Python wheels
off the `kotlinBackend` flavor's classpath entirely. `:rns-backend-py` only
reaches a build through `:rns-host`'s `pythonBackendImplementation` edge — a
`kotlinBackend` build configures this module but never executes its Chaquopy
tasks.

## Never do work on an RNS-allocated callback thread

Upstream RNS allocates a **fresh OS thread for every event** it hands to a
registered callback — `Transport.receive()` spawns one thread per announce per
registered announce handler, `Interface` spawns one per held-announce release,
and so on. With LXMF registering two announce handlers (delivery +
propagation) and `event_bridge.py` one, a mesh announce burst of N
destinations is ~3N threads. Whatever a handler does inline keeps its thread
alive, so a burst large enough (a peer replaying its announce buffer, a
device with a dozen BLE links) exhausts the process's thread slots: the next
`pthread_create` throws `OutOfMemoryError: pthread_create (1040KB max stack)
failed: Try again`, the `:reticulum` process dies, and every interface goes
down with it (columba#1228; Sentry `COLUMBA-E6/E2/DB/DZ/DP/EF`).

The rule: **a callback the upstream stack calls must return immediately.**
Hand the raw event to a module-level worker on a bounded queue and do the
translation / JNI hop there. `_AnnounceHandler.received_announce` →
`_enqueue_announce` → `_announce_worker` → `_dispatch_announce` is the worked
example (one worker, FIFO, bounded queue that sheds announcements — with a
rate-limited log — only when the Kotlin consumer is genuinely wedged). Tests
that assert on emitted payloads need `_flush_announce_queue()` first.

Shedding is a deliberate last resort, not a normal path: the announce stream
already drops announcements it cannot buffer Kotlin-side, and dropping under
a wedged consumer is far better than dying.

Teardown fences as well as drains: `deregister_callbacks()` runs
`_reset_announce_dispatch()`, which bumps the dispatch generation and discards
the backlog, so an announce queued against a stopped stack is never delivered
to the stack that replaces it (it would otherwise be persisted with the
restart's timestamp and show up as fresh peer activity).
