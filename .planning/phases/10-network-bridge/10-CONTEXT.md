# Phase 10: Network Bridge - Context

**Gathered:** 2026-02-04
**Status:** Ready for planning

<domain>
## Phase Boundary

Connect Kotlin audio pipeline to Python Reticulum network layer. Encoded packets (<100 bytes) flow bidirectionally. Signalling (call status, profile changes) works inband. This phase delivers the bridge — Telephony integration is Phase 11.

</domain>

<decisions>
## Implementation Decisions

### Packet Handoff Pattern
- **Kotlin → Python:** Direct Chaquopy call (synchronous, simple)
- **Python → Kotlin:** Python callback to Kotlin method (event-driven, low latency)
- **Packet format:** Raw ByteArray (no Base64 encoding overhead)
- **Class naming:** Match Python LXST names (Packetizer, LinkSource, SignallingReceiver)

### Signalling Flow
- **Channel:** Inband with audio packets (matches Python LXST approach)
- **Events:** Match Python LXST event types (status changes, mute state, audio level)
- **Delivery:** Fire-and-forget (non-blocking, matches LXST)

### Threading Model
- **Bridge calls:** Dedicated bridge thread (research feasibility, avoids GIL contention)
- **Incoming packets:** Queue handoff from Python to Kotlin audio thread (thread-safe)
- **Queue depth:** Max 8 packets, drop oldest if full (matches Mixer backpressure)
- **Initialization:** Eager at app start (no latency on first call)

### Error Handling
- **Bridge crash:** End call gracefully (signal call ended, cleanup resources)
- **Metrics:** None (keep it simple — just works or fails)
- **Packet validation:** Trust encoder (Phase 7 codecs enforce limits)

### Logging Constraint (CRITICAL)
- **Logging MUST NOT block audio thread**
- Use async logging or separate logging thread
- No synchronous Log.d() calls in packet hot path
- This prevents audio choppiness from logging overhead

### Claude's Discretion
- Profile change initiator (research Telephone.py behavior)
- Packet failure handling (log and continue vs retry once)
- Exact Chaquopy threading implementation
- Bridge thread lifecycle management

</decisions>

<specifics>
## Specific Ideas

- "Match Python LXST exactly — this is a port, not a redesign"
- Guiding principle from earlier phases applies here too
- Phase 7-9 patterns: coroutine-based, queue backpressure, float32 internal format
- Latency target: <5ms for packet transfer across bridge

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 10-network-bridge*
*Context gathered: 2026-02-04*
