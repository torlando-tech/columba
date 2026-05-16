"""Columba (Android emulator) peer.

Drives Columba via the debug-only `TestReceiver` broadcast surface
(`app/src/debug/java/network/columba/app/test/TestReceiver.kt`). Verifies
inbound messages by scanning `logcat` for the `rx_msg source=stream …`
lines that `TestController.observeMessages` emits live as messages
arrive.

Sends: shell out to `adb shell am broadcast -a network.columba.test.SEND_*`.
Receives: capture `logcat` to a buffer + parse `rx_msg` lines.

Image / file / telemetry sends use a small in-emulator path-staging step:
the payload is `adb push`'d into a tmp file in the app's external files
dir, then a `SEND_IMAGE` / `SEND_FILE` action is broadcast with the path.
We piggy-back on the existing `network.columba.test.*` action namespace
but add per-payload extras here (the receiver-side code is small enough
that the additions remain readable).

NOTE: TestReceiver currently has SEND_DIRECT / SEND_OPP / SEND_PROP for
text only. The richer-payload SEND_* actions referenced in this file may
need stubs added on the Kotlin side — see `_assert_test_action_supported`.
"""

from __future__ import annotations

import base64
import re
import subprocess
import time
from dataclasses import dataclass
from typing import Optional


PKG = "network.columba.app.debug"
RECEIVER = f"{PKG}/network.columba.app.test.TestReceiver"
LOGCAT_TAG = "COLUMBA_TEST"


@dataclass
class ColumbaRxMsg:
    """One parsed `rx_msg source=stream …` line. All values come back
    `escape()`'d by `TestController` — Unicode control-pictures replace
    whitespace so each value is a single token. We un-escape here so
    tests assert against the original payload."""

    source_hex: str
    msg_id_hex: str
    content: str
    raw_line: str

    @classmethod
    def parse(cls, line: str) -> Optional["ColumbaRxMsg"]:
        # `rx_msg source=stream from=<hex> id=<hex> content=<escaped>`
        m = re.search(
            r"rx_msg\s+source=stream\s+from=([0-9a-fA-F]+)\s+"
            r"id=([0-9a-fA-F]+)\s+content=(\S*)",
            line,
        )
        if not m:
            return None
        return cls(
            source_hex=m.group(1).lower(),
            msg_id_hex=m.group(2).lower(),
            content=_unescape(m.group(3)),
            raw_line=line,
        )


def _sh_quote(s: str) -> str:
    """POSIX-shell single-quote escaping. Wraps `s` so the device shell
    sees it as one token — required for JSON payloads going through
    `am broadcast --es`."""
    return "'" + s.replace("'", "'\\''") + "'"


def _unescape(s: str) -> str:
    """Reverse `TestController.escape`'s control-picture substitution.

    The mapping comes from `TestController.escape` in
    `app/src/debug/.../test/TestController.kt`:
        ' '  (0x20) ↔ '␣' (U+2423 OPEN BOX)
        '\\n' (0x0A) ↔ '⏎' (U+23CE RETURN SYMBOL)
        '\\r' (0x0D) ↔ '␍' (U+240D SYMBOL FOR CARRIAGE RETURN)
        '\\t' (0x09) ↔ '␉' (U+2409 SYMBOL FOR HORIZONTAL TABULATION)
    """
    return (
        s.replace("␣", " ")
         .replace("⏎", "\n")
         .replace("␍", "\r")
         .replace("␉", "\t")
    )


