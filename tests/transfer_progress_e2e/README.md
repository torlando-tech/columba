# Real LXMF Resource progress E2E

This suite proves the complete production path that previously dropped outgoing transfer progress:

1. A host-side Python LXMF recipient starts on a private Reticulum TCP server.
2. A throttled TCP proxy keeps the genuine RNS Resource active long enough to observe multiple polls.
3. A fresh Python-backend Columba APK runs on an Android emulator.
4. The harness completes onboarding, configures the TCP interface, adds the real recipient through the Contacts UI, selects a 1 MiB file through Android DocumentsUI, and sends it from the production conversation UI.
5. The test requires `Transferring Resource`, a genuine Resource percentage between 1 and 99, and the attachment name in the outgoing bubble.
6. The host recipient must receive the exact filename, byte count, SHA-256, and a valid LXMF message hash.

The screenshot, logcat, receiver log, proxy log, and JUnit XML are uploaded by CI as `transfer-progress-e2e-evidence`.

## Local run

Prerequisites:

- Python 3.11
- Android SDK emulator with an API 34 Google APIs x86_64 image
- A running emulator at `emulator-5554`
- The Python-backend x86_64 debug APK

```bash
python3.11 -m venv .scratch/transfer-progress-e2e-venv
.scratch/transfer-progress-e2e-venv/bin/pip install -r tests/transfer_progress_e2e/requirements.txt
./gradlew :app:assembleNoSentryPythonBackendDebug
COLUMBA_EMULATOR_SERIAL=emulator-5554 \
COLUMBA_E2E_APK=app/build/outputs/apk/noSentryPythonBackend/debug/app-noSentry-pythonBackend-x86_64-debug.apk \
COLUMBA_E2E_ARTIFACT_DIR=.scratch/transfer-progress-e2e-artifacts \
.scratch/transfer-progress-e2e-venv/bin/python -m pytest -v \
  tests/transfer_progress_e2e/test_transfer_progress_e2e.py
```

The harness disables every default app interface before adding the private test TCP interface. It then uses only loopback and the Android emulator's `10.0.2.2` host alias. It does not depend on or connect to public Reticulum hubs, propagation nodes, saved identities, or repository secrets.
