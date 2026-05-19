"""MeshChatX headless peer.

Drives a MeshChatX instance running in `--headless --no-https` mode via
its HTTP API (default `127.0.0.1:8000`). Used to exercise the
reply / reaction interop paths that Sideband does not implement,
since both Columba and MeshChatX share the same per-event wire
format for those features:

  - reply:   `fields[0x30] = bytes(reply_to_hash)`,
             `fields[0x31] = bytes(reply_quoted_content)` (UTF-8)
  - reaction: `fields[0x10] = {reaction_to, emoji, sender}`

See `meshchat.py:16697-16699` for reply, `meshchat.py:16804-16808`
for reaction.

Why subprocess + HTTP rather than in-process import:
  - MeshChatX boots an aiohttp web server inside its own asyncio loop;
    importing it into pytest's thread would deadlock the loop scheduler.
  - The HTTP API is the documented surface — drift between API and
    test rig stays visible.

Set `MESHCHATX_SRC` env var to the MeshChatX source directory to
enable. Tests skip-by-default when the env var is missing so CI
without the runtime stays green.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request
import json
from dataclasses import dataclass
from typing import Any, Optional


@dataclass
class MeshChatXMessage:
    """One row from the conversation GET endpoint, filtered to the
    fields the harness actually asserts against. Other fields are
    ignored to keep the contract narrow."""

    hash_hex: str
    source_hex: str
    destination_hex: str
    content: str
    fields: dict  # parsed `fields_json` from MeshChatX's DB row


class MeshChatXPeer:
    """Headless MeshChatX subprocess wrapper.

    Construct, call `start()`, then `identity_hex` exposes the local
    LXMF address, and the send/receive helpers are usable.

    Args:
        meshchatx_src: path to the MeshChatX source directory (the one
            containing `meshchatx/meshchat.py`).
        reticulum_config_dir: shared RNS config directory so this peer
            and Columba run over the same transport.
        port: HTTP server port. Defaults to 8765 (off MeshChatX's
            usual 8000 so a developer-running instance doesn't collide).
        storage_dir: optional storage dir for MeshChatX's SQLite DB.
            A fresh tempdir per-test if omitted.
    """

    DEFAULT_PORT = 8765

    def __init__(
        self,
        meshchatx_src: str,
        reticulum_config_dir: str,
        port: int = DEFAULT_PORT,
        storage_dir: Optional[str] = None,
    ):
        self.meshchatx_src = meshchatx_src
        self.reticulum_config_dir = reticulum_config_dir
        self.port = port
        self.storage_dir = storage_dir or tempfile.mkdtemp(
            prefix="meshchatx-peer-"
        )
        self._owns_storage_dir = storage_dir is None
        self._proc: Optional[subprocess.Popen] = None
        self._identity_hex: Optional[str] = None

    @property
    def base_url(self) -> str:
        return f"http://127.0.0.1:{self.port}"

    @property
    def identity_hex(self) -> str:
        if self._identity_hex is None:
            raise RuntimeError(
                "MeshChatX peer not started or identity not yet known"
            )
        return self._identity_hex

    # ---- lifecycle -----------------------------------------------------

    def start(self, ready_timeout: float = 60.0) -> None:
        if self._proc is not None:
            raise RuntimeError("MeshChatXPeer.start() already called")

        # `--no-https` so we don't need a self-signed cert dance; this
        # is a localhost-only test rig so plain HTTP is fine.
        # `--reticulum-config-dir` shares transport with Columba.
        # `--no-crash-recovery` so test failures don't leave behind a
        # zombie recovery prompt.
        cmd = [
            sys.executable,
            "-m",
            "meshchatx.meshchat",
            "--headless",
            "--no-https",
            "--no-crash-recovery",
            "--host",
            "127.0.0.1",
            "--port",
            str(self.port),
            "--reticulum-config-dir",
            self.reticulum_config_dir,
            "--storage-dir",
            self.storage_dir,
        ]

        env = os.environ.copy()
        env["PYTHONPATH"] = (
            f"{self.meshchatx_src}{os.pathsep}{env.get('PYTHONPATH', '')}"
        )

        self._proc = subprocess.Popen(
            cmd,
            cwd=self.meshchatx_src,
            env=env,
            stdout=subprocess.PIPE,
            stderr=subprocess.STDOUT,
        )

        # Poll the status endpoint until ready or timeout. MeshChatX
        # finishes its async setup before the route handler returns
        # "ok", so a successful response means we can hit the send
        # endpoint safely.
        deadline = time.time() + ready_timeout
        last_err: Optional[Exception] = None
        while time.time() < deadline:
            if self._proc.poll() is not None:
                # Child exited before becoming ready — drain its output
                # for diagnostics rather than blocking on a stillborn
                # server.
                tail = b""
                if self._proc.stdout is not None:
                    tail = self._proc.stdout.read() or b""
                raise RuntimeError(
                    "MeshChatX subprocess exited before becoming ready. "
                    f"Last output:\n{tail.decode('utf-8', errors='replace')}"
                )
            try:
                self._http_get("/api/v1/status")
                break
            except Exception as e:  # noqa: BLE001
                last_err = e
                time.sleep(0.5)
        else:
            self.stop()
            raise TimeoutError(
                f"MeshChatX HTTP server did not become ready within "
                f"{ready_timeout}s. Last error: {last_err!r}"
            )

        # MeshChatX exposes its own LXMF address via /api/v1/config in
        # the `lxmf_address` config key.
        cfg = self._http_get("/api/v1/config")
        lxmf_address = (
            cfg.get("config", {}).get("lxmf_address")
            if isinstance(cfg, dict)
            else None
        )
        if not lxmf_address:
            raise RuntimeError(
                "MeshChatX reported no lxmf_address — peer cannot "
                "be addressed."
            )
        self._identity_hex = str(lxmf_address).lower()

    def stop(self) -> None:
        if self._proc is not None:
            self._proc.terminate()
            try:
                self._proc.wait(timeout=15.0)
            except subprocess.TimeoutExpired:
                self._proc.kill()
                self._proc.wait(timeout=5.0)
            self._proc = None
        if self._owns_storage_dir and os.path.isdir(self.storage_dir):
            shutil.rmtree(self.storage_dir, ignore_errors=True)

    # ---- sending -------------------------------------------------------

    def send_text(self, dest_hex: str, content: str) -> dict:
        """POST /api/v1/lxmf-messages/send with a plain-text body."""
        return self._http_post(
            "/api/v1/lxmf-messages/send",
            {
                "lxmf_message": {
                    "destination_hash": dest_hex,
                    "content": content,
                },
                "delivery_method": "opportunistic",
            },
        )

    def send_reply(
        self,
        dest_hex: str,
        content: str,
        reply_to_hash: str,
        reply_quoted_content: str,
    ) -> dict:
        """POST /api/v1/lxmf-messages/send with reply fields set.

        On the wire this puts `fields[0x30] = bytes.fromhex(reply_to_hash)`
        and `fields[0x31] = reply_quoted_content.encode('utf-8')` — the
        same format Columba writes after the v2 reply migration.
        """
        return self._http_post(
            "/api/v1/lxmf-messages/send",
            {
                "lxmf_message": {
                    "destination_hash": dest_hex,
                    "content": content,
                    "reply_to_hash": reply_to_hash,
                    "reply_quoted_content": reply_quoted_content,
                },
                "delivery_method": "opportunistic",
            },
        )

    def send_reaction(
        self,
        dest_hex: str,
        target_message_hash: str,
        emoji: str,
    ) -> dict:
        """POST /api/v1/lxmf-messages/reactions.

        On the wire this puts `fields[0x10] = {reaction_to, emoji, sender}`
        on an otherwise-empty message — same format Columba uses.
        """
        return self._http_post(
            "/api/v1/lxmf-messages/reactions",
            {
                "destination_hash": dest_hex,
                "target_message_hash": target_message_hash,
                "emoji": emoji,
            },
        )

    # ---- receive -------------------------------------------------------

    def wait_for_message(
        self,
        from_hex: str,
        predicate=lambda m: True,
        timeout: float = 30.0,
        poll: float = 0.25,
    ) -> MeshChatXMessage:
        """Block until the conversation endpoint returns a message
        from `from_hex` matching `predicate`. Returns the parsed
        `MeshChatXMessage`."""
        from_hex_norm = from_hex.lower()
        deadline = time.time() + timeout
        while time.time() < deadline:
            data = self._http_get(
                f"/api/v1/lxmf-messages/conversation/{from_hex_norm}"
                f"?order=desc&count=50"
            )
            for raw in data.get("lxmf_messages", []):
                msg = _parse_meshchatx_message(raw)
                if msg is None:
                    continue
                if msg.source_hex != from_hex_norm:
                    continue
                if predicate(msg):
                    return msg
            time.sleep(poll)
        raise AssertionError(
            f"MeshChatX did not surface a matching message from "
            f"{from_hex} within {timeout}s"
        )

    # ---- HTTP plumbing -------------------------------------------------

    def _http_get(self, path: str) -> Any:
        return self._http_request("GET", path, body=None)

    def _http_post(self, path: str, body: dict) -> Any:
        return self._http_request("POST", path, body=body)

    def _http_request(self, method: str, path: str, body: Optional[dict]) -> Any:
        url = self.base_url + path
        data = None
        headers = {"Accept": "application/json"}
        if body is not None:
            data = json.dumps(body).encode("utf-8")
            headers["Content-Type"] = "application/json"
        req = urllib.request.Request(url, data=data, method=method, headers=headers)
        try:
            with urllib.request.urlopen(req, timeout=10.0) as resp:
                payload = resp.read()
        except urllib.error.HTTPError as e:
            # Surface the body in the error message — the harness's
            # error paths benefit from MeshChatX's structured 400/503
            # responses far more than a bare status code.
            raise RuntimeError(
                f"MeshChatX {method} {path} → HTTP {e.code}: "
                f"{(e.read() or b'').decode('utf-8', errors='replace')}"
            ) from None
        if not payload:
            return None
        return json.loads(payload.decode("utf-8"))


def _parse_meshchatx_message(raw: dict) -> Optional[MeshChatXMessage]:
    """Parse one row from the conversation endpoint. MeshChatX's
    `convert_db_lxmf_message_to_dict` shape: `{hash, source_hash,
    destination_hash, content, fields, ...}`."""
    if not isinstance(raw, dict):
        return None
    hash_hex = raw.get("hash")
    source = raw.get("source_hash")
    destination = raw.get("destination_hash")
    if not (
        isinstance(hash_hex, str)
        and isinstance(source, str)
        and isinstance(destination, str)
    ):
        return None
    content = raw.get("content")
    if isinstance(content, (bytes, bytearray)):
        content = content.decode("utf-8", errors="replace")
    elif content is None:
        content = ""

    fields = raw.get("fields")
    if isinstance(fields, str):
        try:
            fields = json.loads(fields)
        except json.JSONDecodeError:
            fields = {}
    elif not isinstance(fields, dict):
        fields = {}

    return MeshChatXMessage(
        hash_hex=hash_hex.lower(),
        source_hex=source.lower(),
        destination_hex=destination.lower(),
        content=str(content),
        fields=fields,
    )
