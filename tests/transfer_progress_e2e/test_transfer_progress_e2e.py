from __future__ import annotations

import hashlib
import json
import os
import re
import socket
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

import pytest

from ui_driver import AdbUiDriver, UiSnapshot


PKG = "network.columba.app.debug"
ACTIVITY = f"{PKG}/network.columba.app.MainActivity"
RECEIVER = f"{PKG}/network.columba.app.test.TestReceiver"
FILE_NAME = "columba-progress-e2e.bin"
FILE_SIZE = 1024 * 1024


def free_port() -> int:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", 0))
        return int(sock.getsockname()[1])


def wait_file(path: Path, timeout: float) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if path.is_file() and path.stat().st_size:
            return
        time.sleep(0.2)
    raise TimeoutError(f"Timed out waiting for {path}")


def logcat(driver: AdbUiDriver) -> str:
    return driver.adb("logcat", "-d", "-v", "brief").stdout


def broadcast(driver: AdbUiDriver, action: str, **extras: str) -> None:
    args = ["shell", "am", "broadcast", "-n", RECEIVER, "-a", f"network.columba.test.{action}"]
    for key, value in extras.items():
        args.extend(("--es", key, value))
    driver.adb(*args)


def wait_for_log_reply(
    driver: AdbUiDriver,
    action: str,
    pattern: str,
    *,
    timeout: float = 120,
    **extras: str,
) -> re.Match[str]:
    deadline = time.monotonic() + timeout
    compiled = re.compile(pattern)
    while time.monotonic() < deadline:
        broadcast(driver, action, **extras)
        time.sleep(1.5)
        match = compiled.search(logcat(driver))
        if match is not None:
            return match
        time.sleep(0.5)
    raise TimeoutError(f"No {action} reply matching {pattern!r}")


def broadcast_and_wait(
    driver: AdbUiDriver,
    action: str,
    pattern: str,
    *,
    timeout: float = 30,
    **extras: str,
) -> re.Match[str]:
    broadcast(driver, action, **extras)
    deadline = time.monotonic() + timeout
    compiled = re.compile(pattern)
    while time.monotonic() < deadline:
        match = compiled.search(logcat(driver))
        if match is not None:
            return match
        time.sleep(0.5)
    raise TimeoutError(f"No {action} completion matching {pattern!r}")


def dismiss_optional(driver: AdbUiDriver, text: str, *, timeout: float = 12) -> None:
    try:
        driver.click_text(text, timeout=timeout)
    except TimeoutError:
        pass


