---
created: 2026-01-25T21:30
title: Refactor PropagationNodeManager to extract components
area: architecture
files:
  - app/src/main/java/com/lxmf/messenger/service/PropagationNodeManager.kt:130
---

## Problem

PropagationNodeManager has grown too large after adding relay loop fix features (state machine, loop detection, exclusion mechanism). Detekt flags it as `LargeClass` and we added a suppression to unblock the bug-fix PR.

The class now has multiple distinct responsibilities:
- Relay selection state machine (IDLE/SELECTING/STABLE/BACKING_OFF)
- Loop detection with exponential backoff
- Relay exclusion tracking
- Sync operations
- Settings persistence

## Solution

Extract into focused components:
1. `RelaySelectionStateMachine` - State transitions, guards, cooldown
2. `LoopDetector` - Track selections, detect loops, calculate backoff
3. Keep `PropagationNodeManager` as coordinator

After refactor, remove `@Suppress("LargeClass")` annotation.
