# RNS Patches for Columba

This directory contains patched versions of Reticulum Network Stack (RNS) files that fix resource management issues discovered during Columba development.

## Why Patches Are Needed

These patches address **real bugs in upstream RNS** that cause ResourceWarning messages visible in Chaquopy/Android environments. While these warnings are hidden by default in desktop Python, they indicate actual resource leaks that can exhaust file descriptors over time.

### Why We See The Warnings

- **Chaquopy 13.0+** enables ResourceWarning by default (since November 2022)
- This is intentional to help Android developers catch resource leaks early
- Desktop Python requires `-W default` flag to see these warnings
- Android has stricter file descriptor limits (typically 256-512 vs 1024+ on desktop)

### Root Cause

RNS uses the pattern `file = open(...); ...; file.close()` in multiple places. This is:
1. **Not exception-safe**: If an exception occurs before `.close()`, the file handle leaks
2. **Non-deterministic**: Files eventually close via garbage collection, but timing is unpredictable
3. **Against Python best practices**: Should use context managers (`with` statements)

## Patched Files

### 1. `RNS/Destination.py`

**Lines Fixed**: 216-218 (_persist_ratchets), 441-448 (_reload_ratchets)

**Problem**:
- Line 441: **No `.close()` at all** - file handle never explicitly closed (the ResourceWarning we saw)
- Line 216: Has `.close()` but not exception-safe

**Impact**:
- Ratchet files are loaded/saved during destination initialization
- Retry logic can leak 2 file handles per failure
- High-impact on LXMF router with many destinations

**Fix**:
```python
# Before (line 441 - NO close at all!):
ratchets_file = open(ratchets_path, "rb")
persisted_data = umsgpack.unpackb(ratchets_file.read())
# ... rest of function ...
# File never closed!

# After (exception-safe):
with open(ratchets_path, "rb") as ratchets_file:
    persisted_data = umsgpack.unpackb(ratchets_file.read())
    # ... rest of function ...
# File automatically closed, even on exception
```

### 2. `RNS/__init__.py`

**Lines Fixed**: 149-151 (log function)

**Problem**:
- Has `.close()` but not exception-safe
- Logging is called very frequently throughout RNS
- File handle leaks can accumulate quickly

**Impact**:
- Every log message to file opens a new file handle
- High-frequency logging scenarios (DEBUG/EXTREME levels) are especially vulnerable
- Can exhaust file descriptors under heavy logging

**Fix**:
```python
# Before (not exception-safe):
file = open(logfile, "a")
file.write(logstring+"\n")
file.close()  # If exception in write(), never reached

# After (exception-safe):
with open(logfile, "a") as file:
    file.write(logstring+"\n")
# File always closed, even on exception
```

## Additional Files With Issues (Not Patched Yet)

These files have similar issues but with **explicit `.close()` calls** (better than Destination.py:441, but still not exception-safe). Lower priority for patching:

### `RNS/Transport.py`
- Lines 187-190, 229-231, 276-278, 2933-2935
- All have explicit `.close()` calls
- Risk: Medium (not as critical as Destination.py)

### `RNS/Resource.py`
- Lines 689-691, 697-699, 719-721, 726-732
- All have explicit `.close()` calls
- Risk: Medium (resource assembly operations)

## Deployment Process

The patched files are deployed during Reticulum initialization:

1. Chaquopy pip installs RNS to: `app/build/python/pip/debug/common/RNS/`
2. ReticulumWrapper copies patches from: `python/patches/RNS/`
3. Patches override pip-installed files at runtime

See: `python/reticulum_wrapper.py` - `_deploy_rns_patches()` method

## Testing

After deploying patches, verify in logcat:

```bash
adb logcat | grep -E "ResourceWarning|unclosed file"
```

**Expected**: No ResourceWarning messages for Destination.py or __init__.py file operations

## Upstream Status

**Reported**: [TODO: Add GitHub issue link]

**Status**: Not yet reported to RNS project

**Reason**: Desktop users don't see warnings (disabled by default), so issue likely unknown

**Plan**:
1. Test patches thoroughly in Columba
2. Report issue to RNS GitHub with detailed explanation
3. Submit PR with fixes
4. Remove local patches when RNS upstream includes fix

## When To Remove These Patches

These patches can be safely removed when:
1. RNS upstream fixes the file handling issues
2. Columba updates to the fixed RNS version
3. Testing confirms ResourceWarnings are gone

## Technical Details

### Context Manager Pattern

Python's `with` statement uses the context manager protocol:

```python
with open(path, "mode") as file:
    # do stuff
# __exit__() always called here, closes file
```

**Guarantees**:
- File is closed when exiting the `with` block
- Works even if exception is raised
- Works even if `return` statement executed
- Cleaner, more Pythonic code

### Exception Safety

The original pattern fails if exception occurs:

```python
file = open(path)  # ← File opened
data = process(file.read())  # ← Exception here!
file.close()  # ← Never reached, file stays open
```

With context manager:

```python
with open(path) as file:  # ← File opened
    data = process(file.read())  # ← Exception here!
# ← File ALWAYS closed here via __exit__()
```

## References

- **Chaquopy Release Notes**: https://chaquo.com/chaquopy/doc/current/changelog.html#id63
  - Version 13.0.0 (2022-11-21): "Enable all warnings including ResourceWarning"
- **Python ResourceWarning**: https://docs.python.org/3/library/exceptions.html#ResourceWarning
- **Context Managers**: https://docs.python.org/3/reference/datamodel.html#context-managers
- **PEP 343**: https://peps.python.org/pep-0343/ (The "with" statement)

## Version Info

- **Created**: 2025-11-06
- **RNS Version**: Latest from pip (as of 2025-11-06)
- **Columba Version**: Development
- **Python**: 3.11 (Chaquopy)
- **Platform**: Android (Chaquopy environment)
