# Columba ↔ Sideband LXMF interop suite

Pytest harness driving a **Columba app on an Android emulator** and a
**headless Sideband daemon on the test host** as two LXMF peers on the
same RNS network. Exercises every protocol-meaningful message field
upstream LXMF defines that both apps implement, in both directions.

Reactions and replies are deliberately excluded — `FIELD_REACTION` (0x10)
is Columba-specific and not present in upstream `LXMF.LXMessage`.
Everything else round-trips.

## What it covers

| Field / shape | A→B (Columba→Sideband) | B→A (Sideband→Columba) | Delivery methods |
|---|---|---|---|
| Text only | ✓ | ✓ | DIRECT, OPPORTUNISTIC, PROPAGATED† |
| `FIELD_IMAGE` (0x06) | ✓ | ✓ | DIRECT |
| `FIELD_FILE_ATTACHMENTS` (0x05) | ✓ | ✓ | DIRECT |
| `FIELD_ICON_APPEARANCE` (0x04) | ✓ | ✓ | DIRECT |
| `FIELD_TELEMETRY` (0x02) | ✓ | ✓ | OPPORTUNISTIC |
| `FIELD_AUDIO` (0x07) | ✓ | ✓ | DIRECT |

† PROPAGATED tests are slow (~7 min each, lxmd-dependent), marked
`@pytest.mark.slow`, and skipped by default. Opt in with `pytest -m slow`.

Verification is bidirectional and strict: each test compares the
inbound content + field bytes on the receiving side against the
outbound payload.

## How verification works

| Peer | Send surface | Receive verification |
|---|---|---|
| **Columba** (Android, emulator) | `adb shell am broadcast` against the debug-only `TestReceiver` (`network.columba.test.SEND_*`) | `logcat` `rx_msg source=stream …` / `rx_location source=stream …` lines (hex-escaped, single-token) |
| **Sideband** (Python, headless on test host) | direct `SidebandCore.send_message(...)` / `send_with_fields(...)` calls from the test process | (a) `sqlite3` query against `app_storage/sideband.db` for the inbound row, OR (b) an LXMRouter-level inbound *tap* that captures the raw LXMessage before Sideband's filters can drop it (used for telemetry-only frames + format-mismatched fields) |

Both peers share the host `rnsd`'s shared instance (port 37428) — same
transport, same path table, no NAT trickery.

### Two-sided path-readiness gate

After a Columba data wipe or fresh install the new identity isn't yet
announced to Sideband (and vice-versa). The session-scoped `interop`
fixture re-announces both peers and blocks until:

  1. Columba's RNS path table has a route to Sideband (`HAS_PATH` returns 1).
  2. Sideband's RNS Identity cache holds Columba's public key (`RNS.Identity.recall` non-None).
  3. Columba's RNS Identity cache holds Sideband's public key (proxied via a one-shot `SEND_DIRECT` that completes with `msg_sent` rather than stalling on `requesting path`).

Without (3), the first attachment-bearing test would time out for ~30s
on path resolution.

## Telemetry encoding caveat

`FIELD_TELEMETRY` *transport* is interop-clean — both apps put bytes in
field 2 — but *encoding* is not:

- Columba sends UTF-8 JSON (`{"lat": …, "lon": …}`).
- Sideband sends msgpacked `Telemeter` blobs.

Neither side decodes the other's payload through its domain-specific
decoder. Columba's `rx_location` observer expects JSON; Sideband's
`Telemeter.from_packed` expects msgpack. The interop tests assert
**bytes survive the round-trip**, not that they parse on the
receiving end — that's a separate domain-decoding project.

## Layout

```
tests/interop/
├── README.md                 # this file
├── requirements.txt          # pytest + pytest-timeout
├── pytest.ini                # markers, timeout method, default `-m "not slow"`
├── conftest.py               # session-scoped peer fixtures + path-ready gate
├── peer_sideband.py          # SidebandCore daemon lifecycle + DB reader +
│                             # send wrapper + LXMRouter inbound tap
├── peer_columba.py           # TestReceiver wrapper + logcat scanner
├── verify.py                 # shared helpers (hex-unescape, field decode)
├── fixtures/                 # binary test payloads (tiny PNG, opus tone, text file)
├── test_text.py              # text-only — all 3 delivery methods × both directions
├── test_image.py             # text + FIELD_IMAGE
├── test_file.py              # text + FIELD_FILE_ATTACHMENTS
├── test_icon.py              # FIELD_ICON_APPEARANCE
├── test_telemetry.py         # FIELD_TELEMETRY
├── test_audio.py             # FIELD_AUDIO
├── setup_emulator.sh         # one-time pre-flight emulator prep
└── run.sh                    # convenience runner (sets defaults + invokes pytest)
```

