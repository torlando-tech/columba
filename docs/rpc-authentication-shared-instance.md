# RPC Authentication Error in Shared Instance Mode

## Overview

When Columba connects to a shared Reticulum instance (e.g., Sideband), message delivery fails with RPC authentication errors. This document explains the root cause and provides solutions.

## Key Finding: RPC is NOT Required for Message Delivery

**Core packet routing uses LocalClientInterface socket connections directly - NOT RPC.**

The RPC subsystem is only used for:
- Statistics queries (RSSI, SNR, link quality)
- Path table queries
- Management operations (drop paths, clear queues)

However, a bug in RNS causes message delivery to crash when RPC authentication fails.

---

## LXMF Delivery Methods

| Method | Description | Max Size | RPC Required? |
|--------|-------------|----------|---------------|
| **OPPORTUNISTIC** | Single encrypted packet, no link | ~295 bytes | No |
| **DIRECT** | Link-based, reliable delivery | Unlimited | No |
| **PROPAGATED** | Store-and-forward via propagation node | Unlimited | No |
| **PAPER** | QR code/URI for manual transfer | ~1760 bytes | No |

All methods deliver through `LXMRouter.lxmf_delivery()` → delivery callback. None require RPC.

---

## The Bug: RPC Auth Error Crashes Message Delivery

### Evidence from Logcat

```
12-07 23:45:11.910 I python.stdout: [2025-12-07 23:45:11] [Error]    Traceback (most recent call last):
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Interfaces/LocalInterface.py", line 193, in process_incoming
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Transport.py", line 1889, in inbound
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Link.py", line 995, in receive
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Link.py", line 837, in __update_phy_stats
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Reticulum.py", line 1280, in get_packet_rssi
12-07 23:45:11.910 I python.stdout:   File ".../RNS/Reticulum.py", line 969, in get_rpc_client
12-07 23:45:11.911 I python.stdout: multiprocessing.context.AuthenticationError: digest sent was rejected

12-07 23:45:13.279 I sidebandservice: [Error] An error ocurred while handling RPC call from local client: digest received was wrong
```

### Exact Call Chain

```
1. Packet arrives at LocalClientInterface (connected to Sideband's shared instance)
       ↓
2. LocalInterface.process_incoming() [LocalInterface.py:193]
   - Receives raw packet bytes from shared instance socket
       ↓
3. Transport.inbound() [Transport.py:1889]
   - Routes packet to appropriate handler
   - For link-related packets, calls link.receive()
       ↓
4. Link.receive() [Link.py:995]
   - Processes the link packet (DATA, LINKIDENTIFY, REQUEST, etc.)
   - Calls __update_phy_stats() to record signal quality
       ↓
5. Link.__update_phy_stats() [Link.py:837]
   - Called with force_update=True at 17 different sites in receive()
   - Tries to get RSSI/SNR/Q from the packet
   - If connected to shared instance, makes RPC call
       ↓
6. Reticulum.get_packet_rssi() [Reticulum.py:1280]
   - Makes RPC call to shared instance to query cached RSSI value
       ↓
7. Reticulum.get_rpc_client() [Reticulum.py:969]
   - Creates RPC connection with authkey
   - AuthenticationError: Columba's authkey doesn't match Sideband's
```

### Root Cause

**Columba and Sideband have different RPC keys** because:
- Each app has its own config directory (Android sandboxing)
- RPC keys are derived from app-specific identity private keys
- Without explicit key sharing, authentication fails

### Why This Crashes Message Delivery

The `__update_phy_stats()` method does NOT have exception handling:

```python
# Link.py lines 833-850 (simplified)
def __update_phy_stats(self, packet, force_update=False):
    if self.__track_phy_stats or force_update:
        if RNS.Reticulum.get_instance().is_connected_to_shared_instance:
            # NO TRY-CATCH HERE!
            self.rssi = RNS.Reticulum.get_instance().get_packet_rssi(packet.packet_hash)
            self.snr = RNS.Reticulum.get_instance().get_packet_snr(packet.packet_hash)
            self.q = RNS.Reticulum.get_instance().get_packet_q(packet.packet_hash)
```

When `get_packet_rssi()` raises `AuthenticationError`, it propagates up through:
- `__update_phy_stats()` → `receive()` → `Transport.inbound()` → `process_incoming()`

This crashes the entire packet processing chain.

### Why `force_update=True` Is Used

Even though `__track_phy_stats` defaults to `False`, certain call sites in `Link.receive()` use `force_update=True`:

- **Line 218**: Incoming link request acceptance
- **Lines 995, 1031, 1040, 1053, 1060, 1064, 1069**: Various DATA packet types
- **Lines 1107, 1129, 1138, 1147, 1167, 1176, 1185**: RESOURCE_ADV, LINKCLOSE, etc.

This is automatic RNS behavior - **Columba does not explicitly request signal stats**.

---

