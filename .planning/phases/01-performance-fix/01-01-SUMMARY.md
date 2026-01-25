# Plan 01-01 Summary: Setup & Investigation

## Deliverables

### LeakCanary Integration
- Added `debugImplementation("com.squareup.leakcanary:leakcanary-android:2.13")` to app/build.gradle.kts
- Commit: `12f0c44e`
- LeakCanary auto-initializes in debug builds, no code changes required

### Profiling Session Completed
- 30+ minute soak test on Interface Discovery screen
- 4 heap dumps captured (baseline, T=15, T=20, T=30)
- Frame timing captured during scroll test
- LeakCanary monitoring active throughout

### FINDINGS.md Created
- Documents 3 issues discovered:
  1. **Native Memory Growth** (HIGH) - +19 MB in 14 minutes, in Python/Chaquopy layer
  2. **High Input Latency** (HIGH) - 6,108 delayed touch events
  3. **Janky Frames** (MEDIUM) - 3.66% janky, occasional 100ms+ spikes
- Proposed fixes and files to modify documented
- Heap dumps saved for detailed analysis

## Key Findings for Plan 02

1. **The memory leak is in NATIVE heap, not Java heap** - LeakCanary found nothing because the issue is in Python/Reticulum code
2. **Frame rendering is actually excellent** (90th percentile 9ms) - the UX issue is input latency, not rendering
3. **Input events are being delayed** - Something blocks touch processing before it reaches the UI framework
4. **The `:reticulum` process uses 250+ MB** - Significant memory footprint in Python layer

## Issues Encountered
- LeakCanary crashed app on first launch because a release APK was installed - resolved by installing debug variant
- Android Studio Profiler allocation tracking caused app to freeze - used lightweight adb-based profiling instead

## Verification
- [x] LeakCanary in debug dependencies: `./gradlew :app:dependencies | grep leakcanary`
- [x] FINDINGS.md contains Issues Found section
- [x] Memory profiling covers 30+ minute timeline
- [x] Each issue has proposed fix with file paths
