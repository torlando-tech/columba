# TOR-20 complete — KISS framing toggle in TCP Client wizard

**Branch:** `paperclip/TOR-20-expose-kiss_framing-toggle-in-tcp-client-wizard-final-page-gh-1178`
**Commit:** `7a4feaef7` — pushed to `origin` (Forgejo; GitHub mirror unreachable from this host during the run)
**Closes:** GH #1178

## What was built

New toggle card **"KISS Framing (TNC / Modem)"** on the TCP Client wizard final page (Review Configure), directly under the "Connect via Tor (Orbot)" card, same card style (surfaceVariant Card + title/bodySmall + Switch). Body text carries the upstream warning verbatim in spirit:

> Use only when connecting to a software TNC or soundmodem that exposes KISS-over-TCP. Do NOT enable for Reticulum TCPServerInterface or Backbone connections — it disables the internal reliability and recovery mechanisms.

Changes (5 files, +253/−1):
- `TcpClientWizardViewModel.kt` — `kissFraming` in wizard state, `toggleKissFraming()`, wired into `saveConfiguration()`, edit-mode load, and the new-custom-server reset.
- `ReviewConfigureStep.kt` — the toggle card under the Tor card.
- `ConfigFileParser.kt` — parses `kiss_framing` (truthy set `true/yes/on/1`, case-insensitive) so interfaces read back from a Reticulum config file keep the setting.
- `TcpClientWizardViewModelTest.kt` (+5 tests), `ConfigFileParserTest.kt` (+6 tests).

