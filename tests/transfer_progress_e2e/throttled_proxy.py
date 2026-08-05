#!/usr/bin/env python3
"""Small bidirectional TCP relay with deterministic per-direction bandwidth."""

from __future__ import annotations

import argparse
import socket
import threading
import time


def relay(source: socket.socket, destination: socket.socket, bytes_per_second: int) -> None:
    try:
        while True:
            data = source.recv(4096)
            if not data:
                return
            destination.sendall(data)
            time.sleep(len(data) / bytes_per_second)
    finally:
        try:
            destination.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, upstream_host: str, upstream_port: int, rate: int) -> None:
    with client, socket.create_connection((upstream_host, upstream_port), timeout=10) as upstream:
        left = threading.Thread(target=relay, args=(client, upstream, rate), daemon=True)
        right = threading.Thread(target=relay, args=(upstream, client, rate), daemon=True)
        left.start()
        right.start()
        left.join()
        right.join()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="127.0.0.1")
    parser.add_argument("--listen-port", type=int, default=4242)
    parser.add_argument("--upstream-host", default="127.0.0.1")
    parser.add_argument("--upstream-port", type=int, default=4243)
    parser.add_argument("--bytes-per-second", type=int, default=131_072)
    args = parser.parse_args()

    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as server:
        server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server.bind((args.listen_host, args.listen_port))
        server.listen()
        print(
            f"PROXY_READY host={args.listen_host} port={args.listen_port} "
            f"upstream={args.upstream_host}:{args.upstream_port} "
            f"rate={args.bytes_per_second}",
            flush=True,
        )
        while True:
            client, address = server.accept()
            print(f"PROXY_ACCEPTED client={address[0]}:{address[1]}", flush=True)
            threading.Thread(
                target=handle,
                args=(client, args.upstream_host, args.upstream_port, args.bytes_per_second),
                daemon=True,
            ).start()


if __name__ == "__main__":
    main()
