# Project Milestones: Columba

## v0.7.3 Bug Fixes (Shipped: 2026-01-28)

**Delivered:** Fixed performance degradation, relay selection loop, and three urgent UX bugs for a stable, responsive app experience.

**Phases completed:** 1-2.3 (11 plans total)

**Key accomplishments:**

- Implemented Sentry performance monitoring with ANR detection and frame timing tracking
- Fixed relay selection feedback loop with state machine, debouncing, and exponential backoff
- Fixed "Clear All Announces" to preserve contact announces (no more "Node not found" errors)
- Enabled offline map rendering indefinitely with cached MapLibre style JSON
- Optimized UI performance with @Stable annotations and background thread Python calls
- Implemented loading states for Chats and Contacts tabs (no more empty state flashes)

**Stats:**

- 107 files created/modified
- +11,634 / -1,084 lines of Kotlin
- 5 phases, 11 plans
- 4 days from start to ship (2026-01-25 → 2026-01-28)

**Git range:** `v0.7.2-beta` → `v0.7.3-beta`

**What's next:** v0.7.4 — Address deferred bugs (#338 duplicate notifications, #342 location permission) and investigate native memory growth

---
