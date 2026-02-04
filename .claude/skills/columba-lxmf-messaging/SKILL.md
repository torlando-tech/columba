# Columba LXMF Messaging Skill

```yaml
---
name: columba-lxmf-messaging
description: LXMF (Lightweight Extensible Message Format) protocol integration for Columba messenger. Use when implementing message sending/receiving, conversation management, delivery tracking, propagation node integration, or working with LXMF message fields and attachments.
version: 1.0.0
author: Columba Development
tags: [lxmf, messaging, reticulum, protocol, columba]
---
```

## Overview

This skill provides comprehensive guidance for integrating **LXMF (Lightweight Extensible Message Format)** protocol into the Columba Android messenger application. LXMF is a messaging format and delivery protocol built on Reticulum Network Stack that provides:

- **Zero-conf message routing** - No centralized infrastructure required
- **End-to-end encryption with Forward Secrecy** - All messages encrypted, ratcheted keys
- **Minimal bandwidth usage** - 111 bytes overhead, works on packet radio/LoRa
- **Multiple delivery methods** - Direct (real-time), opportunistic (single packet), propagated (store-and-forward)
- **Extensible fields** - Support for attachments, telemetry, audio, images, etc.

## When to Use This Skill

Auto-triggers when working on:
- Message sending and receiving logic
- Conversation management and storage
- Delivery confirmation and progress tracking
- Propagation node integration (store-and-forward)
- Message fields (attachments, audio, telemetry, images)
- LXMF-Reticulum integration
- Python-Kotlin messaging bridge (Chaquopy)
- Message state management
- Delivery receipt handling

## Quick Reference

### Common Scenarios

#### Sending a Message
```kotlin
// Kotlin side
viewModelScope.launch {
    val result = lxmfRepository.sendMessage(
        destinationHash = peerHash,
        content = messageText,
        method = DeliveryMethod.DIRECT,
        fields = mapOf()
    )
    // Message stored in database with state tracking
}
```

#### Receiving a Message
```python
# Python callback automatically triggered
def _on_message_received(self, message):
    # Validate, process, emit to Kotlin
    emit_event({
        "type": "message_received",
        "message_id": message.hash.hex(),
        "source_hash": message.source_hash.hex(),
        "content": message.content.decode("utf-8"),
        "timestamp": message.timestamp
    })
```

#### Tracking Delivery Status
```kotlin
// Observe message state
messageFlow
    .filter { it.id == messageId }
    .collect { message ->
        when (message.state) {
            MessageState.OUTBOUND -> showSending()
            MessageState.DELIVERED -> showDelivered()
            MessageState.FAILED -> showFailed()
        }
    }
```

## Core Concepts

### 1. LXMessage Structure

Every LXMF message contains:
- **Required Fields**: destination_hash, source_hash, signature, timestamp
- **Optional Content**: title (bytes), content (bytes)
- **Optional Fields**: Dictionary of typed fields (attachments, telemetry, etc.)
- **Metadata**: State, method, progress, packed bytes

**Size Limits:**
- Opportunistic: ~295 bytes total (single packet)
- Direct (single packet): ~319 bytes content
- Direct (resource): Up to 128 KB (configurable delivery_limit)
- Propagated: No practical limit (async store-and-forward)

### 2. Delivery Methods

| Method | Use When | Link Required | Size Limit | Delivery |
|--------|----------|---------------|------------|----------|
| **Opportunistic** | Have ratchet, small message | No | ~295 bytes | Single packet |
| **Direct** | Real-time, online recipient | Yes | 128 KB | Over established link |
| **Propagated** | Offline recipient | No | No limit | Via propagation node |

**Automatic Fallback:**
```python
lxm.try_propagation_on_fail = True  # Falls back to propagation if direct fails
```

### 3. Message States

