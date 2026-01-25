# Phase 2: Relay Loop Fix - Context

**Gathered:** 2026-01-25
**Status:** Ready for planning

<domain>
## Phase Boundary

Fix the relay auto-selection logic so it selects a relay once and stabilizes — no add/remove cycling behavior. Users should see a relay selected and it should stay selected until genuinely unreachable.

</domain>

<decisions>
## Implementation Decisions

### Selection Behavior
- **Trigger conditions:** Auto-select at app startup AND when current relay becomes unreachable
- **Stabilization:** Use BOTH state flag ("relay is stable") AND debounce timer as safety net
- **Priority:** First available relay wins (no sticky preference for previously used)
- **Retry on disconnect:** Wait 15-30 seconds before trying a different relay (allow transient hiccups to recover)
- **Debounce cooldown:** Fixed value, not configurable
- **User precedence:** If user manually selects during auto-selection, cancel auto-select immediately and use user's choice

### User Feedback
- **During selection:** Subtle indicator (spinner or status text) — non-intrusive
- **Indicator placement:** Claude's discretion
- **On failure:** Warning state only (visual yellow/orange) — no popup
- **On success:** No confirmation — indicator disappears, relay shows as selected seamlessly

### Edge Case Handling
- **No relays available:** Retry with exponential backoff, max interval 10 minutes
- **User manually unsets relay:** Respect user choice (stay unset), show warning in UI that no relay is set
- **No network at startup:** Fail fast, mark as "no relay", use backoff retry when network appears
- **User in settings during retry:** Continue background retry — don't pause

### Logging/Diagnostics
- **Production level:** Minimal (info level) — key events only
- **Log content:** Always include reason: "Relay selected (startup)", "Relay changed (previous unreachable)"
- **Loop detection:** Log warning locally AND send Sentry event if excessive changes detected
- **Loop threshold:** 3+ relay changes in 1 minute triggers warning

### Claude's Discretion
- Exact placement of subtle selection indicator
- Specific debounce timer value (within reasonable range)
- Implementation details for exponential backoff
- Exact retry timing values within 15-30 second window

</decisions>

<specifics>
## Specific Ideas

- The current bug shows 40+ add/remove cycles — fix must eliminate this completely
- Success criteria from roadmap: "single-selection behavior, not 40+ cycles"
- Exponential backoff for "no relays" case prevents hammering while still recovering eventually

</specifics>

<deferred>
## Deferred Ideas

None — discussion stayed within phase scope

</deferred>

---

*Phase: 02-relay-loop-fix*
*Context gathered: 2026-01-25*
