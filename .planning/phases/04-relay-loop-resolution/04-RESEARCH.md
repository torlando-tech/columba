# Phase 4: Relay Loop Resolution - Research

**Researched:** 2026-01-29
**Domain:** Kotlin StateFlow lifecycle, Room database invalidation, reactive state management
**Confidence:** HIGH

## Summary

Phase 2 implemented a state machine with debounce and cooldown to prevent relay selection loops. Despite this fix, Sentry continues to report "Relay selection loop detected" warnings on v0.7.3-beta. The root cause investigation reveals that the Phase 2 fix addressed the **symptom** (rapid re-selection) but not the **underlying cause**: the StateFlow configuration uses `SharingStarted.Eagerly`, which keeps the upstream Flow collecting indefinitely regardless of whether anyone is observing.

The Seer (Sentry AI) recommendation to use `SharingStarted.WhileSubscribed` is correct. With `Eagerly`, the `currentRelayState`, `currentRelay`, and `availableRelaysState` StateFlows are continuously active. Combined with Room's broad table-level invalidation (ANY row change triggers Flow emissions), this creates constant upstream activity that can trigger edge cases the state machine guard doesn't fully prevent.

**Root causes identified:**
1. `SharingStarted.Eagerly` keeps upstream Flows active indefinitely, even when no UI is observing
2. Room's InvalidationTracker triggers Flow emissions for ANY table change, not just relevant rows
3. Multiple active StateFlows multiplies the trigger frequency
4. The state machine guard only prevents selection logic, but the collector code still runs for every emission

**Primary recommendation:** Replace `SharingStarted.Eagerly` with `SharingStarted.WhileSubscribed(5000L)` for all three StateFlows in PropagationNodeManager. This stops upstream collection when no observers are present, eliminating unnecessary reactive churn during background operation.

## Standard Stack

This phase addresses StateFlow configuration, not library selection. The existing stack is appropriate:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin Coroutines | 1.8+ | Asynchronous programming | Industry standard for Android async |
| Kotlin Flow | 1.8+ | Reactive streams | Built into coroutines, designed for reactive state |
| StateFlow | 1.8+ | Hot Flow for state | Standard for exposing state from managers |
| Room | 2.6+ | Database with reactive queries | Official Android persistence with Flow support |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `SharingStarted.WhileSubscribed` | Flow API | Lifecycle-aware sharing | When upstream should stop when no observers |
| `distinctUntilChanged()` | Flow operator | Filter duplicate emissions | Already in use, prevents redundant syncs |
| `debounce()` | Flow operator | Rate-limit emissions | Already in use for announce Flow |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| WhileSubscribed(5000L) | WhileSubscribed(0) | No timeout grace period during config changes |
| WhileSubscribed(5000L) | Lazily | Never stops upstream once started |
| WhileSubscribed(5000L) | Eagerly (current) | Never stops upstream, root cause of issue |

**Installation:**
Not applicable - all components already in use. This is a configuration change.

## Architecture Patterns

### Pattern 1: WhileSubscribed for Service-Layer StateFlows
**What:** Use `SharingStarted.WhileSubscribed(5000L)` instead of `Eagerly` for StateFlows that derive from database Flows
**When to use:** When the upstream Flow connects to Room database queries
**Why:** Stops database observation when no UI is collecting, reducing reactive churn

**Current (problematic):**
```kotlin
// Source: PropagationNodeManager.kt lines 197-202
val currentRelayState: StateFlow<RelayLoadState> =
    contactRepository.getMyRelayFlow()
        .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
            RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
        }
        .stateIn(scope, SharingStarted.Eagerly, RelayLoadState.Loading)
```

**Fixed:**
```kotlin
// SharingStarted.WhileSubscribed stops upstream when no collectors
val currentRelayState: StateFlow<RelayLoadState> =
    contactRepository.getMyRelayFlow()
        .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
            RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000L),
            RelayLoadState.Loading
        )
```

### Pattern 2: Consistent 5000ms Timeout
**What:** Use 5000ms (5 seconds) as the standard timeout for WhileSubscribed
**When to use:** All ViewModels and Managers that expose StateFlows
**Why:** Matches Android's recommended pattern, survives configuration changes without restart

```kotlin
// Source: Android Developers official guidance
SharingStarted.WhileSubscribed(
    stopTimeoutMillis = 5000L,  // 5 second grace period
    replayExpirationMillis = Long.MAX_VALUE  // Keep replay cache
)
```

This timeout allows:
- Screen rotation (typically <2 seconds)
- Brief navigation away and back
- Configuration changes

Without restarting the upstream Flow unnecessarily.

### Pattern 3: Derived StateFlows Follow Parent Strategy
**What:** When deriving a StateFlow from another StateFlow, use the same SharingStarted strategy
**When to use:** When creating mapped/transformed StateFlows

**Current (problematic):**
```kotlin
// currentRelay derives from currentRelayState
val currentRelay: StateFlow<RelayInfo?> =
    currentRelayState
        .map { state -> (state as? RelayLoadState.Loaded)?.relay }
        .stateIn(scope, SharingStarted.Eagerly, null)  // Eagerly keeps running
```

