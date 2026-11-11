#!/usr/bin/env python3
"""Host-side Python RNS+LXST call peer for the store-art call capture.

Runs a real Reticulum TCPServerInterface + LXMF router + LXST Telephone
on this host; the emulator connects as a TCP client (host reachable as
10.0.2.2) and dials this peer's identity as "Maya Okafor". The Telephone
auto-answers, so the emulator's in-call UI reaches Active and can be
screenshotted. Patterned on tests/tcp_lxmf_e2e/peer.py.

The announced destination hash + public key are printed on PEER_READY;
capture.sh feeds them to SEED_STORE_ART (maya_hash / maya_pub extras) so
the seeded Maya conversation dials this actually-reachable node instead
of the deterministic fake hash.

Stdout protocol (line-buffered; capture.sh polls the captured log):

  PEER_READY dest=<hex> pub=<hex> port=<n>   peer is listening
  CALL_INCOMING from=<hex>                   caller identified
  CALL_ESTABLISHED                           call answered
  CALL_ENDED                                 link closed

Headless host: LXST's LineSink/LineSource need a PulseAudio server that
does not exist here, so the module-level names Telephony.py bound are
patched to null devices (RX frames are counted, TX stays silent). The
call state machine and wire protocol are untouched by the patch.
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

import RNS
import LXMF
from LXST.Sinks import Sink
from LXST.Sources import Source
from LXST.Primitives import Telephony as TelephonyModule
from LXST.Primitives.Telephony import Telephone

ANNOUNCE_INTERVAL_S = 5


class NullSink(Sink):
    """Frame sink with no hardware: satisfies the LineSink surface
    Telephony.py uses (buffer attrs, start/stop, wait_for_frames)."""

    def __init__(self, preferred_device=None, autodigest=True, low_latency=False):
        self.should_run = False
        self.released = False
        self.buffer_max_height = 6
        self.autostart_min = 1
        self.streaming = False
        self._hw_frame_time = 0.06
        self.frame_deque = None
        self.backend = None
        self.frames = 0

    def handle_frame(self, frame, source=None):
        self.frames += 1

    def can_receive(self, from_source=None):
        return True

    def start(self):
        self.should_run = True

    def stop(self):
        self.should_run = False

    def is_running(self):
        return self.should_run

    def wait_for_frames(self, timeout=None):
        pass

    def enable_low_latency(self, enabled=True):
        pass

    def release(self):
        self.released = True
        self.should_run = False

    def __str__(self):
        return "NullSink()"


class NullSource(Source):
    """Silent mic: never produces frames, so TX carries no audio."""

    def __init__(self, preferred_device=None, target_frame_ms=80, codec=None,
                 sink=None, filters=None, gain=0.0, ease_in=0.0, skip=0.0):
        self.should_run = False
        self.released = False
        self.codec = codec
        self.sink = sink
        self.filters = filters
        self.frame_deque = None
        self.backend = None

    def start(self):
        self.should_run = True

    def stop(self):
        self.should_run = False

    def is_running(self):
        return self.should_run

    def mute(self, muted=True):
        pass

    def release(self):
        self.released = True
        self.should_run = False

    def __str__(self):
        return "NullSource()"


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=4242)
    ap.add_argument("--statedir", required=True)
    ap.add_argument("--name", default="Maya Okafor")
    args = ap.parse_args()

    TelephonyModule.LineSink = NullSink
    TelephonyModule.LineSource = NullSource

    statedir = Path(args.statedir)
    statedir.mkdir(parents=True, exist_ok=True)
    (statedir / "config").write_text(
        "[reticulum]\n"
        "share_instance = No\n"
        "instance_name = store_art_call_peer\n"
        "telemetry_enabled = No\n"
        "\n"
        "[interfaces]\n"
        "    [[tcp_server]]\n"
        "    type = TCPServerInterface\n"
        "    enabled = yes\n"
        f"    port = {args.port}\n"
        "    listen_ip = 0.0.0.0\n",
        encoding="utf-8",
    )

    RNS.Reticulum(configdir=str(statedir), loglevel=RNS.LOG_WARNING)

    identpath = statedir / "peer.identity"
    if identpath.exists():
        identity = RNS.Identity.from_file(str(identpath))
    else:
        identity = RNS.Identity()
        identity.to_file(str(identpath))

    router = LXMF.LXMRouter(identity=identity, storagepath=str(statedir))
    peer_dest = router.register_delivery_identity(identity, display_name=args.name)
    router.register_delivery_callback(lambda msg: None)

    tel = Telephone(identity, auto_answer=1)
    tel.set_ringing_callback(
        lambda ident: print(f"CALL_INCOMING from={ident.hash.hex()}", flush=True))
    tel.set_established_callback(
        lambda ident: print("CALL_ESTABLISHED", flush=True))
    tel.set_ended_callback(lambda ident: print("CALL_ENDED", flush=True))

    def wait_interface(timeout: float = 30) -> bool:
        deadline = time.time() + timeout
        while time.time() < deadline:
            if RNS.Transport.interfaces:
                return True
            time.sleep(0.2)
        return False

    if not wait_interface():
        print("PEER_ERR interface did not come up", flush=True)
        return 2

    print(
        f"PEER_READY dest={peer_dest.hash.hex()} "
        f"pub={identity.get_public_key().hex()} port={args.port}",
        flush=True,
    )

    last_announce = 0.0
    while True:
        if time.time() - last_announce >= ANNOUNCE_INTERVAL_S:
            peer_dest.announce()
            tel.announce()
            last_announce = time.time()
        time.sleep(0.5)


if __name__ == "__main__":
    sys.exit(main())