Emitter check (gap #4): the python-backend emitter writes `kiss_framing = true` (lowercase, only when on). RNS reads it via configobj `as_bool`, which lowercases before matching `t/true/yes/on/1` — accepted. Confirmed on-device: RNS instantiated the interface with no parse error.

## Verification

- Unit tests (`:app:testNoSentryPythonBackendDebugUnitTest`, `--offline`): **85/85 green** — TcpClientWizardViewModelTest 79/79, ConfigFileParserTest 6/6, 0 failures/errors/skips.
- Detekt (`:app:detekt`, `--offline`): exit 0.
- ktlint (`:app:ktlintCheck`, `--offline`, the task CI runs): **exit 0**. Caveat recorded for honesty: with ktlint-gradle 12.1.1 on this AGP/KGP combo, `:app` registers only `ktlintKotlinScriptCheck` (no source-set tasks), and the repo pins ktlint advisory (`ignoreFailures=true`, `build.gradle.kts:55`). So the changed files were additionally linted directly with the same engine (ktlint 1.5.0, `android_studio` style, repo `.editorconfig`): the diff introduces +30 violations vs the base versions, all in rule classes already present in the same files (indent / trailing-comma / argument-list-wrapping) — new code matches file-local style; `ConfigFileParser.kt` delta 0; no new violation classes in the 4 pre-existing files. Details in the verification summary.
- Device (`emulator-5554`, API 34 x86_64, `network.columba.app.debug`, flavor **noSentry+pythonBackend debug**): created TCP client interface `TOR20_KISS_Test` via the wizard; with the toggle ON the pulled Reticulum config (`files/reticulum/reticulum/config` via `run-as`) contains `kiss_framing = true` inside `[[TOR20_KISS_Test]]`; with the toggle OFF the line is absent. Logcat shows RNS parsed and instantiated the interface (`InterfaceUtils: shouldToggleBeEnabled(TOR20_KISS_Test)`); the subsequent `Connection refused` is expected — no listener on 127.0.0.1:8001 on the emulator.
- Build note: host network was down during the run, so the APK was built `--offline -x :rns-backend-py:installDebugPythonRequirements` against a chaquopy python tree copied from a sibling worktree whose `rns-backend-py/build.gradle.kts` is byte-identical (same Reticulum/LXMF/ble pins) — bundled RNS is the pinned build; a fresh-network rebuild reproduces identical python packages.

## Deliverables (registered on this issue)

| File | Attachment ID |
|---|---|
| Wizard final page, KISS card under Tor card | `cc73d426-53fe-469c-9b15-c27bb5e9442a` |
| Wizard final page, toggle ON before save | `96f3387a-bb4f-4bd0-a657-1e1043ac862b` |
| Config pull — `kiss_framing = true` present | `7af0fb5e-edd1-4d1c-b620-e60e0fda3a6c` |
| Config pull — `kiss_framing` absent | `09d81681-4ab4-42cb-a348-df76a6255225` |
| Verification summary | `a922cbdd-132e-439e-bace-1f5574e613bb` |
| TcpClientWizardViewModelTest XML (79/79) | `93e87b63-0111-4d2f-9513-6b6c3e948cc0` |
| ConfigFileParserTest XML (6/6) | `608763ca-d7c4-4928-a4ce-2ae2f5a225dd` |

## Manual test plan

**Preconditions:** Android 7.0+ (minSdk 24). Debug APK flavor: noSentry + pythonBackend (`network.columba.app.debug`). Any signed-in/onboarded state; no RNS interfaces required beyond the one created below.

### A. Reproduce the gap on the current released build

1. Install the current released Columba (stable release APK from GitHub releases / Obtainium).
2. Open **Settings → Network → Manage Interfaces → Add Interface → TCP Client → Custom server**.
3. Fill host (e.g. `127.0.0.1`) and port (`8001`), continue to the final **Review Settings** page.
   - **Expected (bug):** the page shows Host/Port, advanced options and the "Connect via Tor (Orbot)" card — there is **no KISS framing control anywhere**, so a KISS-over-TCP endpoint (software TNC / soundmodem) cannot be configured from the UI at all.
4. Save the interface, apply, then pull the generated config (`adb shell run-as <release-pkg> cat files/reticulum/reticulum/config`): the `[[...]]` block has **no `kiss_framing` line** — the option is unreachable end-to-end.

### B. Verify the fix on the attached debug APK

Install: `adb install -r app-noSentry-pythonBackend-x86_64-debug.apk` (from `app/build/outputs/apk/noSentryPythonBackend/debug/`).

1. Open **Settings → Network → Manage Interfaces → Add Interface → TCP Client → Custom server**; enter name `TOR20_KISS_Test`, host `127.0.0.1`, port `8001`; continue to **Review Settings**.
   - **Expected:** a card titled **"KISS Framing (TNC / Modem)"** appears directly **under** the "Connect via Tor (Orbot)" card, same card style, switch **off**, with the warning text "Use only when connecting to a software TNC or soundmodem that exposes KISS-over-TCP. Do NOT enable for Reticulum TCPServerInterface or Backbone connections — it disables the internal reliability and recovery mechanisms."
2. Toggle the KISS switch **on** (no save yet) and screenshot — switch is on.
3. Tap **Save**, then apply/restart RNS so the config regenerates.
4. Pull the config: `adb shell run-as network.columba.app.debug cat files/reticulum/reticulum/config`.
   - **Expected:** the `[[TOR20_KISS_Test]]` (or your name) block contains `kiss_framing = true`.
5. Confirm no-crash + parse: `adb logcat | grep -i TOR20_KISS` — RNS instantiates the interface (e.g. `InterfaceUtils: shouldToggleBeEnabled(TOR20_KISS_Test)`); a `Connection refused` for 127.0.0.1:8001 is expected with no listener and is not a fault.
6. Edit the interface (Manage Interfaces → tap it → final page).
   - **Expected:** the KISS switch renders **on** (edit-mode load round-trips the flag).
7. Toggle it **off**, save, apply, re-pull the config.
   - **Expected:** the `kiss_framing` line is **absent** from the block (emitter writes it only when on).
8. Regression: with the toggle off, the rest of the wizard behaves as before (Tor card, host/port, save/edit round-trip unchanged).