**Fixed:**
```kotlin
val currentRelay: StateFlow<RelayInfo?> =
    currentRelayState
        .map { state -> (state as? RelayLoadState.Loaded)?.relay }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000L),  // Consistent with parent
            null
        )
```

### Anti-Patterns to Avoid

- **SharingStarted.Eagerly for database-derived Flows:** Keeps Room observation active indefinitely, causes unnecessary work
- **Mixing sharing strategies:** Creates confusing behavior when parent stops but child doesn't
- **Zero timeout on WhileSubscribed:** Causes restart churn during configuration changes
- **Eagerly for resource-intensive upstreams:** Wastes battery/CPU when no UI is observing

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Stopping upstream when no observers | Manual start/stop logic | `SharingStarted.WhileSubscribed` | Built-in lifecycle management |
| Grace period during config changes | Timer-based restart prevention | `WhileSubscribed(5000L)` timeout | Handles edge cases correctly |
| Replay for late subscribers | Manual caching | StateFlow's built-in replay | Guaranteed single latest value |
| Subscription counting | Manual subscriber tracking | SharingStarted commands | Correct atomicity |

**Key insight:** The fix is a configuration change (`Eagerly` to `WhileSubscribed`), not new code. The existing state machine, debounce, and cooldown remain valuable as defense-in-depth.

## Common Pitfalls

### Pitfall 1: Eagerly Keeps Upstream Forever
**What goes wrong:** `SharingStarted.Eagerly` starts the upstream Flow immediately and NEVER stops it, regardless of subscriber count.

**Why it happens:** `Eagerly` was chosen for "immediate availability" - wanting the state to be populated before any UI observes. But for Room-derived Flows, this means the database InvalidationTracker runs continuously.

**How to avoid:**
- Use `SharingStarted.WhileSubscribed(5000L)` for database-derived StateFlows
- Accept that first observation may see initial/Loading state briefly
- Use Loading sealed class states to handle async initialization

**Warning signs:**
- Room queries running when app is in background
- Logcat shows Flow emissions with no UI visible
- State machine guards triggering frequently (collector running but blocked)

### Pitfall 2: Room's Broad Invalidation + Multiple Eager Flows
**What goes wrong:** Room triggers Flow emissions for ANY change to observed tables. With 3 Eagerly-shared StateFlows all observing database tables, you get 3x the emission frequency.

**Why it happens:** Room's InvalidationTracker operates at table level, not row level. When any announce updates, all three Flows emit.

**How to avoid:**
- Reduce active observers with `WhileSubscribed`
- Keep debounce on high-frequency Flows (already done)
- Consider `distinctUntilChanged()` on business-relevant fields (already done)

**Warning signs:**
- Multiple log entries for same database change
- State machine logs showing frequent "Skipping auto-select - state=X"
- Battery drain when app should be idle

### Pitfall 3: State Machine Guard Doesn't Prevent Collection
**What goes wrong:** The state machine guard (`if (_selectionState.value != IDLE) return@collect`) prevents selection LOGIC from running, but the collector lambda still executes for every emission. With Eagerly, this is every database change.

**Why it happens:** Misunderstanding of what the guard protects. It protects against re-entrant selection, not against collection overhead.

**How to avoid:**
- Stop the upstream entirely with `WhileSubscribed` when not needed
- Guard is still valuable for the times when collection IS needed

**Warning signs:**
- High frequency of "Skipping auto-select" log messages
- Collector code runs but no visible effect (state check passes)

### Pitfall 4: WhileSubscribed Zero Timeout Causes Churn
**What goes wrong:** Using `WhileSubscribed()` without timeout causes upstream restart on every screen rotation or navigation.

**Why it happens:** Zero timeout means upstream stops immediately when last subscriber leaves, even during normal Android lifecycle events.

**How to avoid:**
- Always use timeout: `WhileSubscribed(5000L)` is the standard
- 5 seconds covers configuration changes and brief navigation

**Warning signs:**
- Database query logs on every screen rotation
- Brief flash of Loading state after rotation
- Performance degradation on navigation

## Code Examples

Verified patterns for the fix:

### Fix 1: currentRelayState StateFlow
```kotlin
// Source: PropagationNodeManager.kt lines 197-202
// BEFORE (problematic):
val currentRelayState: StateFlow<RelayLoadState> =
    contactRepository.getMyRelayFlow()
        .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
            RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
        }
        .stateIn(scope, SharingStarted.Eagerly, RelayLoadState.Loading)

// AFTER (fixed):
val currentRelayState: StateFlow<RelayLoadState> =
    contactRepository.getMyRelayFlow()
        .combine(settingsRepository.autoSelectPropagationNodeFlow) { contact, isAutoSelect ->
            RelayLoadState.Loaded(buildRelayInfo(contact, isAutoSelect))
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000L),
            RelayLoadState.Loading
        )
```

