#!/usr/bin/env python3
"""Deterministic host-side LXMF recipient for the Android progress E2E test."""

from __future__ import annotations

import argparse
import hashlib
import json
import signal
import threading
import time
from pathlib import Path
from typing import Any, cast

import LXMF
import RNS


class Receiver:
    def __init__(self, root: Path, port: int, result_path: Path):
        self.root = root
        self.result_path = result_path
        self.stop = threading.Event()
        self._write_config(port)
        self.reticulum = RNS.Reticulum(str(root / "reticulum"))
        self.router = LXMF.LXMRouter(
            storagepath=str(root / "lxmf"),
            delivery_limit=8 * 1024,
        )
        self.identity = RNS.Identity()
        self.destination = cast(
            Any,
            self.router.register_delivery_identity(
                self.identity,
                display_name="Columba CI Receiver",
                stamp_cost=None,
            ),
        )
        if self.destination is None:
            raise RuntimeError("LXMF delivery destination registration failed")
        self.router.register_delivery_callback(self._received)

    def _write_config(self, port: int) -> None:
        config_dir = self.root / "reticulum"
        config_dir.mkdir(parents=True, exist_ok=True)
        (config_dir / "config").write_text(
            """[reticulum]
  enable_transport = Yes
  share_instance = No

[logging]
  loglevel = 6

[interfaces]
  [[Columba E2E TCP Server]]
    type = TCPServerInterface
    enabled = Yes
    listen_ip = 127.0.0.1
    listen_port = {port}
""".format(port=port),
            encoding="utf-8",
        )

    def _received(self, message) -> None:
        attachments = message.fields.get(LXMF.FIELD_FILE_ATTACHMENTS, [])
        first = attachments[0] if attachments else None
        if not first or len(first) != 2:
            result = {"error": "missing_file_attachment", "message_hash": message.hash.hex()}
        else:
            name, data = first
            data = bytes(data)
            if isinstance(name, bytes):
                name = name.decode("utf-8", errors="replace")
            result = {
                "filename": str(name),
                "size": len(data),
                "sha256": hashlib.sha256(data).hexdigest(),
                "message_hash": message.hash.hex(),
            }
        self.result_path.parent.mkdir(parents=True, exist_ok=True)
        self.result_path.write_text(json.dumps(result, sort_keys=True), encoding="utf-8")
        print(f"RECEIVED {json.dumps(result, sort_keys=True)}", flush=True)

    def run(self, destination_path: Path) -> None:
        destination_path.parent.mkdir(parents=True, exist_ok=True)
        destination_path.write_text(self.destination.hash.hex(), encoding="ascii")
        print(f"DESTINATION {self.destination.hash.hex()}", flush=True)
        next_announce = 0.0
        while not self.stop.wait(0.2):
            now = time.monotonic()
            if now >= next_announce:
                self.router.announce(self.destination.hash)
                print("ANNOUNCED", flush=True)
                next_announce = now + 2.0


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--port", type=int, default=4243)
    parser.add_argument("--destination-file", type=Path, required=True)
    parser.add_argument("--result-file", type=Path, required=True)
    args = parser.parse_args()

    receiver = Receiver(args.root, args.port, args.result_file)
    signal.signal(signal.SIGTERM, lambda *_: receiver.stop.set())
    signal.signal(signal.SIGINT, lambda *_: receiver.stop.set())
    receiver.run(args.destination_file)


if __name__ == "__main__":
    main()
