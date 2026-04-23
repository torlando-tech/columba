# Where RNS Patches Live (Historical Note)

This directory previously held runtime-overlay copies of patched RNS files
(`RNS/Destination.py` and `RNS/__init__.py`) that Chaquopy copied over the
pip-installed Reticulum module at app startup. That mechanism has been
removed — it silently went stale across upstream bumps and couldn't be
tested against upstream RNS.

## Current home

All RNS fork patches now live on the `fix/socket-leak-1.1.9` branch of
[`torlando-tech/Reticulum`](https://github.com/torlando-tech/Reticulum/tree/fix/socket-leak-1.1.9),
which `python/requirements.txt` pins via `pip install git+...`.

Patches currently carried on that branch:

1. **TCPInterface / BackboneInterface: close socket on connection failure**
   — prevents ~780 leaked file descriptors per hour during reconnect storms.
2. **Link.py: catch exceptions in `__update_phy_stats()`** — enables the
   shared-instance-across-apps Android scenario where the RPC client's auth
   key doesn't match the RNS instance's.
3. **Destination.py + __init__.py: use context managers for ratchet-file
   and log-file I/O** — fixes ResourceWarnings visible in Chaquopy and
   makes the file operations exception-safe.

## Where to send them upstream

Patches 1 and 3 are universal bug fixes that should go to
`markqvist/Reticulum`. Patch 2 is a workaround for a Columba-specific
shared-instance scenario and should be discussed with upstream before
being proposed.

## Adding a new RNS fix

Commit to the fork branch (`torlando-tech/Reticulum @ fix/socket-leak-*`),
then bump the pinned ref in `python/requirements.txt`. Do **not**
reintroduce the runtime-overlay mechanism; silent drift across upstream
versions is the problem we just finished paying down.