### Fix 2: currentRelay StateFlow
```kotlin
// Source: PropagationNodeManager.kt lines 209-212
// BEFORE (problematic):
val currentRelay: StateFlow<RelayInfo?> =
    currentRelayState
        .map { state -> (state as? RelayLoadState.Loaded)?.relay }
        .stateIn(scope, SharingStarted.Eagerly, null)

// AFTER (fixed):
val currentRelay: StateFlow<RelayInfo?> =
    currentRelayState
        .map { state -> (state as? RelayLoadState.Loaded)?.relay }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000L),
            null
        )
```

### Fix 3: availableRelaysState StateFlow
```kotlin
// Source: PropagationNodeManager.kt lines 220-236
// BEFORE (problematic):
val availableRelaysState: StateFlow<AvailableRelaysState> =
    announceRepository.getTopPropagationNodes(limit = 10)
        .map { announces ->
            // ... mapping logic ...
            AvailableRelaysState.Loaded(relays)
        }
        .stateIn(scope, SharingStarted.Eagerly, AvailableRelaysState.Loading)

// AFTER (fixed):
val availableRelaysState: StateFlow<AvailableRelaysState> =
    announceRepository.getTopPropagationNodes(limit = 10)
        .map { announces ->
            // ... mapping logic ...
            AvailableRelaysState.Loaded(relays)
        }
        .stateIn(
            scope,
            SharingStarted.WhileSubscribed(5000L),
            AvailableRelaysState.Loading
        )
```

### Existing Code That Remains Unchanged
The following Phase 2 fixes remain valuable and should NOT be removed:
- State machine (`RelaySelectionState` enum)
- `debounce(1000)` on announce Flow
- State guard in `observePropagationNodeAnnounces()`
- Loop detection with `recordSelection()`
- 30-second cooldown after selection
- Exponential backoff on loop detection

These provide defense-in-depth. The WhileSubscribed change addresses the root cause; the state machine handles edge cases.

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| SharingStarted.Eagerly for all | WhileSubscribed for database Flows | Kotlin 1.4+ (2020), widespread 2022+ | Reduces unnecessary work |
| Eagerly for "always available" | Loading states + WhileSubscribed | Industry consensus 2023+ | Better resource management |
| No timeout on WhileSubscribed | 5000ms standard timeout | Android guidance 2021+ | Survives config changes |

**Deprecated/outdated:**
- `SharingStarted.Eagerly` for database-derived StateFlows: Causes unnecessary database observation
- Zero-timeout `WhileSubscribed()`: Causes restart churn on configuration changes

## Open Questions

Things that couldn't be fully resolved:

1. **Impact on service-process StateFlows**
   - What we know: PropagationNodeManager runs in service process, not UI process
   - What's unclear: Does WhileSubscribed work correctly across process boundaries via AIDL?
   - Recommendation: Test thoroughly. The StateFlows are collected within the service process, so should work. But verify no regression in UI responsiveness.

2. **Interaction with start() method**
   - What we know: start() launches coroutines that collect from currentRelay and announces
   - What's unclear: Does WhileSubscribed affect these internal collectors?
   - Recommendation: The internal collectors (observeRelayChanges, observePropagationNodeAnnounces) count as subscribers. When start() runs, they subscribe and keep upstream active. This is correct behavior - upstream runs while service is active.

3. **Initial Loading state on first access**
   - What we know: With WhileSubscribed, first access may see Loading state
   - What's unclear: Will UI handle Loading state gracefully everywhere?
   - Recommendation: UI already handles Loading states (RelayLoadState.Loading exists). Verify no regressions in relay modal and settings screens.

## Sources

### Primary (HIGH confidence)
- [StateFlow and SharedFlow - Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) - Official Android guide on SharingStarted strategies
- [SharingStarted - Kotlin Coroutines API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/-sharing-started/) - Official API documentation for SharingStarted
- Columba codebase: PropagationNodeManager.kt - Current implementation showing Eagerly usage

### Secondary (MEDIUM confidence)
- [Things to know about Flow's shareIn and stateIn operators](https://manuelvivo.dev/sharein-statein) - Manuel Vivo (Android DevRel) on sharing strategies
- [WhileSubscribed(5000)](https://blog.p-y.wtf/whilesubscribed5000) - Detailed analysis of timeout parameter
- [ShareIn vs StateIn in Kotlin Flows](https://medium.com/@mortitech/sharein-vs-statein-in-kotlin-flows-when-to-use-each-1a19bd187553) - Community best practices

### Tertiary (LOW confidence)
- Sentry Seer recommendation - AI-generated suggestion to use WhileSubscribed (aligned with official guidance)

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - Configuration change only, well-documented APIs
- Architecture: HIGH - Patterns verified against official Android guidance
- Pitfalls: HIGH - Root cause directly observed in current implementation
- Code examples: HIGH - Direct modifications to existing PropagationNodeManager.kt

**Research date:** 2026-01-29
**Valid until:** 90 days (stable domain - StateFlow configuration is mature)
**Key assumption:** The loop is caused by excessive reactive churn from Eagerly-shared StateFlows, not a fundamental algorithm bug. If loops persist after WhileSubscribed change, investigate further (Python layer, Room query design).
