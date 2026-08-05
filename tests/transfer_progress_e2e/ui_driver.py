"""Host-side UIAutomator helpers for the transfer-progress E2E test."""

from __future__ import annotations

import re
import subprocess
import time
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path
from typing import Callable, TypeVar


_BOUNDS = re.compile(r"\[(\d+),(\d+)]\[(\d+),(\d+)]")
T = TypeVar("T")


@dataclass(frozen=True)
class UiNode:
    text: str
    description: str
    bounds: tuple[int, int, int, int]

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return ((left + right) // 2, (top + bottom) // 2)


@dataclass(frozen=True)
class UiSnapshot:
    nodes: tuple[UiNode, ...]

    @classmethod
    def parse(cls, xml: str) -> "UiSnapshot":
        root = ET.fromstring(xml)
        nodes: list[UiNode] = []
        for element in root.iter("node"):
            match = _BOUNDS.fullmatch(element.attrib.get("bounds", ""))
            if match is None:
                continue
            left, top, right, bottom = (int(value) for value in match.groups())
            nodes.append(
                UiNode(
                    text=element.attrib.get("text", ""),
                    description=element.attrib.get("content-desc", ""),
                    bounds=(left, top, right, bottom),
                )
            )
        return cls(tuple(nodes))

    def require_text(self, text: str, *, contains: bool = False) -> UiNode:
        for node in self.nodes:
            if (contains and text in node.text) or (not contains and node.text == text):
                return node
        raise LookupError(f"UI text not found: {text!r}")

    def require_description(self, description: str, *, contains: bool = False) -> UiNode:
        for node in self.nodes:
            value = node.description
            if (contains and description in value) or (not contains and value == description):
                return node
        raise LookupError(f"UI description not found: {description!r}")

    def semantic_percentage(self, label: str) -> int | None:
        pattern = re.compile(rf"^{re.escape(label)}, (\d{{1,3}}) percent$")
        for node in self.nodes:
            match = pattern.fullmatch(node.description)
            if match is not None:
                value = int(match.group(1))
                if 0 < value < 100:
                    return value
        return None


class AdbUiDriver:
    def __init__(self, serial: str, artifact_dir: Path):
        self.serial = serial
        self.artifact_dir = artifact_dir
        self.artifact_dir.mkdir(parents=True, exist_ok=True)
        self._dump_path = self.artifact_dir / "window.xml"

    def adb(
        self,
        *args: str,
        timeout: float = 30,
        capture_output: bool = True,
    ) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            ["adb", "-s", self.serial, *args],
            check=True,
            text=True,
            capture_output=capture_output,
            timeout=timeout,
        )

    def snapshot(self) -> UiSnapshot:
        remote = "/sdcard/columba-e2e-window.xml"
        self.adb("shell", "uiautomator", "dump", remote)
        self.adb("pull", remote, str(self._dump_path))
        return UiSnapshot.parse(self._dump_path.read_text(encoding="utf-8"))

    def wait_for(
        self,
        predicate: Callable[[UiSnapshot], T | None],
        *,
        timeout: float = 30,
        interval: float = 0.35,
        description: str,
    ) -> T:
        deadline = time.monotonic() + timeout
        last_snapshot: UiSnapshot | None = None
        while time.monotonic() < deadline:
            try:
                last_snapshot = self.snapshot()
            except (subprocess.CalledProcessError, ET.ParseError):
                time.sleep(interval)
                continue
            result = predicate(last_snapshot)
            if result is not None:
                return result
            time.sleep(interval)
        self.screenshot("timeout.png")
        visible = [] if last_snapshot is None else [
            node.text or node.description for node in last_snapshot.nodes if node.text or node.description
        ]
        raise TimeoutError(f"Timed out waiting for {description}; visible={visible[-30:]}")

    def wait_text(self, text: str, *, timeout: float = 30, contains: bool = False) -> UiNode:
        def find(snapshot: UiSnapshot) -> UiNode | None:
            try:
                return snapshot.require_text(text, contains=contains)
            except LookupError:
                return None

        return self.wait_for(find, timeout=timeout, description=f"text {text!r}")

    def wait_description(
        self,
        description: str,
        *,
        timeout: float = 30,
        contains: bool = False,
    ) -> UiNode:
        def find(snapshot: UiSnapshot) -> UiNode | None:
            try:
                return snapshot.require_description(description, contains=contains)
            except LookupError:
                return None

        return self.wait_for(find, timeout=timeout, description=f"description {description!r}")

    def tap(self, node: UiNode) -> None:
        x, y = node.center
        self.adb("shell", "input", "tap", str(x), str(y))

    def click_text(self, text: str, *, timeout: float = 30, contains: bool = False) -> None:
        self.tap(self.wait_text(text, timeout=timeout, contains=contains))

    def click_description(
        self,
        description: str,
        *,
        timeout: float = 30,
        contains: bool = False,
    ) -> None:
        self.tap(self.wait_description(description, timeout=timeout, contains=contains))

    def replace_focused_text(self, value: str) -> None:
        self.adb("shell", "input", "keycombination", "113", "29")
        self.adb("shell", "input", "text", value)

    def back(self) -> None:
        self.adb("shell", "input", "keyevent", "BACK")

    def screenshot(self, name: str) -> Path:
        destination = self.artifact_dir / name
        with destination.open("wb") as output:
            subprocess.run(
                ["adb", "-s", self.serial, "exec-out", "screencap", "-p"],
                check=True,
                stdout=output,
                timeout=30,
            )
        return destination
