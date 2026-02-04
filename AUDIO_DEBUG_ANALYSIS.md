# LXST Audio Pipeline Debug Analysis

**Date:** 2026-02-03
**Build:** Debug with instrumentation installed

## Summary

Audio calls connect but audio is severely degraded:
- Very delayed (multiple seconds)
- Only fragments come through
- Frequent buffer underruns

## Instrumentation Results

### Device 1: Fold (10.0.0.249)

| Timestamp | Component | Data |
|-----------|-----------|------|
| 20:30:46 | MIX.h#1 | max=0.0000, qlen=1 **SILENT** |
| 20:30:50 | RX#1 | datalen=20 |
| 20:30:55 | MIX.h#51 | max=0.0006 (tiny) |
| 20:30:58 | MIX.j#51 | max=0.0881 **SOME AUDIO** |
| 20:31:08 | RX#51 | datalen=22 |
| 20:31:13 | MIX.j#101 | max=0.0174 |
| 20:31:19 | LS#51 | raw=0.0096 **VERY WEAK RECORDING** |
| 20:31:22 | PKT.tx#51 | len=30 codec=Opus |
| 20:31:23 | | **"No frames available"** |
| 20:31:23 | MIX.h#151 | max=0.0000 qlen=0 **SILENT** |

### Device 2: S21 (10.0.0.71)

| Timestamp | Component | Data |
|-----------|-----------|------|
| 20:30:48 | RX#1 | datalen=4 **TOO SMALL!** |
| 20:30:48 | MIX.h#1 | max=0.0000 **SILENT** |
| 20:30:53-20:31:31 | | **15+ "No frames available"** |
| 20:31:00 | MIX.h#51 | max=0.0000 **SILENT** |
| 20:31:06 | LS#51 | raw=0.0001 **ESSENTIALLY SILENT!** |
| 20:31:12 | MIX.h#101 | max=0.0625 **SOME AUDIO** |
| 20:31:20 | RX#51 | datalen=45 |
| 20:31:26 | LS#101 | raw=0.0964 filt=0.2096 **GOOD!** |
| 20:31:31 | PKT | **"link not active, dropping frame"** |

## Key Findings

### 1. Microphone Capture Problems

**S21 is INCONSISTENT:**
- Frame 51: `raw=0.0001` (silent)
- Frame 101: `raw=0.0964` → `filt=0.2096` (good audio after filtering)

**Fold is consistently WEAK:**
- Frame 51: `raw=0.0096` (barely above noise floor)

**Hypothesis:** The ChaquopyRecorder (Kotlin AudioRecord bridge) is not reliably capturing audio. The intermittent silence suggests:
- AudioRecord state issues
- Missing/delayed audio data from the recording queue
- Possible threading/timing issues with the LinkedBlockingQueue

### 2. Buffer Underrun (PRIMARY ISSUE)

"No frames available on LineSink" appears **15+ times** on S21 during a ~45 second call.

This means:
- Playback is consuming frames faster than they arrive
- Insufficient buffering for network jitter
- LXST's default MAX_FRAMES=8 is not enough

### 3. Packet Size Anomalies

S21 received `datalen=4` as the first packet - this is NOT a valid audio frame!
- Valid Opus frames are 10-100+ bytes
- 4 bytes could be signalling or header-only packet
- Need to investigate what's being sent

### 4. Link Instability

`📡 PKT: link not active, dropping frame` appeared at end of call.
This indicates the Reticulum link is becoming unstable during the call.

## Root Cause Hypothesis

The audio issues are caused by **multiple interacting problems**:

```
┌──────────────────────────────────────────────────────────────┐
│                    TRANSMIT SIDE                             │
│                                                              │
│  ChaquopyRecorder.record()                                   │
│         │                                                    │
│         ▼                                                    │
│  ┌─────────────────────────────────────────────┐            │
│  │ PROBLEM 1: Intermittent silence (0.0001)    │            │
│  │ Recording returns near-zero samples         │            │
│  └─────────────────────────────────────────────┘            │
│         │                                                    │
│         ▼                                                    │
│  LineSource → Filters → Gain → Opus Encode                  │
│         │                                                    │
│         ▼                                                    │
│  Mixer → Packetizer → Network                               │
└──────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────┐
│                    RECEIVE SIDE                              │
│                                                              │
│  Network → LinkSource._packet()                             │
│         │                                                    │
│         ▼                                                    │
│  ┌─────────────────────────────────────────────┐            │
│  │ PROBLEM 2: Packets arrive too slowly        │            │
│  │ 15+ buffer underruns during call            │            │
│  └─────────────────────────────────────────────┘            │
│         │                                                    │
│         ▼                                                    │
│  Opus Decode → Mixer → LineSink                             │
│         │                                                    │
│         ▼                                                    │
│  ┌─────────────────────────────────────────────┐            │
│  │ PROBLEM 3: LineSink buffer empties          │            │
│  │ "No frames available" → audio gaps          │            │
│  └─────────────────────────────────────────────┘            │
│         │                                                    │
│         ▼                                                    │
│  ChaquopyPlayer.play() → AudioTrack                         │
└──────────────────────────────────────────────────────────────┘
```

## Proposed Fixes

### Priority 1: Fix Recording Inconsistency

The S21 sometimes records silence (0.0001) and sometimes good audio (0.2096). This is the **transmit side** of the problem.

**Action:** Investigate `ChaquopyRecorder.record()`:
1. Check if AudioRecord is properly started
2. Verify the LinkedBlockingQueue isn't being starved
3. Add debug logging to see if bytes() conversion is working
4. Check for timing issues between Kotlin and Python threads

### Priority 2: Add Jitter Buffer

The playback buffer is emptying too quickly. LXST's default MAX_FRAMES=8 isn't enough.

**Action:** Add pre-buffering before playback starts:
```python
# In LineSink or receive pipeline:
# Wait for N frames before starting playback
JITTER_BUFFER_FRAMES = 4  # ~160ms at 40ms/frame
if len(buffer) < JITTER_BUFFER_FRAMES:
    # Don't start playback yet, wait for more frames
    pass
```

### Priority 3: Investigate Weak Recording on Fold

Fold consistently records very weak audio (0.0096 max). This could be:
- Microphone gain too low
- Wrong AudioSource (should be VOICE_COMMUNICATION)
- Hardware issue

**Action:** Check KotlinAudioBridge configuration on Fold.

### Priority 4: Investigate Link Stability

The link going inactive mid-call is concerning.

**Action:** Check Reticulum link management and keepalive.

## Files Modified for Instrumentation

1. `/python/lxst_modules/lxst_debug_instrumentation.py` - NEW (monkey-patches LXST)
2. `/python/lxst_modules/call_manager.py` - Added instrumentation import

## Next Steps

1. Keep instrumentation active for future debugging
2. Focus on fixing ChaquopyRecorder first (transmit side)
3. Then add jitter buffer (receive side)
4. Test incrementally after each fix
