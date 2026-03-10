# Parental Controls

Columba includes a parental control system that allows a parent (guardian) to manage and restrict messaging on a child's device. This document explains how the system works for both parents and technical users.

## Table of Contents

1. [Setup Guide](#setup-guide)
2. [Security Model](#security-model)
3. [Child Device Restrictions](#child-device-restrictions)
4. [Command Protocol Reference](#command-protocol-reference)
5. [Known Limitations](#known-limitations)

---

## Setup Guide

### Overview

Parental controls use a **QR code pairing** process that must happen in person. This ensures that only someone with physical access to both devices can establish the parent-child relationship.

### For Parents (Guardian Device)

1. **Open Columba** on your device
2. **Navigate to Settings → Parental Controls**
3. **Tap "I am a Parent"** to enter guardian mode
4. **Tap "Show Pairing QR Code"** to generate your pairing code
5. **Show the QR code** to the child's device camera

The QR code is valid for **5 minutes** and contains your device's identity information signed with your private key.

### For Children (Managed Device)

1. **Open Columba** on your device
2. **Navigate to Settings → Parental Controls**
3. **Tap "Scan Parent's QR Code"**
4. **Grant camera permission** if prompted
5. **Scan the QR code** displayed on the parent's device
6. **Review the confirmation dialog** showing the parent's identity
7. **Tap "Confirm Pairing"** to complete setup

After pairing:
- The parent's device will show your device as a "Paired Child"
- Your device will show "Parental Controls Active" with the parent's identity
- You can message your parent at any time (even when locked)

### Managing a Child's Device (Parent)

Once paired, the parent can:

| Action | Description |
|--------|-------------|
| **Lock** | Enable messaging restrictions on the child's device |
| **Unlock** | Disable messaging restrictions |
| **Add Allowed Contact** | Allow the child to message a specific person |
| **Remove Allowed Contact** | Revoke messaging permission for a contact |
| **Unpair** | Remove parental controls entirely |

---

## Security Model

### How Reticulum Identities Work

Columba uses the [Reticulum Network Stack](https://reticulum.network/) for all communication. Every Columba user has a unique **cryptographic identity** consisting of:

- **Ed25519 signing key pair** - Used to prove you are who you claim to be
- **X25519 encryption key pair** - Used for end-to-end encrypted communication
- **Destination hash** - A 16-byte identifier derived from your public keys

When you scan a pairing QR code, you're receiving the parent's **destination hash** and **public signing key**. This allows the child's device to:

1. **Verify commands** came from the parent (signature verification)
2. **Send messages** to the parent (using their destination hash)

### Command Authentication

All parental control commands are **cryptographically signed** using the parent's Ed25519 private key:

```
signature = Ed25519_Sign(private_key, cmd + nonce + timestamp + payload)
```

The child's device verifies commands by:

1. **Checking the sender** - Command must come from the stored guardian destination
2. **Verifying the signature** - Using the guardian's stored public key
3. **Validating the timestamp** - Command must be within 5 minutes of current time
4. **Checking the nonce** - Each command has a unique random nonce to prevent replay

### Anti-Replay Protection

To prevent attackers from recording and replaying old commands:

| Protection | Description |
|------------|-------------|
| **5-minute window** | Commands older than 5 minutes are rejected |
| **Nonce tracking** | Each processed nonce is stored; duplicates are rejected |
| **Timestamp ordering** | Commands must have timestamps newer than the last processed command |

### Why This Is Secure

- **No server required** - Commands travel directly between devices via LXMF
- **End-to-end encrypted** - All LXMF messages are encrypted
- **Identity-based auth** - Only the holder of the parent's private key can sign valid commands
- **Physical pairing** - QR codes require in-person presence to establish trust

---

## Child Device Restrictions

### When Locked

When a parent **locks** the child's device, the following restrictions apply:

#### Messaging Restrictions

| Restriction | Behavior |
|-------------|----------|
| **Incoming messages** | Only messages from allowed contacts and the guardian are shown |
| **Outgoing messages** | Child can only send messages to allowed contacts and the guardian |
| **Blocked messages** | Silently dropped (sender sees "delivered", but child never receives) |

#### UI Restrictions

| Feature | Restriction |
|---------|-------------|
| **Map tab** | Hidden from navigation bar |
| **Network discovery** | Hidden in Contacts screen |
| **Manage Interfaces** | Button disabled in Settings |
| **Manage Identities** | Button disabled in Settings |
| **Location sharing toggle** | Disabled in Settings |
| **Remove Parental Controls** | Button disabled until unlocked |

### When Unlocked

When unlocked, the child can:
- Message anyone
- Access all app features
- Remove parental controls (unpair)

The parent can re-lock at any time.

### What Children CAN Always Do

Even when locked, children can:

- **Message their guardian** - Always allowed regardless of lock state
- **Receive messages from guardian** - Always delivered
- **View existing conversations** - With allowed contacts
- **Use the app normally** - For approved contacts
- **See their lock status** - Transparency about restrictions

---

## Command Protocol Reference

### Message Format

Guardian commands are sent as LXMF messages with a special prefix in the content field:

```
__GUARDIAN_CMD__:<JSON command data>
```

Alternatively, commands can use LXMF field `0x80` (128 decimal) for the command data.

### Command Schema

```json
{
  "cmd": "<COMMAND_TYPE>",
  "nonce": "<32-character hex string>",
  "timestamp": <Unix timestamp in milliseconds>,
  "payload": { <command-specific data> },
  "signature": "<128-character hex Ed25519 signature>"
}
```

### Command Types

#### LOCK

Enables messaging restrictions on the child device.

```json
{
  "cmd": "LOCK",
  "nonce": "a1b2c3d4e5f6789012345678abcdef01",
  "timestamp": 1705234567890,
  "payload": {},
  "signature": "..."
}
```

#### UNLOCK

Disables messaging restrictions on the child device.

```json
{
  "cmd": "UNLOCK",
  "nonce": "b2c3d4e5f6789012345678abcdef01a2",
  "timestamp": 1705234567891,
  "payload": {},
  "signature": "..."
}
```

#### ALLOW_ADD

Adds contacts to the child's allowed contact list.

```json
{
  "cmd": "ALLOW_ADD",
  "nonce": "c3d4e5f6789012345678abcdef01a2b3",
  "timestamp": 1705234567892,
  "payload": {
    "contacts": [
      {"hash": "abc123def456789...", "name": "Grandma"},
      {"hash": "def456789abc123...", "name": "Uncle Bob"}
    ]
  },
  "signature": "..."
}
```

The child device will:
1. Add these hashes to the allowed contacts database
2. Create contact entries so the child can see and message them
3. Sync the updated allow list to the Python layer

#### ALLOW_REMOVE

Removes contacts from the child's allowed contact list.

```json
{
  "cmd": "ALLOW_REMOVE",
  "nonce": "d4e5f6789012345678abcdef01a2b3c4",
  "timestamp": 1705234567893,
  "payload": {
    "contacts": ["abc123def456789...", "def456789abc123..."]
  },
  "signature": "..."
}
```

#### ALLOW_SET

Replaces the entire allowed contact list atomically.

```json
{
  "cmd": "ALLOW_SET",
  "nonce": "e5f6789012345678abcdef01a2b3c4d5",
  "timestamp": 1705234567894,
  "payload": {
    "contacts": [
      {"hash": "abc123def456789...", "name": "Grandma"}
    ]
  },
  "signature": "..."
}
```

This removes all existing allowed contacts and replaces them with the provided list.

#### STATUS_REQUEST

Requests the current lock state and allow list from the child device.

```json
{
  "cmd": "STATUS_REQUEST",
  "nonce": "f6789012345678abcdef01a2b3c4d5e6",
  "timestamp": 1705234567895,
  "payload": {},
  "signature": "..."
}
```

*Note: Status response is not yet fully implemented.*

#### PAIR_ACK (Child → Parent)

Sent by the child device to acknowledge successful pairing.

```json
{
  "cmd": "PAIR_ACK",
  "nonce": "789012345678abcdef01a2b3c4d5e6f7",
  "timestamp": 1705234567896,
  "payload": {
    "display_name": "Child's Phone"
  },
  "signature": "..."
}
```

### Signature Computation

The signature covers the concatenation of:

```
cmd_bytes (UTF-8) + nonce (16 bytes) + timestamp (8 bytes big-endian) + payload_bytes (msgpack)
```

Signed using Ed25519 with the guardian's identity private key.

### QR Code Format

Pairing QR codes use the following URI format:

```
lxmf-guardian://<dest_hash>:<public_key>:<timestamp>:<signature>
```

| Field | Format | Description |
|-------|--------|-------------|
| `dest_hash` | 32 hex chars | Parent's LXMF destination hash |
| `public_key` | 128 hex chars | Parent's Ed25519 public key (64 bytes) |
| `timestamp` | decimal | Unix timestamp in milliseconds |
| `signature` | 128 hex chars | Ed25519 signature over (dest_hash + timestamp) |

---

## Known Limitations

### 1. Online Status Leak

**Issue:** When a blocked contact attempts to message a locked child, they can briefly see the child as "online" before their message is dropped.

**Technical detail:** Reticulum links don't reveal the initiator's identity at establishment time. The identity is only revealed within the LXMF message itself. By that point, a link has already been established (revealing online status).

**Mitigation:** Message content is still blocked. The attacker cannot communicate with the child; they can only detect online presence.

**Future improvement:** Tear down links immediately after detecting a blocked sender, or require identity proof before link establishment.

### 2. Factory Reset Bypass

**Issue:** A child can bypass parental controls by factory resetting their device or clearing app data.

**By design:** This is intentional. Parental controls are meant for cooperative family safety, not for preventing a determined adversary. If a child factory resets, they lose all their contacts and message history as well.

**Mitigation:** Parents should have conversations about why controls are in place. If bypass becomes an issue, consider device-level restrictions (Android Family Link, etc.).

### 3. Single Guardian

**Issue:** Only one guardian can be configured per child device.

**Reason:** Simplifies the trust model and prevents conflicts between multiple guardians.

**Workaround:** The guardian device can be shared between parents, or one parent can manage controls while the other is added to the allow list.

### 4. Signature Verification TODO

**Current state:** Command processing validates:
- Sender is the configured guardian destination
- Timestamp is within 5-minute window
- Nonce hasn't been replayed

**Not yet implemented:** Full Ed25519 signature verification via AIDL bridge to Python.

**Why it's still secure:** The sender's destination hash is cryptographically authenticated by the LXMF protocol. Only someone with the guardian's private key can send from that destination.

**Future improvement:** Add explicit signature verification for defense-in-depth.

### 5. No Remote Wipe

**Issue:** Parents cannot remotely wipe or disable a lost/stolen device.

**Reason:** Out of scope for an LXMF messenger. Use Android device management features for this.

### 6. Command Delivery Reliability

**Issue:** Commands may be delayed or lost if devices are offline.

**Mitigation:**
- Commands can be sent via propagation nodes for store-and-forward delivery
- Parent UI shows command transmission status
- Retry mechanisms exist for failed sends

---

## Database Schema

### GuardianConfigEntity

Stores the parent-child relationship on the child's device:

```kotlin
@Entity(tableName = "guardian_config")
data class GuardianConfigEntity(
    @PrimaryKey val identityHash: String,      // Child's identity
    val guardianDestinationHash: String?,       // Parent's destination
    val guardianPublicKey: ByteArray?,          // Parent's Ed25519 public key
    val guardianName: String?,                  // Display name
    val isLocked: Boolean = false,              // Current lock state
    val lockedTimestamp: Long = 0,              // When locked
    val lastCommandNonce: String?,              // Anti-replay
    val lastCommandTimestamp: Long = 0,         // Anti-replay
    val pairedTimestamp: Long = 0,              // When paired
)
```

### AllowedContactEntity

Stores the allow list on the child's device:

```kotlin
@Entity(
    tableName = "allowed_contacts",
    primaryKeys = ["identityHash", "contactHash"]
)
data class AllowedContactEntity(
    val identityHash: String,    // Child's identity
    val contactHash: String,     // Allowed contact's destination
    val displayName: String?,    // Contact's name
    val addedTimestamp: Long,    // When added
)
```

### PairedChildEntity

Stores paired children on the parent's device:

```kotlin
@Entity(tableName = "paired_children")
data class PairedChildEntity(
    @PrimaryKey val childDestinationHash: String,
    val displayName: String?,
    val isLocked: Boolean = false,
    val lockChangedTimestamp: Long = 0,
    val pairedTimestamp: Long,
    val lastSeenTimestamp: Long,
    val guardianIdentityHash: String,  // Parent's identity
)
```

---

## Source Code Reference

| Component | File |
|-----------|------|
| Command Processing | `app/.../service/GuardianCommandProcessor.kt` |
| Repository | `data/.../repository/GuardianRepository.kt` |
| Crypto (Python) | `python/guardian_crypto.py` |
| Python Wrapper | `python/reticulum_wrapper.py` |
| Message Filtering | `app/.../service/MessageCollector.kt` |
| Send Blocking | `app/.../viewmodel/MessagingViewModel.kt` |
| Parent UI | `app/.../ui/screens/GuardianScreen.kt` |
| QR Scanner | `app/.../ui/screens/GuardianQrScannerScreen.kt` |
| Database Entities | `data/.../db/entity/Guardian*.kt` |