```python
GENERATING   = 0x00  # Being created
OUTBOUND     = 0x01  # Ready to send
SENDING      = 0x02  # Currently sending
SENT         = 0x04  # Sent (propagated) or awaiting delivery
DELIVERED    = 0x08  # Confirmed delivered
REJECTED     = 0xFD  # Rejected by recipient
CANCELLED    = 0xFE  # Cancelled by sender
FAILED       = 0xFF  # Delivery failed
```

### 4. Message Fields

Extend messages with rich content:

```python
FIELD_EMBEDDED_LXMS    = 0x01  # Nested messages
FIELD_TELEMETRY        = 0x02  # Telemetry data
FIELD_ICON_APPEARANCE  = 0x04  # User avatar/icon
FIELD_FILE_ATTACHMENTS = 0x05  # File attachments
FIELD_IMAGE            = 0x06  # Image data
FIELD_AUDIO            = 0x07  # Audio data (PTT, voice messages)
FIELD_THREAD           = 0x08  # Threading/replies
FIELD_RENDERER         = 0x0F  # Content renderer hint (markdown, plain, etc.)
```

### 5. LXMRouter - Central API

**LXMRouter** handles all messaging operations:
- Message packing and encryption
- Link establishment (for direct delivery)
- Delivery confirmation
- Retries and fallback
- Propagation node communication
- Incoming message routing

**Don't bypass the router** - it manages complexity for you.

## Architecture Integration

### Data Flow Overview

```
[Kotlin UI]
    ↓ (user sends message)
[ViewModel]
    ↓ (via LxmfRepository)
[Python LXMF Wrapper]
    ↓ (LXMRouter.handle_outbound)
[Reticulum Network]
    ↓
[Remote Peer]
    ↓ (receives, sends delivery confirmation)
[Python Callback]
    ↓ (emit event)
[Kotlin Event Handler]
    ↓ (update database)
[Room Database]
    ↓ (Flow)
[UI Updates]
```

### Integration with Other Skills

**Related Skills:**
- **columba-threading-redesign**: Use proper dispatchers for Python calls (Dispatchers.IO)
- **columba-data-persistence**: Message and conversation database schema (see Data Models)
- **columba-ble-networking**: Reticulum interfaces (BLE is one transport option)
- **kotlin-android-chaquopy-testing**: Test Python-Kotlin messaging bridge

## Component Responsibilities

### Python Layer (`lxmf_wrapper.py`)
- Initialize Reticulum and LXMRouter
- Handle outbound messages via router
- Process inbound messages via callbacks
- Emit events to Kotlin layer (JSON)
- Manage propagation node sync
- Store/restore identities

### Kotlin Data Layer
- **MessageEntity**: Store packed LXM + metadata
- **ConversationEntity**: Track per-peer context
- **LxmfRepository**: Coordinate Python calls and database
- **MessageDao/ConversationDao**: Room database access

### Kotlin UI Layer
- **MessageScreen**: Display messages, send UI
- **MessageViewModel**: State management, user actions
- **EventHandler**: Process Python events (received, delivered, failed)

## Database Schema

### MessageEntity
```kotlin
@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,           // LXM hash (hex)
    val conversationHash: String,         // Peer destination hash
    val content: String,                  // Message content (UTF-8)
    val title: String = "",               // LXMF title field
    val timestamp: Long,                  // Unix timestamp (ms)
    val isFromMe: Boolean,                // Originator flag
    val state: Int,                       // LXMF state (0x00-0xFF)
    val method: Int,                      // Delivery method (1-5)
    val packedLxm: ByteArray,             // Full packed message
    val fields: String,                   // JSON of LXMF fields
    val extras: String = "{}",            // JSON for rssi, snr, stamps
    val isRead: Boolean = false,          // Read status
    val progress: Float = 0f              // 0.0 to 1.0
)
```

