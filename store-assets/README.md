# Play Store screenshot pipeline

Builds the Google Play phone screenshots (1080x1920) from the app itself:
an in-app demo dataset seeds the `columba-e2e` emulator, `capture.sh`
screenshots the real UI, and `compose.py` frames it on a gradient canvas
with the headline caption.

    ./gradlew :app:assembleNoSentryPythonBackendDebug
    store-assets/capture.sh             # boot AVD, install, seed, capture, compose
    python3 compose.py                  # re-compose only (SKIP_INSTALL=1 skips install)

If the main checkout is mid-WIP, build in a clean worktree and point the
script at its output: `APK_DIR=~/worktrees/<wt>/app/build/outputs/...`.

Outputs land in `out/` as PNG + webp (q82). `out/` is gitignored; the
listing copy in `listing.yaml` is the source of truth.

## Layout of this directory
- `capture.sh` - full pipeline: boot AVD -> install -> `pm clear` ->
  seed -> demo mode -> screencap per screen -> live voice call ->
  screencap -> compose.
- `call_peer.py` - host-side Python RNS/LXMF peer (TCPInterface server on
  :4242 + LXST signal responder) the emulator places the call to. Runs
  from `.venv-call/` (gitignored; `python3 -m venv .venv-call &&
  .venv-call/bin/pip install rns lxmf`).
- `tools/x86_64/liblxst_opus_jni.so` - NDK build of lxst-kt
  `cpp/lxst_opus_jni.c` for x86_64; see the call section below.
- `compose.py` - compositor. Device geometry lives in `FRAMES` (device
  units, screen width 1280). The frame and the capture's clip mask are
  generated from that one dict with the same screen rect and radius, so
  the capture is always cut by the hole's own shape (never reaching the
  body's rounded corners) and the status-bar overlay height can never
  disagree with the punch hole position.
- `listing.yaml` - canvas, gradient, caption copy, capture per final.
- `captures/` - raw device captures, one file per screen, named
  `<id>.png` to match the manifest entry.
- `fonts/` - Roboto Medium (clock + captions), vendored.

## The demo dataset (in-app, debug-only)
`app/src/debug/.../test/StoreArtSeeder.kt` inserts a deterministic,
entirely fictional dataset (people, node names, messages, coordinates)
into Room via the normal DAOs, scoped to a seeded local identity created
through `IdentityRepository` so the unlock gate passes. Idempotent: all
keys derive from SHA-256 of stable labels and insert with REPLACE/Upsert.

Trigger (debug builds only, same surface as the e2e harness):

    adb shell am broadcast -p network.columba.app.debug \
        -a network.columba.test.SEED_STORE_ART
    # logcat -s COLUMBA_TEST -> store_art_seeded contacts=5 announces=15 ...

It enables no live discovery beyond the ONBOARD AutoInterface (capture.sh
runs ONBOARD first; the interface clears the "Columba is offline" banner,
and the emulator's NAT'd WiFi cannot see real peers, so the Network tab
stays exactly as seeded). The map centers deterministically on a seeded
default offline region row - no GPS fake needed. The dataset content is
marketing copy, not test fixtures - change names/copy in
`StoreArtSeeder.kt` and the captions in `listing.yaml` together.

## Status bar
Captures are taken with Android demo mode on (`capture.sh` does this);
the AOSP emulator SystemUI hides notifications with it, though the
clock/battery demo commands do not apply on this emulator build. The
compositor's `status_bar: true` overlay therefore paints a minimal 9:41
bar (Roboto clock + wifi/battery primitives) tall enough to cover the
capture's real status bar and the frame's punch hole - it also handles
devices whose vendor SystemUI ignores demo mode entirely (the test phone).

## The voice-call screen (05_call)
The call capture is a real LXMF voice call: `call_peer.py` runs a Python
LXST responder on the host; capture.sh points the app's TCP uplink at
`10.0.2.2:4242`, seeds Maya's destination as the peer identity, and taps
through the call dialog. On this x86_64 AVD two workarounds apply:
- `settings put global columba_lxst_native_audio 0` (read by
  `PythonCallManager`) selects LXST-kt's Phase-2 pipeline (AudioTrack +
  Kotlin codec); the Oboe path's native libs are arm-only and would kill
  `:reticulum` with UnsatisfiedLinkError on the first audio frame. Reset
  to 1 after the capture.
- Phase-2 still dlopens `libopus` + `liblxst_opus_jni` (arm-only in the
  AAR), so capture.sh injects x86_64 builds into the installed APK's lib
  dir: `libopus.so` + `libc++.so` from the emulator's media APEX, and the
  committed JNI glue from `tools/x86_64/`.

## Captions
The 2026-11 "No X" ladder (no phone number / no account / no
towers / no middlemen / no carrier). It replaces the 2026-09-07 approved copy
and is pending re-approval. `caption_text` may contain an explicit newline
to pin the two-line break; without one the compositor splits balanced.

`06_interfaces` closes the set with a single line (`Internet optional.`,
917px at 120px): capture.sh opens Settings -> Network -> Manage Interfaces
and taps +, so the modal lists Auto Discovery / TCP Client / Bluetooth LE /
RNode LoRa - the caption is the claim, the modal is the proof. The + FAB
has no text, so that tap is a coordinate (964,2221 on the 1080x2400 device).

## Delivery
- Play Console accepts the PNGs directly (1080x1920 is inside limits).
- To review on-device over Columba itself, send the webp (Columba renders
  webp inline, not png).
