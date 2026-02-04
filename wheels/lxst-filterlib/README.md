# LXST Filterlib Wheels for Android

Pre-compiled native filter library for LXST voice calls.

## Why This Is Needed

LXST's audio filters (high-pass, low-pass, AGC) are implemented in C for performance.
Without native compilation, LXST falls back to pure Python/numpy which is 10-20x too slow
for real-time audio processing (400-900ms per frame vs 60ms frame time).

## Building

Wheels are built automatically via GitHub Actions in the LXST repository:
https://github.com/torlando-tech/LXST

To trigger a build:
```bash
cd /path/to/LXST
git tag filterlib-v1.0.0
git push origin filterlib-v1.0.0
```

## Manual Download

Download pre-built wheels from LXST releases and place them here:
- `lxst_filterlib-1.0.0-cp311-cp311-android_21_arm64_v8a.whl`
- `lxst_filterlib-1.0.0-cp311-cp311-android_21_x86_64.whl`

## Installation

The wheels are installed via Chaquopy's pip configuration in `app/build.gradle.kts`.