### ConversationEntity
```kotlin
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val peerHash: String,     // Destination hash (hex)
    val peerName: String,                 // Display name
    val lastMessage: String,              // Last message preview
    val lastMessageTimestamp: Long,       // Last activity
    val unreadCount: Int = 0,             // Unread messages
    val isTrusted: Boolean = false,       // Trust flag
    val appearance: String? = null,       // JSON [icon, fg, bg]
    val sendTelemetry: Boolean = false    // Auto-send telemetry
)
```

## Key Implementation Patterns

### Pattern 1: Sending Messages

See `patterns/send-message-pattern.md` for complete before/after code.

**High-level flow:**
1. User enters text, taps send
2. ViewModel validates and calls repository
3. Repository calls Python wrapper (Dispatchers.IO)
4. Python creates LXMessage, registers callbacks, sends via router
5. Python returns message ID and packed bytes
6. Kotlin stores in database (state = OUTBOUND)
7. Python callback updates state (DELIVERED/FAILED)
8. UI observes Flow and updates

### Pattern 2: Receiving Messages

See `patterns/receive-message-pattern.md` for complete implementation.

**High-level flow:**
1. Python router receives message via callback
2. Validate signature, check if known sender
3. Emit JSON event to Kotlin
4. Kotlin event handler parses JSON
5. Store in database (isFromMe = false)
6. Create/update conversation
7. Increment unread count
8. Show notification if app backgrounded
9. UI Flow automatically updates

### Pattern 3: Conversation Context

See `patterns/conversation-management.md` for details.

**Key principle:** Conversation identified by peer's destination hash.

```kotlin
// All messages for a conversation
val messages = messageDao.getMessagesForConversation(peerHash)

// Outbound: message.destinationHash == peerHash
// Inbound: message.sourceHash == peerHash (we store as conversationHash)
```

### Pattern 4: Propagation Node Integration

See `patterns/propagation-node-sync.md` for full implementation.

**Use cases:**
- Recipient is offline
- Asynchronous messaging
- Message persistence

**Key operations:**
- Set propagation node: `router.set_outbound_propagation_node(node_hash)`
- Send via propagation: `desired_method = LXMF.LXMessage.PROPAGATED`
- Retrieve messages: `router.request_messages_from_propagation_node(identity)`
- Track progress: `router.propagation_transfer_state` and `propagation_transfer_progress`

## Documentation Structure

### Quick Start
- **This file (SKILL.md)** - Overview and quick reference

### Deep Dive Guides (`docs/`)
1. **LXMF_PROTOCOL_GUIDE.md** - Complete protocol specification
2. **PYTHON_INTEGRATION.md** - Chaquopy wrapper implementation
3. **DATABASE_SCHEMA.md** - Room database design and migrations
4. **EVENT_HANDLING.md** - Python→Kotlin event system
5. **PROPAGATION_NODES.md** - Store-and-forward messaging
6. **MESSAGE_FIELDS.md** - Attachments, telemetry, audio, images
7. **TROUBLESHOOTING.md** - Common issues and solutions

### Implementation Patterns (`patterns/`)
1. **send-message-pattern.md** - Complete send workflow
2. **receive-message-pattern.md** - Complete receive workflow
3. **conversation-management.md** - Context and threading
4. **propagation-node-sync.md** - Async messaging patterns
5. **delivery-tracking.md** - State management and callbacks

### Code Templates (`templates/`)
1. **lxmf_wrapper.py** - Python LXMF wrapper (complete)
2. **MessageRepository.kt** - Kotlin repository layer
3. **MessageViewModel.kt** - ViewModel with state management
4. **MessageScreen.kt** - Compose UI for messages
5. **EventHandler.kt** - Python event processing
6. **MessageDao.kt** - Room DAO operations

### Verification Checklists (`checklists/`)
1. **integration-checklist.md** - Pre-deployment verification
2. **testing-checklist.md** - Test coverage requirements
3. **performance-checklist.md** - Performance targets

