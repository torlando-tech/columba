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
| `rns` (Reticulum) | `git+https://github.com/torlando-tech/Reticulum` | **`3759488eba880489562268d9a1a993f443d1b8bd`** (SHA ✓) | RNS 1.5.5 (branch `rebase/columba-1.5.5`), the torlando-tech 6-commit hardening patch set rebased on top of upstream 1.5.5: socket cleanup, narrow PHY-stats RPC backoff, ratchet file-handle fixes, deterministic AutoInterface teardown, and lifecycle-race fixes. 1.5.5 adds the live interface attach/detach/reload API (`Reticulum.attach_interface` / `detach_interface` / `reload_interface`) that the Python backend now uses for hot-reload instead of a full RNS restart; `RNS/Reticulum.py` and `RNS/Transport.py` are untouched upstream 1.5.5, so that API is pure upstream code. The fork is exactly upstream tag 1.5.5 + those 6 commits. Verified: the fork's own suite (`tests/all.py`, 100 tests incl. the `AutoInterfaceTeardownTests` hardening class that exercises the rebased `AutoInterface.py`) is green on this SHA, plus a standalone attach/reload/detach smoke test confirming the reload replaces the interface object while leaving untouched interfaces live. The former pin was `754654fe` (upstream 1.5.2 + the same 6 commits); 4/6 patch-ids are byte-identical across the rebase, the other 2 differ only by the 1.5.5 `AutoInterface.discovery_sockets` compatibility line preserved during conflict resolution. |
| `lxmf` (LXMF) | `git+https://github.com/torlando-tech/LXMF` | **`8912186e48b482a76bf04e2ac4b6c8940991aecc`** (SHA ✓) | LXMF 1.1.0 with validated external native stamping, cooperative cancellation and stale-result rejection, plus `receiving_interface` and `receiving_hops` on opportunistic delivery. |
| `ble-reticulum` | `git+https://github.com/torlando-tech/ble-reticulum.git` | **`63557acfe5c923dcdafeb7bc37508bf835f16514`** (SHA ✓) | Provides `BLEInterface` + `bluetooth_driver` that the bundled `ble_modules/` adapters subclass. SHA is the `v0.2.3` release tag (main tip, 2026-10-03); builds as `ble-reticulum-0.2.3`. Carries the `BLEPeerInterface.ifac_size` fix (#45: RNS 1.5.2 reads `interface.ifac_size` on every inbound packet, and the spawned BLE peer interface previously did not inherit it, so every inbound packet raised `AttributeError` and no peer announce was ever ingested) plus the BLE data-path fixes: `BLEPeerInterface.announce_rate_grace`/`_penalty` for the RNS stats RPC (#47), same-address duplicate-identity normalization so a fixed-MAC peer reconnecting via the other BLE mode is not misread as Android MAC rotation (#48), and the scanner-wedge health check that disambiguates a quiet RF room from a genuinely wedged adapter via the adapter's own `Powered` state, without a false "reboot required" (#49). Behavior-only change: no `Interface` callback signature or inbound-path attribute contract changes. |
| `cryptography` | PyPI | `>=42.0.0` | Range, not pinned — Chaquopy resolves a native wheel for the target ABI. Acceptable: it's a well-tested transitive dep, not a protocol-correctness surface. |
| `u-msgpack-python` | PyPI | unpinned | Sideband-compatible telemetry + LXST signalling wire format. Pure-Python, stable API. |

## Decisions made during the Phase B restore

### `patches/` tree — intentionally NOT restored

`release/v0.10.x`'s `python/patches/RNS/{Destination.py,__init__.py}` carried
context-manager fixes for RNS file-handle leaks (ratchet I/O + the `log()`
function). They are **not restored** here because:

1. The pinned RNS fork commit `3759488e` **already includes** the ratchet I/O
   context-manager fixes (carried by hardening commit `2f3cf488`, present on the
   rebased 1.5.5 branch), while upstream RNS 1.5.5 includes the equivalent
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