## Can RPC Key Enable Interface Configuration?

**NO** - Even with valid RPC key, shared instance clients cannot configure interfaces:

```python
# From Reticulum.py _add_interface()
if not self.is_connected_to_shared_instance:
    # Process interface configuration
```

Interface configuration is blocked for connected clients. This is by design - the shared instance owns the interfaces.

---

## Solutions

### Option 1: Patch RNS in Columba's Bundled Copy

Add exception handling in `Link.__update_phy_stats()`:

```python
def __update_phy_stats(self, packet, force_update=False):
    if self.__track_phy_stats or force_update:
        try:
            if RNS.Reticulum.get_instance().is_connected_to_shared_instance:
                self.rssi = RNS.Reticulum.get_instance().get_packet_rssi(packet.packet_hash)
                self.snr = RNS.Reticulum.get_instance().get_packet_snr(packet.packet_hash)
                self.q = RNS.Reticulum.get_instance().get_packet_q(packet.packet_hash)
            else:
                # ... existing local cache lookup
        except Exception as e:
            RNS.log(f"Could not update physical layer stats: {e}", RNS.LOG_DEBUG)
            # Stats remain None, but packet processing continues
```

**Pros**: Immediate fix, no user action required
**Cons**: Diverges from upstream RNS

### Option 2: Configure RPC Key

Allow users to paste the RPC key from Sideband (Settings → Connectivity → "Share Instance Access").

**Pros**: Enables full stats functionality
**Cons**: Requires user action, key management

### Option 3: Both Approaches

Patch for resilience + allow RPC key for full functionality.

**Pros**: Best of both worlds
**Cons**: More implementation work

### Option 4: Upstream PR

Submit fix to Reticulum repository.

**Pros**: Fixes for everyone
**Cons**: Dependent on upstream acceptance timeline

---

## Sideband RPC Key Export

Location in Sideband: **Settings → Connectivity → "Share Instance Access"**

The exported config includes:
```
shared_instance_type = tcp
rpc_key = <hex_key>
```

---

---

## Why Other RNS Clients Don't Have This Problem

### NomadNet & MeshChat Approach

Both NomadNet and reticulum-meshchat use a simple initialization pattern:

```python
# NomadNet (nomadnet/NomadNetworkApp.py:95)
self.rns = RNS.Reticulum(configdir=rnsconfigdir)

# MeshChat (meshchat.py:113-115)
self.reticulum = RNS.Reticulum(reticulum_config_dir)
```

**Why they work:** These are typically run on Linux/desktop systems where:
- All apps can share the same `~/.reticulum` config directory
- The RPC key is derived from the same identity file
- RPC authentication succeeds because both sides use the same key

### Sideband's Android Solution

Sideband explicitly handles the Android sandboxing problem:

**File: `/home/tyler/repos/Sideband/sbapp/sideband/core.py:607-608`**
```python
self.identity = RNS.Identity.from_file(self.identity_path)
self.rpc_key = RNS.Identity.full_hash(self.identity.get_private_key())
```

**File: `/home/tyler/repos/Sideband/sbapp/main.py:3914-3925`** - Export feature:
```python
rpc_string = "shared_instance_type = tcp\n"
rpc_string += "rpc_key = " + RNS.hexrep(self.sideband.reticulum.rpc_key, delimit=False)
```

Sideband provides a UI to export this configuration, which other apps can paste into their Reticulum config.

### The Columba Problem

Columba is unique because:

1. **Android sandbox**: Columba has its own app directory, can't read Sideband's config
2. **Bundled Python**: Uses Chaquopy with its own RNS installation
3. **Own identity**: Columba creates its own identity file (different from Sideband's)
4. **Derived RPC key**: RNS derives RPC key from identity: `Identity.full_hash(private_key)`

When Columba connects to Sideband's shared instance:
- **Data routing works** (LocalClientInterface uses raw sockets)
- **RPC fails** (Columba's derived key != Sideband's derived key)

### RPC Key Configuration in RNS

**File: `/home/tyler/repos/Reticulum/RNS/Reticulum.py:472-478`**
```python
if option == "rpc_key":
    try:
        value = bytes.fromhex(self.config["reticulum"][option])
        self.rpc_key = value
    except Exception as e:
        RNS.log("Invalid shared instance RPC key specified, falling back to default key", RNS.LOG_ERROR)
        self.rpc_key = None
```

RNS supports explicit RPC key configuration for exactly this scenario - when apps can't share config directories.

---

## Summary

| Aspect | Status |
|--------|--------|
| Message delivery without RPC | Should work (bug prevents it) |
| Root cause | Missing exception handling in `__update_phy_stats()` |
| Why other clients work | They share config directories or explicitly configure RPC key |
| Columba's issue | Android sandbox prevents config sharing, RPC key mismatch |
| Interface configuration | Always disabled for shared instance clients |
| Recommended fix | Patch RNS + support RPC key configuration in UI |