### Reference Materials (`references/`)
1. **lxmf-research-report.md** - Complete research findings (12,000+ words)
2. **sideband-patterns.md** - Production app reference
3. **api-reference.md** - Python LXMF API quick reference

## Performance Targets

### Message Delivery
- **Direct delivery**: < 5s for small messages
- **Opportunistic delivery**: < 2s (single packet)
- **Propagated delivery**: Best-effort (depends on node sync)

### Python-Kotlin Bridge
- **Message send call**: < 100ms (non-blocking)
- **Event processing**: < 50ms per event
- **Database write**: < 100ms per message

### UI Responsiveness
- **Message list scroll**: 60 FPS (< 16ms/frame)
- **Send button response**: < 200ms visual feedback
- **State updates**: Immediate (Flow updates)

## Testing Strategy

### Unit Tests
- Python wrapper functions (mock LXMF router)
- Repository layer (mock Python module)
- ViewModel logic (mock repository)
- Database operations (Room in-memory DB)

### Integration Tests
- Python→Kotlin event flow
- End-to-end message send/receive (local loopback)
- State transitions
- Error handling

### Manual Tests
- Multi-device messaging
- Network interruption recovery
- Background/foreground transitions
- Notification delivery

## Common Gotchas

### 1. Don't Re-pack Messages
```python
# WRONG
lxm.pack()
lxm.pack()  # Don't pack twice

# CORRECT
router.handle_outbound(lxm)  # Router packs if needed
```

### 2. Store Complete Packed Messages
```kotlin
// WRONG
messageDao.insert(content = message.content)  // Loses metadata

// CORRECT
messageDao.insert(
    content = message.content,
    packedLxm = message.packed  // Store full packed message
)
```

### 3. Use Proper Dispatcher for Python Calls
```kotlin
// WRONG
viewModelScope.launch {
    pythonModule.callAttr("send_message", ...)  // Blocks main thread!
}

// CORRECT
viewModelScope.launch {
    withContext(Dispatchers.IO) {
        pythonModule.callAttr("send_message", ...)
    }
}
```

### 4. Handle Conversation Context Correctly
```kotlin
// For OUTBOUND messages
conversationHash = message.destinationHash

// For INBOUND messages
conversationHash = message.sourceHash
```

### 5. Don't Bypass LXMRouter
```python
# WRONG - manual packet sending
packet = RNS.Packet(dest, lxm.packed)
packet.send()

# CORRECT - let router handle delivery
router.handle_outbound(lxm)
```

## Next Steps

### For New Implementation
1. Read `docs/LXMF_PROTOCOL_GUIDE.md` for protocol understanding
2. Review `docs/PYTHON_INTEGRATION.md` for wrapper setup
3. Study `patterns/send-message-pattern.md` for send flow
4. Study `patterns/receive-message-pattern.md` for receive flow
5. Copy templates to your codebase
6. Run through `checklists/integration-checklist.md`

### For Existing Code Enhancement
1. Review current implementation vs patterns
2. Check `docs/TROUBLESHOOTING.md` for known issues
3. Verify database schema matches `docs/DATABASE_SCHEMA.md`
4. Add missing field support from `docs/MESSAGE_FIELDS.md`
5. Optimize using `checklists/performance-checklist.md`

## Additional Resources

- **LXMF Specification**: https://github.com/markqvist/LXMF
- **Reticulum Manual**: https://markqvist.github.io/Reticulum/manual/
- **Sideband Source**: Reference implementation in Python
- **reticulum-manual-mcp**: MCP server for searching Reticulum docs

## Skill Maintenance

**Last Updated**: 2025-10-30
**LXMF Version**: Compatible with LXMF 0.4.x+
**Reticulum Version**: Compatible with RNS 0.8.x+
**Columba Target**: Android 15+ (SDK 35)

**Update Triggers:**
- LXMF protocol changes
- New LXMF field types
- Reticulum API changes
- Android SDK updates affecting Chaquopy
- Performance optimization discoveries