class ColumbaPeer:
    """ADB-driven Columba peer. Construct with the emulator serial and
    optionally the host rnsd address (defaults to `10.0.0.145:4242`)."""

    def __init__(
        self,
        serial: str,
        rnsd_host: str = "10.0.0.145",
        rnsd_port: int = 4242,
        prop_node_hex: Optional[str] = None,
    ):
        self.serial = serial
        self.rnsd_host = rnsd_host
        self.rnsd_port = rnsd_port
        self.prop_node_hex = prop_node_hex
        self._logcat_proc: Optional[subprocess.Popen] = None
        self._identity_hex: Optional[str] = None

    # ---- lifecycle -----------------------------------------------------

    def start(self, ready_timeout: float = 120.0) -> None:
        """Bring Columba to a state where TestReceiver responds with a
        live identity.

        The harness deliberately does NOT reconfigure interfaces here
        — `ADD_TCP_CLIENT` requires foreground-service privileges to
        restart Reticulum, and the post-monkey-launch window doesn't
        always satisfy Android's foreground-service gate (you get
        `startForegroundService() not allowed`). Instead, the
        emulator is expected to come pre-configured (see
        `setup_emulator.sh` in this dir).

        Steps:
          1. Wake the screen.
          2. Bring Columba to foreground via `monkey` LAUNCHER intent.
          3. Wait for the backend to report a live LXMF identity.

        `ready_timeout` covers cold-start path: a fresh-install emulator
        takes ~60-90s before the Reticulum backend reports its identity.
        Once running, subsequent fixture invocations resolve identity in
        under 2s because GET_DEST returns synchronously."""
        self._adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        self._adb(
            "shell", "monkey", "-p", PKG,
            "-c", "android.intent.category.LAUNCHER", "1",
            check_output=False,
        )
        # Foreground-state grace period: monkey's intent dispatch returns
        # before the Activity is fully resumed, and TestReceiver's
        # ensureInit blocks on Hilt singletons that resolve once the
        # Activity is past onResume.
        time.sleep(5)

        # Optional propagation node hex — set only if provided. The
        # broadcast is safe even before the backend is "ready" (it just
        # updates a config flag, no service restart).
        if self.prop_node_hex:
            self.broadcast("SET_PROP_NODE", hex=self.prop_node_hex)

        # Resolve identity hash so callers know how to address us.
        self._refresh_identity(timeout=ready_timeout)

    def stop(self) -> None:
        self._stop_logcat()
        # Don't force-stop the app — the next test may reuse it. If a
        # full reset is wanted, the harness can call `force_stop()`.

    def force_stop(self) -> None:
        self._adb("shell", "am", "force-stop", PKG, check_output=False)

    # ---- identity ------------------------------------------------------

    @property
    def identity_hex(self) -> str:
        if self._identity_hex is None:
            raise RuntimeError("Columba peer not started")
        return self._identity_hex

    def _refresh_identity(self, timeout: float = 30.0) -> None:
        """Poll GET_DEST until the backend reports a real hash. After a
        fresh APK install or cold launch the backend may still be
        initializing — the receiver replies with
        `dest_err reason=not_ready` for several seconds. We re-issue
        GET_DEST every poll cycle so a `not_ready` interim doesn't
        block forever."""
        deadline = time.time() + timeout
        last_err: str | None = None
        while time.time() < deadline:
            self.clear_logcat()
            self.broadcast("GET_DEST")
            time.sleep(2.0)
            for line in self._read_logcat_lines():
                m = re.search(r"\bdest=([0-9a-fA-F]+)", line)
                if m:
                    self._identity_hex = m.group(1).lower()
                    return
                err = re.search(r"dest_err\s+reason=(\S+)", line)
                if err:
                    last_err = err.group(1)
            time.sleep(2.0)
        raise TimeoutError(
            "Columba did not respond to GET_DEST within "
            f"{timeout}s — last reply: dest_err reason={last_err}"
        )

    # ---- path resolution ----------------------------------------------

    def has_path_to(self, dest_hex: str, timeout: float = 60.0) -> bool:
        """Poll the `HAS_PATH` broadcast until `result=1` or timeout."""
        deadline = time.time() + timeout
        while time.time() < deadline:
            self.clear_logcat()
            self.broadcast("HAS_PATH", to=dest_hex)
            time.sleep(1.5)
            for line in self._read_logcat_lines():
                m = re.search(
                    rf"has_path\s+to={re.escape(dest_hex)}\s+result=(\d)",
                    line,
                )
                if m:
                    if m.group(1) == "1":
                        return True
                    # result=0 — keep polling, path may resolve via announce
                    break
            time.sleep(2)
        return False

    # ---- sending -------------------------------------------------------

    def send_text(
        self,
        dest_hex: str,
        text: str,
        method: str = "DIRECT",
        try_propagation_on_fail: bool = False,
    ) -> None:
        action = {
            "DIRECT": "SEND_DIRECT",
            "OPPORTUNISTIC": "SEND_OPP",
            "PROPAGATED": "SEND_PROP",
        }[method]
        extras = {"to": dest_hex, "text": text}
        if try_propagation_on_fail:
            extras["try_prop"] = "true"
        self.broadcast(action, **extras)

    def send_image(
        self,
        dest_hex: str,
        text: str,
        image_bytes: bytes,
        image_format: str = "png",
    ) -> None:
        """Stage the payload to a tmp file on the device, then broadcast
        SEND_IMAGE. Relies on TestReceiver's image-send action — see
        TestReceiver.kt; if the action is missing, this raises with a
        clear instruction to add it."""
        self._assert_test_action_supported("SEND_IMAGE")
        path = self._stage_payload(image_bytes, ext=image_format)
        self.broadcast(
            "SEND_IMAGE",
            to=dest_hex,
            text=text,
            path=path,
            fmt=image_format,
        )

    def send_file(
        self,
        dest_hex: str,
        text: str,
        filename: str,
        data: bytes,
    ) -> None:
        self._assert_test_action_supported("SEND_FILE")
        path = self._stage_payload(data, name=filename)
        self.broadcast(
            "SEND_FILE",
            to=dest_hex,
            text=text,
            path=path,
            name=filename,
        )

    def send_audio(
        self,
        dest_hex: str,
        text: str,
        audio_bytes: bytes,
        codec_tag: int = 0,
    ) -> None:
        self._assert_test_action_supported("SEND_AUDIO")
        path = self._stage_payload(audio_bytes, ext="opus")
        self.broadcast(
            "SEND_AUDIO",
            to=dest_hex,
            text=text,
            path=path,
            codec=str(codec_tag),
        )

    def send_location(self, dest_hex: str, json_payload: str) -> None:
        """`SEND_LOCATION` already exists in TestReceiver (added in this
        session). Sends `FIELD_TELEMETRY` via `rnsTelemetry.sendLocation`."""
        self.broadcast("SEND_LOCATION", to=dest_hex, json=json_payload)

    # ---- receiving -----------------------------------------------------

    def wait_for_message(
        self,
        from_hex: Optional[str] = None,
        content_predicate=None,
        timeout: float = 30.0,
        poll: float = 0.5,
    ) -> ColumbaRxMsg:
        """Block until a matching `rx_msg source=stream` line appears in
        logcat. Optional `from_hex` filters by sender; `content_predicate`
        is `callable(ColumbaRxMsg) -> bool`."""
        deadline = time.time() + timeout
        from_lc = from_hex.lower() if from_hex else None
        while time.time() < deadline:
            for line in self._read_logcat_lines():
                msg = ColumbaRxMsg.parse(line)
                if msg is None:
                    continue
                if from_lc and msg.source_hex != from_lc:
                    continue
                if content_predicate and not content_predicate(msg):
                    continue
                return msg
            time.sleep(poll)
        raise AssertionError(
            f"Columba did not log an rx_msg within {timeout}s "
            f"(from_hex={from_hex})"
        )

    def wait_for_location(
        self,
        from_hex: Optional[str] = None,
        timeout: float = 30.0,
        poll: float = 0.5,
    ) -> dict:
        """Block until `rx_location source=stream json={…}` appears.

        The `json=` value can be either:
          - Raw JSON (Columba's own SEND_LOCATION emits a string that
            `event_bridge.py._jsonable` passes through as a string), or
          - Hex-encoded bytes (when a peer sent `FIELD_TELEMETRY` as
            bytes, `event_bridge.py._jsonable` hex-encodes them, and
            Columba's `routeFieldSideChannels` then `.toString()`s the
            JSON value, yielding a hex string the harness has to decode).

        Both forms are honored — JSON parse failure on the first read
        falls back to hex-decode-then-JSON-parse."""
        import json
        deadline = time.time() + timeout
        from_lc = from_hex.lower() if from_hex else None
        while time.time() < deadline:
            for line in self._read_logcat_lines():
                m = re.search(
                    r"rx_location\s+source=stream\s+(?:from=([0-9a-f]+)\s+)?"
                    r"json=(\S+)",
                    line,
                )
                if not m:
                    continue
                src = m.group(1)
                if from_lc and src and src.lower() != from_lc:
                    continue
                raw = _unescape(m.group(2))
                # 1) try raw JSON
                try:
                    return json.loads(raw)
                except Exception:  # noqa: BLE001
                    pass
                # 2) try hex-encoded bytes -> JSON
                try:
                    return json.loads(bytes.fromhex(raw).decode("utf-8"))
                except Exception:  # noqa: BLE001
                    continue
            time.sleep(poll)
        raise AssertionError(
            f"Columba did not log an rx_location within {timeout}s"
        )

    # ---- TestReceiver wrapping -----------------------------------------

    def broadcast(self, action: str, **extras: str) -> None:
        """Fire-and-forget `am broadcast`. TestReceiver's reply is in
        logcat under tag `COLUMBA_TEST` — callers that need the reply
        use `wait_for_*` to poll for it.

        adb tokenizes its shell payload via the device shell, so any
        extras value containing whitespace, braces, quotes, etc. (e.g.
        JSON payloads) must be wrapped in single quotes — without that,
        `--es json {"lat":42}` gets seen by `am` as just `{` and the rest
        is dropped. We single-quote-escape every extras value (single
        quotes inside are encoded `'\''`)."""
        cmd = (
            "am broadcast"
            f" -a network.columba.test.{action}"
            f" -n {RECEIVER}"
        )
        for k, v in extras.items():
            cmd += f" --es {k} {_sh_quote(str(v))}"
        self._adb("shell", cmd, check_output=False)

    def clear_logcat(self) -> None:
        self._stop_logcat()
        self._adb("logcat", "-c", check_output=False)

    # ---- internals -----------------------------------------------------

    def _read_logcat_lines(self, _unused_since_seconds: int = 5) -> list[str]:
        """One-shot logcat snapshot, filtered to the COLUMBA_TEST tag.
        Callers `clear_logcat()` before broadcasting so this returns
        only post-broadcast lines. No `-t` cap on the dump — at the
        COLUMBA_TEST tag the noise floor is just our own emissions, so
        we capture everything since the clear and let the regex
        match. Capping with `-t` was dropping replies when other
        components emitted to logcat between clear and dump."""
        # `logcat -d -s COLUMBA_TEST:*` is the canonical filter form;
        # ditches the post-dump grep and lets logcat do tag-side filtering.
        result = subprocess.run(
            ["adb", "-s", self.serial, "logcat", "-d", "-s", "COLUMBA_TEST:*"],
            capture_output=True,
            text=True,
            check=False,
            timeout=15,
        )
        if result.returncode != 0:
            return []
        return [
            ln for ln in result.stdout.splitlines()
            if "COLUMBA_TEST" in ln
        ]

    def _stop_logcat(self) -> None:
        if self._logcat_proc is not None:
            try:
                self._logcat_proc.terminate()
                self._logcat_proc.wait(timeout=2)
            except Exception:  # noqa: BLE001
                pass
            self._logcat_proc = None

    def _stage_payload(
        self,
        data: bytes,
        ext: Optional[str] = None,
        name: Optional[str] = None,
    ) -> str:
        """Push payload bytes to a tmp path the app can read.

        /sdcard/Download is inaccessible to the app under scoped storage
        (the file is created with shell-UID + media_rw group, and the
        app sees `Permission denied` on read). The reliable path under
        scoped storage is /data/local/tmp with world-read permissions —
        the emulator already grants `adb root`, so we can chmod 644.
        Tests on CI runners without `adb root` need a different staging
        strategy (e.g. base64-encoded extras with size cap)."""
        local_tmp = "/tmp/columba-interop-stage.bin"
        with open(local_tmp, "wb") as f:
            f.write(data)
        device_name = name or f"interop-{int(time.time() * 1000)}.{ext or 'bin'}"
        device_path = f"/data/local/tmp/{device_name}"
        self._adb("push", local_tmp, device_path, check_output=False)
        # World-read so the app's UID can open it. /data/local/tmp is the
        # canonical "any UID can read" tree on Android — the adb shell
        # writes there with 0644 by default but the app's process may
        # not have +x on the directory, so an explicit chmod is the
        # safer bet.
        self._adb("shell", "chmod", "644", device_path, check_output=False)
        return device_path

    def _assert_test_action_supported(self, action: str) -> None:
        """Soft preflight: broadcast the action with no extras and check
        for `rx_broadcast_unknown action=…` in logcat. The check is
        time-boxed; we accept false positives (the receiver replies
        before we read) over false negatives."""
        self.clear_logcat()
        self.broadcast(action)
        time.sleep(1.5)
        for line in self._read_logcat_lines():
            if f"rx_broadcast_unknown action=network.columba.test.{action}" in line:
                raise NotImplementedError(
                    f"TestReceiver doesn't handle {action}. Add a branch in "
                    "app/src/debug/java/network/columba/app/test/TestReceiver.kt "
                    "matching the action and routing to a TestController handler."
                )

    def _adb(self, *args, check_output: bool = True, timeout: int = 30):
        cmd = ["adb", "-s", self.serial] + list(args)
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            check=False,
            timeout=timeout,
        )
        if check_output and result.returncode != 0:
            raise RuntimeError(
                f"adb command failed ({' '.join(cmd)}): "
                f"{result.stderr.strip() or result.stdout.strip()}"
            )
        return result
