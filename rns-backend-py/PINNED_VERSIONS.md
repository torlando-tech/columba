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
| `rns` (Reticulum) | `git+https://github.com/torlando-tech/Reticulum` | **`817ecc31bd524c6268f816530c5115211b39cffc`** (SHA ✓) | RNS 1.3.8 + 4 torlando-tech fork patches. Carries the socket cleanup, PHY-stats RPC handling, known-destinations migration, and ratchet file-handle fixes. RNS 1.3.8 already includes the equivalent log file-handle fix upstream, so the rebased fork adds no duplicate log change. |
| `lxmf` (LXMF) | `git+https://github.com/torlando-tech/LXMF` | **`158b771c4c65ff43fd25764b00f5555ea543ab2e`** (SHA ✓) | torlando-tech fork: `set_external_generator()` to bypass Python multiprocessing on Android + `receiving_interface` passed to the delivery callback for opportunistic messages. SHA is the tip of `feature/receiving-interface-capture` as of 2026-05-14; builds as `lxmf-0.9.2`. |
| `ble-reticulum` | `git+https://github.com/torlando-tech/ble-reticulum.git` | **`07d941304c9a1dc3a8e58087b3b974ff3d229e56`** (SHA ✓) | Provides `BLEInterface` + `bluetooth_driver` that the bundled `ble_modules/` adapters subclass. SHA is the tip of `main` as of 2026-05-14; builds as `ble-reticulum-0.2.2`. |
| `cryptography` | PyPI | `>=42.0.0` | Range, not pinned — Chaquopy resolves a native wheel for the target ABI. Acceptable: it's a well-tested transitive dep, not a protocol-correctness surface. |
| `u-msgpack-python` | PyPI | unpinned | Sideband-compatible telemetry + LXST signalling wire format. Pure-Python, stable API. |

## Decisions made during the Phase B restore

### `patches/` tree — intentionally NOT restored

`release/v0.10.x`'s `python/patches/RNS/{Destination.py,__init__.py}` carried
context-manager fixes for RNS file-handle leaks (ratchet I/O + the `log()`
function). They are **not restored** here because:

1. The pinned RNS fork commit `817ecc31` **already includes** the ratchet I/O
   context-manager fixes, while upstream RNS 1.3.8 includes the equivalent
   `log()` context-manager fix. The plan's instruction is to skip the `patches/`
   tree when the pinned commit already has the fixes — it does.
2. The patch *deployment* mechanism — `reticulum_wrapper.py::_deploy_rns_patches()`,
   which copied the patched files over the pip-installed RNS at runtime — is
   **not** being restored (the slim-Python design deletes `reticulum_wrapper.py`).
   Restoring `patches/` without a deployer would be dead weight.

If a future RNS pin regresses on those fixes, the correct response is to bump
the pin to a fork commit that has them — **not** to re-introduce a runtime
file-patcher.

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
