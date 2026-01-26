# Phase 2: Relay Loop Fix - Research

**Researched:** 2026-01-25
**Domain:** Reactive state management, Kotlin Flow feedback loops, Android Room database
**Confidence:** HIGH

## Summary

The relay auto-selection loop (#343) is a **reactive state feedback loop** where database updates trigger Flow observers that update the database again, creating an infinite cycle. The current implementation in `PropagationNodeManager` has two coroutines that can form a feedback loop:

1. `observeRelayChanges()` - watches the database relay and syncs to Python
2. `observePropagationNodeAnnounces()` - watches available relays and updates the database

The critical issue: when `observePropagationNodeAnnounces()` calls `contactRepository.setAsMyRelay()`, it updates the database, which triggers `observeRelayChanges()` via the `currentRelay` Flow. This can cause rapid cycling if the conditions aren't stable.

**Root causes identified:**
- Room's InvalidationTracker triggers Flow emissions for ANY table change, not just relevant rows
- No debounce/cooldown between relay selection attempts
- The `observePropagationNodeAnnounces()` Flow collector fires on EVERY database change to announces table (hundreds of announces updating frequently)
- Missing state flag to prevent selection during ongoing selection
- `distinctUntilChanged()` on destinationHash alone isn't sufficient - need to prevent the selection logic from running at all during stabilization

**Primary recommendation:** Add a state machine with explicit selection phases (IDLE, SELECTING, STABLE) and debounce timer. Use the state flag to prevent `observePropagationNodeAnnounces()` from triggering selection while already selecting. Add exponential backoff for failure cases.

## Standard Stack

This is a Kotlin/Android architecture issue, not a library selection problem. The existing stack is appropriate:

### Core
| Library | Version | Purpose | Why Standard |
|---------|---------|---------|--------------|
| Kotlin Coroutines | 1.8+ | Asynchronous programming | Industry standard for Android async |
| Kotlin Flow | 1.8+ | Reactive streams | Built into coroutines, designed for reactive state |
| Room | 2.6+ | Database with reactive queries | Official Android persistence library with Flow support |
| StateFlow | 1.8+ | Hot Flow for state | Standard for exposing state from ViewModels/managers |

### Supporting
| Library | Version | Purpose | When to Use |
|---------|---------|---------|-------------|
| `distinctUntilChanged()` | Flow operator | Filter duplicate emissions | Already used, but insufficient alone |
| `debounce()` | Flow operator | Add delay between emissions | Use for auto-selection cooldown |
| `flatMapLatest()` | Flow operator | Cancel previous collection on new emission | Use if switching to query-on-demand pattern |

### Alternatives Considered
| Instead of | Could Use | Tradeoff |
|------------|-----------|----------|
| Flow-based reactive | Polling with timers | Simpler but less efficient, misses real-time updates |
| StateFlow state machine | Channel-based actor | More complex, harder to test, same result |
| Room Flow triggers | Manual database observers | Reinventing Room's InvalidationTracker, more code |

**Installation:**
Not applicable - all components already in use.

## Architecture Patterns

### Pattern 1: State Machine for Selection Process
**What:** Explicit state tracking to prevent re-entrant selection logic
**When to use:** When a reactive process can trigger itself through side effects
**Example:**
```kotlin
// Add state enum
enum class RelaySelectionState {
    IDLE,           // No selection in progress
    SELECTING,      // Selection triggered, waiting for database update
    STABLE,         // Relay selected and stable, cooldown active
    BACKING_OFF     // Failed selection, exponential backoff in progress
}

private val _selectionState = MutableStateFlow(RelaySelectionState.IDLE)

// Guard clause in observePropagationNodeAnnounces
private suspend fun observePropagationNodeAnnounces() {
    announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).collect { propagationNodes ->
        // CRITICAL: Don't trigger selection if we're already selecting or cooling down
        if (_selectionState.value != RelaySelectionState.IDLE) {
            Log.d(TAG, "Skipping auto-select - state=${_selectionState.value}")
            return@collect
        }

        val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()
        if (isAutoSelect && propagationNodes.isNotEmpty()) {
            _selectionState.value = RelaySelectionState.SELECTING
            // ... selection logic ...
            _selectionState.value = RelaySelectionState.STABLE

            // Start cooldown timer
            scope.launch {
                delay(30_000) // 30 second cooldown
                if (_selectionState.value == RelaySelectionState.STABLE) {
                    _selectionState.value = RelaySelectionState.IDLE
                }
            }
        }
    }
}
```

### Pattern 2: Debounce for Database-Triggered Flows
**What:** Add time-based filtering to prevent rapid re-triggers
**When to use:** When database updates happen frequently but actions should be rate-limited
**Example:**
```kotlin
// Debounce announces Flow to reduce trigger frequency
announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE"))
    .debounce(1000) // Wait 1 second after last change before collecting
    .collect { propagationNodes ->
        // Selection logic
    }
```

### Pattern 3: Loop Detection and Circuit Breaker
**What:** Track selection frequency and halt if excessive
**When to use:** Defense-in-depth to catch bugs that slip through state machine
**Example:**
```kotlin
private val recentSelections = ArrayDeque<Long>(maxSize = 10)

private fun recordSelection(destinationHash: String) {
    val now = System.currentTimeMillis()
    recentSelections.addLast(now)
    if (recentSelections.size > 10) recentSelections.removeFirst()

    // Check for loop: 3+ selections in 60 seconds
    val oneMinuteAgo = now - 60_000
    val recentCount = recentSelections.count { it > oneMinuteAgo }

    if (recentCount >= 3) {
        Log.w(TAG, "⚠️ Relay selection loop detected! $recentCount selections in 60s")
        // Send Sentry event (from context decisions)
        _selectionState.value = RelaySelectionState.BACKING_OFF

        // Exponential backoff
        scope.launch {
            val backoffMs = minOf(2.0.pow(recentCount - 3).toLong() * 1000, 600_000) // Max 10 min
            delay(backoffMs)
            _selectionState.value = RelaySelectionState.IDLE
        }
    }
}
```

### Pattern 4: User Action Precedence
**What:** Cancel auto-selection immediately when user manually selects
**When to use:** User actions should always override automatic behavior
**Example:**
```kotlin
suspend fun setManualRelay(destinationHash: String, displayName: String) {
    // CRITICAL: Cancel any ongoing auto-selection
    if (_selectionState.value == RelaySelectionState.SELECTING) {
        Log.i(TAG, "User manual selection - cancelling auto-select")
        _selectionState.value = RelaySelectionState.IDLE
    }

    // Rest of manual selection logic
    settingsRepository.saveAutoSelectPropagationNode(false)
    // ...
}
```

### Anti-Patterns to Avoid

- **Database writes inside Flow collectors without guards:** Always check state before writing
- **Assuming `distinctUntilChanged()` prevents logic from running:** It only filters emissions, the collector still runs
- **No timeout on state transitions:** Always have a timeout to recover from stuck states
- **Synchronous state checks in async context:** State can change between check and action - use atomic transitions

## Don't Hand-Roll

Problems that look simple but have existing solutions:

| Problem | Don't Build | Use Instead | Why |
|---------|-------------|-------------|-----|
| Reactive database queries | Custom observers/listeners | Room Flow queries | Room's InvalidationTracker handles SQLite triggers correctly |
| Debounce/throttle timing | Manual delay() loops | Flow.debounce() operator | Handles cancellation and edge cases |
| State machine | Boolean flags | Sealed class/enum state | Type-safe, easier to test, catches invalid transitions |
| Exponential backoff | Manual calculation | Standard formula with cap | Well-tested, prevents overflow |

**Key insight:** The feedback loop is an architecture issue, not a missing library problem. The solution is design patterns (state machine, debounce), not new dependencies.

## Common Pitfalls

### Pitfall 1: Room's Broad Invalidation
**What goes wrong:** Room triggers Flow emissions for ANY row change in observed tables, not just the rows you care about

**Why it happens:** Room only tracks table-level changes via SQLite triggers. When the announces table has hundreds of propagation nodes updating frequently (every time they re-announce), `getAnnouncesByTypes()` Flow emits on EVERY change, even if the nearest relay didn't change.

**How to avoid:**
- Use `debounce()` on announce Flows to batch rapid changes
- Add state machine to prevent selection logic from running during cooldown
- Consider query-on-demand pattern (triggered by timer, not Flow) if Flow triggers too frequently

**Warning signs:**
- Logs show `observePropagationNodeAnnounces` called dozens of times per minute
- Same relay selected repeatedly with no hop count change
- CPU usage spikes correlating with network activity (announce reception)

### Pitfall 2: distinctUntilChanged Doesn't Prevent Collection
**What goes wrong:** Adding `.distinctUntilChanged()` on destinationHash prevents *duplicate emissions*, but doesn't prevent the collector code from running

**Why it happens:** Misunderstanding of Flow operators - `distinctUntilChanged()` filters what gets emitted downstream, but the collector lambda still executes for every upstream emission. If your collector has side effects (database writes), those happen even when values are filtered.

**How to avoid:**
- Guard database writes with state checks: `if (_selectionState.value != IDLE) return`
- Separate query logic from action logic
- Use state machine to control *whether to run selection logic at all*, not just filter results

**Warning signs:**
- `distinctUntilChanged()` added but loop continues
- Logs show collector running but same value filtered
- State checks inside collector, not before it

### Pitfall 3: Feedback Loops with Two-Way Reactive Flows
**What goes wrong:** Flow A observes database and updates it → triggers Flow B → Flow B updates database → triggers Flow A → infinite loop

**Why it happens:** Reactive programming encourages "always react to changes," but when two systems both react to each other's outputs, you get positive feedback.

**How to avoid:**
- Identify circular dependencies: Database → Flow → Database
- Break the loop with state: one system becomes "active" (can write), other becomes "passive" (read-only)
- Add cooldown periods after writes to prevent immediate re-trigger
- Use single-directional data flow where possible (Command → Database → View, not View → Database → View)

**Warning signs:**
- Logs show alternating operations (select relay A → clear relay → select relay A)
- Same function called from multiple code paths
- No clear "source of truth" - multiple systems trying to control the same state

### Pitfall 4: Missing Timeout on State Machine
**What goes wrong:** State machine gets stuck in SELECTING or BACKING_OFF forever if an exception occurs

**Why it happens:** State transitions assume success, but failures (network error, database locked, etc.) can leave state machine in non-IDLE state permanently.

**How to avoid:**
- Always start a timeout timer when entering non-IDLE state
- Timeout should transition back to IDLE after reasonable period (30-60 seconds)
- Use `withTimeout()` or separate coroutine job for timeout enforcement
- Log warning when timeout fires (indicates bug or unexpected condition)

**Warning signs:**
- Auto-selection stops working after first failure
- Manual selection works but auto-selection never triggers again
- State machine state is not IDLE but no activity happening

### Pitfall 5: Race Condition Between User Action and Auto-Selection
**What goes wrong:** User manually selects relay while auto-selection is in progress → both writes execute → unpredictable final state

**Why it happens:** User actions and background coroutines execute concurrently. Without explicit coordination, both can call `setAsMyRelay()` at nearly the same time.

**How to avoid:**
- User actions must check and cancel auto-selection state first
- Use single write queue (Channel/Mutex) if truly critical
- State machine helps: manual selection forces state to IDLE before writing
- Add "user action timestamp" to detect and ignore auto-selections older than last user action

**Warning signs:**
- User selects relay but different relay appears selected
- Logs show setAsMyRelay called twice with different hashes in rapid succession
- Relay selection "flickers" in UI

## Code Examples

Verified patterns from the existing codebase and Kotlin docs:

### Current observePropagationNodeAnnounces (has bug)
```kotlin
// Source: PropagationNodeManager.kt line 695
private suspend fun observePropagationNodeAnnounces() {
    announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE")).collect { propagationNodes ->
        val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()

        // BUG: This collector runs on EVERY announce table change
        // With 100+ propagation nodes, this can be 40+ times per minute
        // No guard to prevent selection during cooldown

        if (isAutoSelect && propagationNodes.isNotEmpty()) {
            val nearest = propagationNodes.minByOrNull { it.hops }
            if (nearest != null) {
                // This calls onPropagationNodeAnnounce, which calls setAsMyRelay,
                // which updates database, which triggers observeRelayChanges
                onPropagationNodeAnnounce(
                    nearest.destinationHash,
                    nearest.peerName,
                    nearest.hops,
                    nearest.publicKey,
                )
            }
        }
    }
}
```

### Fixed observePropagationNodeAnnounces (with state machine and debounce)
```kotlin
private suspend fun observePropagationNodeAnnounces() {
    announceRepository.getAnnouncesByTypes(listOf("PROPAGATION_NODE"))
        .debounce(1000) // Batch rapid changes from announce table
        .collect { propagationNodes ->
            // Guard: Don't select if already selecting or in cooldown
            if (_selectionState.value != RelaySelectionState.IDLE) {
                Log.d(TAG, "Skipping auto-select - state=${_selectionState.value}")
                return@collect
            }

            val isAutoSelect = settingsRepository.getAutoSelectPropagationNode()

            if (isAutoSelect && propagationNodes.isNotEmpty()) {
                _selectionState.value = RelaySelectionState.SELECTING

                val nearest = propagationNodes.minByOrNull { it.hops }
                if (nearest != null) {
                    Log.i(TAG, "Auto-selecting relay: ${nearest.peerName} (state=SELECTING)")

                    onPropagationNodeAnnounce(
                        nearest.destinationHash,
                        nearest.peerName,
                        nearest.hops,
                        nearest.publicKey,
                    )

                    // Mark stable and start cooldown
                    _selectionState.value = RelaySelectionState.STABLE

                    // Record for loop detection
                    recordSelection(nearest.destinationHash)

                    // Cooldown period before allowing next auto-selection
                    scope.launch {
                        delay(30_000) // 30 seconds
                        if (_selectionState.value == RelaySelectionState.STABLE) {
                            _selectionState.value = RelaySelectionState.IDLE
                            Log.d(TAG, "Relay selection cooldown complete - IDLE")
                        }
                    }
                }
            }
        }
}
```

### Loop Detection Implementation
```kotlin
// Source: New code based on context decisions (loop threshold: 3+ in 1 minute)
private val recentSelections = ArrayDeque<Pair<String, Long>>(maxSize = 10)

private suspend fun recordSelection(destinationHash: String) {
    val now = System.currentTimeMillis()
    recentSelections.addLast(destinationHash to now)
    if (recentSelections.size > 10) recentSelections.removeFirst()

    // Check for loop: 3+ different selections in 60 seconds
    val oneMinuteAgo = now - 60_000
    val recentCount = recentSelections.count { it.second > oneMinuteAgo }

    if (recentCount >= 3) {
        val hashes = recentSelections.filter { it.second > oneMinuteAgo }.map { it.first.take(12) }
        Log.w(TAG, "⚠️ Relay loop detected! $recentCount selections in 60s: $hashes")

        // Send Sentry event (per context decisions)
        // sentryLogger.captureEvent("relay_selection_loop", mapOf("count" to recentCount, "hashes" to hashes))

        // Enter backoff state
        _selectionState.value = RelaySelectionState.BACKING_OFF

        // Exponential backoff: 1s, 2s, 4s, 8s, ... max 10 minutes
        val backoffMs = minOf(
            2.0.pow((recentCount - 3).toDouble()).toLong() * 1000,
            600_000 // 10 minutes max
        )
        Log.w(TAG, "Entering backoff for ${backoffMs / 1000}s")

        scope.launch {
            delay(backoffMs)
            if (_selectionState.value == RelaySelectionState.BACKING_OFF) {
                _selectionState.value = RelaySelectionState.IDLE
                Log.i(TAG, "Backoff complete - IDLE")
            }
        }
    }
}
```

### User Action Precedence
```kotlin
// Source: PropagationNodeManager.kt line 524, needs enhancement
suspend fun setManualRelay(destinationHash: String, displayName: String) {
    Log.i(TAG, "User manually selected relay: $displayName")

    // NEW: Cancel any ongoing auto-selection
    if (_selectionState.value == RelaySelectionState.SELECTING) {
        Log.i(TAG, "User action during auto-select - cancelling auto-selection")
    }
    _selectionState.value = RelaySelectionState.IDLE // User action always resets to IDLE

    // Existing logic
    settingsRepository.saveAutoSelectPropagationNode(false)
    settingsRepository.saveManualPropagationNode(destinationHash)
    // ...
}
```

## State of the Art

| Old Approach | Current Approach | When Changed | Impact |
|--------------|------------------|--------------|--------|
| Polling database periodically | Room Flow queries | Room 2.0+ (2018) | More efficient, real-time updates, but requires feedback loop prevention |
| Boolean flags for state | Sealed class/enum state machines | Kotlin best practices (2020+) | Type-safe, catches invalid transitions at compile time |
| Manual debounce with delay() | Flow.debounce() operator | Kotlin Flow 1.3+ (2020) | Handles cancellation correctly, less boilerplate |
| LiveData for reactive streams | StateFlow/SharedFlow | Kotlin 1.4+ (2020) | Lifecycle-aware, better coroutine integration |

**Deprecated/outdated:**
- Using LiveData for service-layer state (2024+): StateFlow is now preferred for non-UI state
- Mutable exposed Flows (2023+): Should expose immutable StateFlow, keep MutableStateFlow private
- Collecting Flows in lifecycle-unaware scopes (2022+): Use repeatOnLifecycle for UI, ApplicationScope for services

## Open Questions

Things that couldn't be fully resolved:

1. **Exact trigger frequency of observePropagationNodeAnnounces**
   - What we know: Room triggers on ANY announce table change, regardless of row
   - What's unclear: How many propagation nodes exist in typical user's network? How often do they re-announce?
   - Recommendation: Add debug logging to count collector invocations per minute, adjust debounce timeout based on actual data

2. **Python-side relay state persistence**
   - What we know: `set_outbound_propagation_node()` updates LXMF router state
   - What's unclear: Does Python layer persist this across restarts? Could Python be clearing/resetting relay automatically?
   - Recommendation: Review reticulum_wrapper.py initialization - verify Python doesn't auto-clear relay on startup or network events

3. **Optimal cooldown duration**
   - What we know: Context decisions specify 15-30 second retry window for disconnect
   - What's unclear: Should cooldown match retry window, or be longer to prevent thrashing?
   - Recommendation: Start with 30 seconds (matches upper retry bound), monitor logs, adjust if too aggressive

4. **Network event triggers**
   - What we know: Relay should auto-select at startup and when current relay unreachable
   - What's unclear: How to detect "current relay unreachable" without triggering on every failed message?
   - Recommendation: Implement separate "relay health check" that runs every 5 minutes, only triggers selection if relay consistently failing

## Sources

### Primary (HIGH confidence)
- [Kotlin Flow distinctUntilChanged documentation](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/distinct-until-changed.html) - Official Kotlin docs on filtering duplicate emissions
- [Android Room with Flow guide](https://developer.android.com/codelabs/basic-android-kotlin-training-intro-room-flow) - Official Android guide on reactive database queries
- [Room InvalidationTracker behavior](https://medium.com/androiddevelopers/7-pro-tips-for-room-fbadea4bfbd1) - Android Developers blog on Room false positive notifications and distinctUntilChanged solution
- Columba codebase: PropagationNodeManager.kt, ContactRepository.kt, AnnounceDao.kt - Current implementation showing the feedback loop pattern

### Secondary (MEDIUM confidence)
- [Kotlin Flow debounce operator](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/debounce.html) - Official docs on timing-based filtering
- [Deep Dive: Debounce in Kotlin Coroutines Flow](https://androidengineers.substack.com/p/deep-dive-debounce-in-kotlin-coroutines) - Technical article on debounce implementation (Nov 2025)
- [StateFlow vs SharedFlow](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow) - Official Android guide on hot Flows for state management
- [Kotlin Flow: My 7 Favorite Patterns for Clean, Reactive Code](https://medium.com/@hiren6997/kotlin-flow-in-2025-my-7-favorite-patterns-for-clean-reactive-code-2dff57d09a3a) - Community patterns for reactive code (2025)

### Tertiary (LOW confidence)
- [Reactive Programming in Kotlin - StateFlow](https://dladukedev.com/articles/021_kotlin_stateflows/) - Community tutorial on StateFlow patterns
- [Controlling database flow using Room and RxJava2](https://tkolbusz.github.io/controlling-database-flow/) - Older article (RxJava era) but explains Room invalidation mechanics well

## Metadata

**Confidence breakdown:**
- Standard stack: HIGH - All components are official Android/Kotlin libraries, well-documented
- Architecture: HIGH - Feedback loop pattern is well-understood in reactive programming, multiple sources confirm Room's broad invalidation behavior
- Pitfalls: HIGH - All pitfalls derived from official documentation or confirmed by existing Columba code showing the problem
- Code examples: HIGH - Based on actual Columba code and official Kotlin Flow documentation

**Research date:** 2026-01-25
**Valid until:** 60 days (stable domain - reactive patterns and Room behavior unlikely to change)
**Key assumption:** The bug is architectural (feedback loop), not a library bug. If loop persists after implementing state machine, investigate Python layer for additional triggers.