## Prerequisites

- A Python venv with upstream RNS + LXMF + the Sideband checkout importable.
  Reuses `~/.reticulum-host/venv` by default (already has RNS/LXMF installed
  for the pre-existing `lxmd` / `rnsd` / `nomadnet` daemons).
- An `adb`-connected Android emulator with the Columba `pythonBackendDebug`
  APK installed. The first arm64 emulator that `adb devices` lists is used
  unless `COLUMBA_EMULATOR_SERIAL` overrides.
- A running host `rnsd` (the Columba dual-build's normal hub setup is fine
  — Sideband joins the shared instance via port 37428 automatically).
- For PROPAGATED tests: a reachable `lxmd` propagation node. The default
  hex matches the existing launch-agent setup
  (`33f2621f135146ce30f0767d811af2b6`).

## One-time emulator prep

`ADD_TCP_CLIENT` from a backgrounded broadcast fails on Android's
`mAllowStartForeground` gate. The setup script forces a foreground
launch, writes the interface entry, then force-restarts Columba to
apply the new config:

```bash
COLUMBA_EMULATOR_SERIAL=emulator-5554 ./setup_emulator.sh
```

Re-run safe; idempotent.

## Running

```bash
./run.sh                       # full default suite (~50s; excludes slow markers)
./run.sh -k test_text          # filter by name
./run.sh -m slow               # also include PROPAGATED tests (~7 min each)
./run.sh --no-prop             # explicit "skip propagation" toggle
```

## Running in CI (target shape)

The suite is structured to support unattended CI execution. Expected
runner setup (not yet wired in this repo's CI):

1. Boot an arm64 Android emulator headless (`emulator -no-window …`)
   and install the `pythonBackendDebug` APK.
2. Start `rnsd` (and `lxmd` if you want PROPAGATED tests) from a known
   config dir.
3. Clone the Sideband source tree (read-only is fine).
4. `pip install -r tests/interop/requirements.txt`.
5. Export env vars: `COLUMBA_EMULATOR_SERIAL`, `COLUMBA_PROP_NODE_HEX`,
   `SIDEBAND_SRC` (path to the cloned tree).
6. Run `./tests/interop/setup_emulator.sh` once per emulator boot.
7. `pytest tests/interop --junit-xml=interop-results.xml`.

The suite emits xunit XML on every run for CI ingest. Each test is
independent and uses a fresh temp Sideband config dir, so retries are
safe.

## Why a Python harness, not Gradle JVM tests

Sideband is Python — `SidebandCore` is the actual peer in this suite,
not a stub or an LXMF mock. Importing it requires CPython + the
Sideband repo on `PYTHONPATH`. Running it under Gradle would mean
either bundling a CPython runtime (no) or shelling out per test
(slower, no shared state). A pytest harness keeps the Sideband peer
in-process for the whole session and shells out to `adb` per Columba
call.

## Adding a new test

1. Create / extend `fixtures/` if you need new bytes.
2. Add a `def test_<field>_<direction>(interop, …)` in the matching file.
3. Use `interop.columba.send_*(...)` / `interop.sideband.send_*(...)`
   to originate, and `interop.<peer>.wait_for_message(...)` or
   `wait_for_tapped_message(...)` to verify. The helpers do the
   time-boxed polling + assertion.

## When tests start failing

Most spurious failures trace to one of:

1. **`rnsd` / `lxmd` / `nomadnet` host stack wedged**. Verify via
   `lxmd --status --timeout 5` and `rnpath -t`. If a daemon's
   unresponsive, `launchctl unload && launchctl load …` the matching
   plist and restart Columba on the emulator (`adb shell am force-stop network.columba.app.debug && monkey …`).
2. **Columba's RNS instance has stale paths**. Symptom: Columba's send
   stalls on `requesting path` despite Sideband being announced.
   Fix: `setup_emulator.sh` + force-stop + relaunch.
3. **Sideband DB lock contention**. Sideband holds a write lock during
   LXM ingestion; the harness's read poll backs off and retries. If
   you see `sqlite3.OperationalError` in test output, that's the lock
   contention — the test still passes if the row eventually appears.
