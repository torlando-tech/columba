---
created: 2026-01-25T09:48
title: Make discovered interfaces page event-driven (real-time updates)
area: ui
files:
  - app/src/main/java/com/lxmf/messenger/ui/announce/AnnounceStreamScreen.kt
  - app/src/main/java/com/lxmf/messenger/viewmodel/AnnounceStreamViewModel.kt
  - app/src/main/java/com/lxmf/messenger/ui/settings/NetworkInterfacesScreen.kt
---

## Problem

The Discovered Interfaces page and Network Interfaces page do not update in real-time when new data arrives:

- User has to back out to Settings and re-navigate to Discovery to see newly discovered interfaces
- Expected behavior: New interfaces should appear automatically as they're discovered
- Same issue affects the Network Interfaces page

This suggests the UI is not properly observing the data source (StateFlow/Flow not collected, or data not being emitted).

## Solution

1. Verify `AnnounceStreamViewModel.announces` Flow is being collected in `AnnounceStreamScreen`
2. Check if `announceRepository` properly emits updates when new announces arrive
3. Ensure Room database queries use `Flow<List<T>>` (reactive) not `suspend fun(): List<T>` (one-shot)
4. Same investigation needed for Network Interfaces page
5. May need to add `LaunchedEffect` or proper `collectAsState()` in Compose UI

**Quick diagnostic:** Check if `observeAnnounces()` in ViewModel is actually emitting to the UI layer.
