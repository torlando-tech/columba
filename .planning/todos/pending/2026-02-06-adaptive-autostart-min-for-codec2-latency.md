---
created: 2026-02-06T20:20
title: Adaptive AUTOSTART_MIN for Codec2 latency
area: performance
files:
  - lxst/src/main/java/tech/torlando/lxst/audio/LineSink.kt:38
  - lxst/src/main/java/tech/torlando/lxst/codec/Codec2.kt
---

## Problem

Codec2 profiles have significantly more playback latency than Opus due to
AUTOSTART_MIN being a fixed frame count (5) regardless of frame duration:

- Codec2: 5 frames x 200ms = 1000ms pre-buffer (a full second of latency)
- Opus MQ: 5 frames x 60ms = 300ms pre-buffer

Additional latency factors (inherent to Codec2, less actionable):
1. Larger frame sizes: 200ms vs 60ms adds ~140ms algorithmic latency per direction
2. Sample rate resampling: 8kHz codec requires 6:1 down/upsample from 48kHz mic/speaker

## Solution

Make AUTOSTART_MIN adaptive based on frame duration. Target a fixed time budget
(e.g., 300ms) rather than a fixed frame count:

```kotlin
val autostartFrames = max(2, (300L / frameTimeMs).toInt())
```

This would give:
- Codec2 (200ms frames): max(2, 300/200) = 2 frames = 400ms
- Opus MQ (60ms frames): max(2, 300/60) = 5 frames = 300ms (unchanged)

Needs careful testing — fewer pre-buffered frames means less jitter absorption,
which may cause more underruns on lossy Reticulum links.
