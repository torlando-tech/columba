# Pinned Python wheel versions — `:rns-backend-py`

The Python flavor ships **upstream RNS/LXMF as the protocol stack**. These are
the only dependencies that carry protocol-correctness weight, so they are
pinned and bumps require a deliberate PR.

**Pin to commit SHA, not branch tip.** Branch tips move; a build done today and
a build done next month must produce the same protocol behaviour. This mirrors
`release/v0.10.x`'s reproducibility discipline (its commit `63c4a2b` did the
same).

The pins live in `build.gradle.kts`'s `chaquopy { defaultConfig { pip { ... } } }`
block — there is no `requirements.txt` (per the dual-build plan, pip pinning
moved into the Gradle build script).

## Current pins

| Package | Ref | Pinned to | Notes |
|---|---|---|---|
| `rns` (Reticulum) | `git+https://github.com/torlando-tech/Reticulum` | **`9aa9d29e1ee0eaa58e6d810c42a29b348c8168ed`** | Branch `columba-android-minimal-1.6.0`, based exactly on upstream RNS 1.6.0 (`cb1260891d1b6da8f6e9b21542c4f0cc0725cac4`). Only Android AutoInterface teardown/hot-reload fixes and packet-local RSSI/SNR/Q snapshots before the asynchronous receive queue are carried. `RNS/Stamps.py` and `RNS/Resource.py` remain identical to upstream 1.6.0; no external stamper, Link RPC shim, socket or ratchet patches. |
| `lxmf` (LXMF) | `git+https://github.com/torlando-tech/LXMF` | **`09cd6be4324345ed07964e9f6e5372308f6d6883`** | LXMF 1.3.0 with `receiving_interface` and `receiving_hops` on opportunistic delivery, plus mutex-protected SQLite compatibility. `LXStamper.py` is unchanged upstream and delegates generation and cancellation to `RNS.Stamps`. |
| `ble-reticulum` | `git+https://github.com/torlando-tech/ble-reticulum.git` | **`63557acfe5c923dcdafeb7bc37508bf835f16514`** (SHA ✓) | Provides `BLEInterface` + `bluetooth_driver` that the bundled `ble_modules/` adapters subclass. SHA is the `v0.2.3` release tag (main tip, 2026-10-03); builds as `ble-reticulum-0.2.3`. Carries the `BLEPeerInterface.ifac_size` fix (#45: RNS 1.5.2 reads `interface.ifac_size` on every inbound packet, and the spawned BLE peer interface previously did not inherit it, so every inbound packet raised `AttributeError` and no peer announce was ever ingested) plus the BLE data-path fixes: `BLEPeerInterface.announce_rate_grace`/`_penalty` for the RNS stats RPC (#47), same-address duplicate-identity normalization so a fixed-MAC peer reconnecting via the other BLE mode is not misread as Android MAC rotation (#48), and the scanner-wedge health check that disambiguates a quiet RF room from a genuinely wedged adapter via the adapter's own `Powered` state, without a false "reboot required" (#49). Behavior-only change: no `Interface` callback signature or inbound-path attribute contract changes. |
| `cryptography` | PyPI | `>=42.0.0` | Range, not pinned — Chaquopy resolves a native wheel for the target ABI. Acceptable: it's a well-tested transitive dep, not a protocol-correctness surface. |
| `u-msgpack-python` | PyPI | unpinned | Sideband-compatible telemetry + LXST signalling wire format. Pure-Python, stable API. |

## Decisions made during the Phase B restore

### `patches/` tree — intentionally NOT restored

The Python backend does not deploy replacement RNS modules or patch installed
source files at runtime. Platform changes belong in the pinned Android fork;
the protocol stack and stamper stay upstream-owned.

The fork leaves upstream PHY-stat RPC exception handling intact. Radio delivery
metrics come from the received message's packet/link snapshot, not the latest
interface reading; opportunistic interface/hop annotations remain in the LXMF
fork. Missing RSSI/SNR stays absent, while valid zero values are preserved.

### Upstream Android stamping

LXMF 1.3.0 uses RNS 1.6.0's stock `RNS.Stamps.generate_stamp` dispatcher,
including fractional costs. On Android it selects the upstream persistent
fork/Manager/mmap worker path without semaphore-backed Event/Queue objects.
There is no Kotlin stamper injection, external-generator registry, replacement
dispatcher, or native hook. The packaged environment has no multiprocessing
stub.

Runtime stop shuts down the LXMF router, cancels deferred messages through
`LXMRouter.cancel_outbound`, and signals `RNS.Stamps.cancel_work` until upstream
workers release their jobs. The upstream worker owns process joins and Manager
shutdown. Cancellation is repeated during teardown because workblock expansion
precedes upstream job registration.

The LXMF SQLite compatibility patch permits `sqlite3.threadsafety >= 1`;
single-thread SQLite builds still fail. The store's existing connection mutex
serializes shared-connection operations, including close. Constructor-only
schema initialization happens before the router exposes the store. No Android
validation errors are suppressed.

Device regressions use the actual Chaquopy packages to observe persistent-worker
dispatch, independently verify an 8.5-cost SHA-256 proof, and cover deferred
cancellation, child-process cleanup, host stop and runtime reuse:

```sh
./gradlew :rns-backend-py:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=network.columba.app.rns.backend.py.PythonRnsUpstreamStamperInstrumentedTest
```

Host Python tests run through `python3 rns-backend-py/run_python_tests.py`.
The dedicated Python CI job installs the exact pins and runs each file in an
isolated interpreter. Do not use dependency-free unittest discovery for this
mixed stub/integration suite.

Cold Gradle bootstrap uses a 60-second network timeout for distribution
downloads, including the official URL's GitHub redirect, before CI checks start.

### `TorClientInterface.py` — not restored (Tor out of scope)

`release/v0.10.x` shipped `python/TorClientInterface.py` (a `TCPClientInterface`
subclass routing over a local SOCKS5/Tor proxy). It is small and self-contained
(`import RNS` + stdlib only), but Tor support is not in scope for the first
Python-flavor cut. It can be re-added as a single file later if wanted —
restore with `git show 66d983f^:python/TorClientInterface.py`.

## Restored interface adapters — known gap inherited from `66d983f^`

`ble_modules/android_ble_interface.py` does
`from drivers.android_ble_driver import AndroidBLEDriver`, but `66d983f^`'s
Python tree has only `drivers/__init__.py` (no `drivers/android_ble_driver.py`).
At v0.10.x runtime the BLE interface files were *deployed* into the RNS
interfaces directory (`~/.reticulum/interfaces/`) alongside `BLEInterface.py` /
`bluetooth_driver.py` from the `ble-reticulum` wheel, and `drivers/` was
populated there. That deployment step lived in the deleted `reticulum_wrapper.py`.

**This is restored verbatim and left as-is.** Wiring BLE-on-Python so
`Transport.find_interfaces()` discovers `AndroidBLEInterface` is an on-device
integration task (BLE is not on the Phase B verification checklist). When that
work happens, either `PythonRnsRuntime` grows an interface-deployment step or
the `from drivers.android_ble_driver import` line is repointed at
`ble_modules.android_ble_driver`.