def complete_onboarding(driver: AdbUiDriver, timeout: float = 90) -> None:
    """Keep advancing onboarding until the production chat list is visible."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            snapshot = driver.snapshot()
        except (subprocess.CalledProcessError, ET.ParseError):
            time.sleep(0.5)
            continue
        try:
            snapshot.require_text("Chats")
            return
        except LookupError:
            pass
        for label in ("Not Now", "Skip"):
            try:
                driver.tap(snapshot.require_text(label))
                break
            except LookupError:
                continue
        time.sleep(0.5)
    driver.screenshot("onboarding-timeout.png")
    raise TimeoutError("Onboarding did not reach the Chats screen")


def stop_process(process: subprocess.Popen[str] | None) -> None:
    if process is None or process.poll() is not None:
        return
    process.terminate()
    try:
        process.wait(timeout=10)
    except subprocess.TimeoutExpired:
        process.kill()
        process.wait(timeout=5)


def start_host_peer(root: Path, artifact_dir: Path):
    receiver_port = free_port()
    proxy_port = free_port()
    while proxy_port == receiver_port:
        proxy_port = free_port()
    destination_file = root / "destination.txt"
    result_file = root / "received.json"
    receiver_log = (artifact_dir / "receiver.log").open("w", encoding="utf-8")
    proxy_log = (artifact_dir / "proxy.log").open("w", encoding="utf-8")
    receiver: subprocess.Popen[str] | None = None
    proxy: subprocess.Popen[str] | None = None
    try:
        receiver = subprocess.Popen(
            [
                sys.executable,
                "tests/transfer_progress_e2e/host_receiver.py",
                "--root",
                str(root / "receiver"),
                "--port",
                str(receiver_port),
                "--destination-file",
                str(destination_file),
                "--result-file",
                str(result_file),
            ],
            stdout=receiver_log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        proxy = subprocess.Popen(
            [
                sys.executable,
                "tests/transfer_progress_e2e/throttled_proxy.py",
                "--listen-port",
                str(proxy_port),
                "--upstream-port",
                str(receiver_port),
                "--bytes-per-second",
                "131072",
            ],
            stdout=proxy_log,
            stderr=subprocess.STDOUT,
            text=True,
        )
        wait_file(destination_file, 20)
        time.sleep(1)
        if receiver.poll() is not None or proxy.poll() is not None:
            raise RuntimeError("Host LXMF receiver or throttled proxy exited during startup")
    except Exception:
        stop_process(proxy)
        stop_process(receiver)
        receiver_log.close()
        proxy_log.close()
        raise
    assert receiver is not None and proxy is not None
    return receiver, proxy, receiver_log, proxy_log, destination_file, result_file, proxy_port


def enter_field(driver: AdbUiDriver, placeholder: str, value: str) -> None:
    driver.tap(driver.wait_text(placeholder))
    driver.replace_focused_text(value)
    driver.back()


def click_description_until_text(
    driver: AdbUiDriver,
    description: str,
    expected_text: str,
    *,
    timeout: float = 30,
) -> None:
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        snapshot: UiSnapshot | None = None
        try:
            snapshot = driver.snapshot()
            snapshot.require_text(expected_text)
            return
        except (LookupError, subprocess.CalledProcessError, ET.ParseError):
            pass
        if snapshot is not None:
            try:
                driver.tap(snapshot.require_description(description))
            except LookupError:
                pass
        time.sleep(0.5)
    raise TimeoutError(f"Tapping {description!r} never revealed {expected_text!r}")


@pytest.mark.timeout(300)
def test_real_resource_progress_reaches_outgoing_bubble(tmp_path: Path) -> None:
    apk = Path(os.environ["COLUMBA_E2E_APK"]).resolve()
    serial = os.environ.get("COLUMBA_EMULATOR_SERIAL", "emulator-5554")
    if (
        not serial.startswith("emulator-")
        and os.environ.get("COLUMBA_E2E_ALLOW_PHYSICAL") != "1"
    ):
        pytest.fail("Refusing to clear app data on a non-emulator Android target")
    artifact_dir = Path(os.environ.get("COLUMBA_E2E_ARTIFACT_DIR", "artifacts/transfer-progress-e2e"))
    artifact_dir.mkdir(parents=True, exist_ok=True)
    driver = AdbUiDriver(serial, artifact_dir)
    processes = start_host_peer(tmp_path, artifact_dir)
    receiver, proxy, receiver_log, proxy_log, destination_file, result_file, proxy_port = processes
    destination = destination_file.read_text(encoding="ascii").strip()

    try:
        driver.adb("install", "-r", str(apk), timeout=180)
        driver.adb("shell", "pm", "clear", PKG)
        driver.adb("logcat", "-c")
        driver.adb("shell", "am", "start", "-n", ACTIVITY)
        complete_onboarding(driver)

        tcp_host = os.environ.get("COLUMBA_E2E_HOST", "10.0.2.2")
        broadcast_and_wait(
            driver,
            "DISABLE_ALL_INTERFACES",
            r"interfaces_disabled\s+count=\d+\s+applied=(?:true|false)",
            timeout=45,
        )
        broadcast_and_wait(
            driver,
            "ADD_TCP_CLIENT",
            r"interface_added\s+name=transfer_progress_e2e\s+.*applied=(?:true|false)",
            timeout=45,
            name="transfer_progress_e2e",
            host=tcp_host,
            port=str(proxy_port),
        )
        time.sleep(1)
        driver.adb("shell", "am", "force-stop", PKG)
        driver.adb("shell", "am", "start", "-n", ACTIVITY)
        dismiss_optional(driver, "Not Now")
        driver.wait_text("Chats", timeout=60)

        wait_for_log_reply(driver, "GET_DEST", r"\bdest=([0-9a-f]{32})", timeout=120)
        wait_for_log_reply(
            driver,
            "HAS_PATH",
            rf"has_path\s+to={destination}\s+result=1",
            timeout=90,
            to=destination,
        )

        driver.click_text("Contacts")
        time.sleep(1)
        driver.wait_text("Contacts", timeout=15)
        click_description_until_text(driver, "Add contact", "Manual Entry")
        driver.click_text("Manual Entry")
        enter_field(driver, "Identity or Address", destination)
        enter_field(driver, "Nickname (optional)", "CI_Receiver")
        driver.click_text("Add")
        driver.click_text("CI_Receiver", timeout=45)
        driver.click_text("Start Chat")
        driver.wait_description("Attach", timeout=30)

        payload = hashlib.shake_256(b"columba-transfer-progress-e2e").digest(FILE_SIZE)
        payload_path = tmp_path / FILE_NAME
        payload_path.write_bytes(payload)
        expected_sha = hashlib.sha256(payload).hexdigest()
        driver.adb("push", str(payload_path), f"/sdcard/Download/{FILE_NAME}", timeout=60)
        driver.adb(
            "shell",
            "am",
            "broadcast",
            "-a",
            "android.intent.action.MEDIA_SCANNER_SCAN_FILE",
            "-d",
            f"file:///sdcard/Download/{FILE_NAME}",
        )
        time.sleep(1)

        driver.click_description("Attach")
        time.sleep(0.75)
        click_description_until_text(driver, "File", FILE_NAME, timeout=30)
        driver.click_text(FILE_NAME, timeout=30)
        driver.wait_text(FILE_NAME, timeout=30)
        driver.click_description("Send message")

        def live_progress(snapshot: UiSnapshot):
            try:
                snapshot.require_text(FILE_NAME)
            except LookupError:
                return None
            percentage = snapshot.semantic_percentage("Sending directly")
            return (snapshot, percentage) if percentage is not None else None

        _, percentage = driver.wait_for(
            live_progress,
            timeout=45,
            interval=0.25,
            description="genuine outgoing Resource progress",
        )
        screenshot = driver.screenshot("outgoing-resource-progress.png")
        assert screenshot.stat().st_size > 10_000
        assert 0 < percentage < 100

        wait_file(result_file, 90)
        received = json.loads(result_file.read_text(encoding="utf-8"))
        assert received["filename"] == FILE_NAME
        assert received["size"] == FILE_SIZE
        assert received["sha256"] == expected_sha
        assert re.fullmatch(r"[0-9a-f]{64}", received["message_hash"])
    finally:
        try:
            driver.screenshot("final-screen.png")
            (artifact_dir / "logcat.log").write_text(logcat(driver), encoding="utf-8")
            driver.adb("shell", "rm", "-f", f"/sdcard/Download/{FILE_NAME}")
            driver.adb("shell", "rm", "-f", "/sdcard/columba-e2e-window.xml")
        except (OSError, subprocess.SubprocessError):
            pass
        for process in (proxy, receiver):
            stop_process(process)
        receiver_log.close()
        proxy_log.close()
