# File Attachment Improvements Plan

## Status

| Phase | Description | Status | PR |
|-------|-------------|--------|-----|
| 1-5 | Configurable limits, warning banner, inbound protection | Complete | [#128](https://github.com/torlando-tech/columba/pull/128) |
| 6 | Large file permission request system | Planned | - |
| 7 | Settings UI | Planned | - |
| 8 | Progress tracking API | Planned | - |

## Summary
1. Remove hard-coded 512KB limit → configurable outbound AND inbound limits
2. Show inline warning banner for files >1MB (slow mesh transfer warning)
3. Implement permission/request system for very large files (>100MB)
4. Lay groundwork for progress tracking

## Key Research Findings

### LXMF Progress Tracking
LXMF internally uses RNS Resources for large messages (>319 bytes) and exposes a `progress` property (0.0-1.0) that can be polled.

- `LXMessage.progress` is updated via `__update_transfer_progress()` callback
- Progress formula: `0.10 + (resource.get_progress() * 0.90)`
- Can add `get_message_progress()` to Python wrapper to expose this

### RNS Resource Accept/Reject Mechanism
RNS already has infrastructure for permission-based file transfers:

- **`ResourceAdvertisement.read_size(packet)`** - query size BEFORE transfer
- **`Link.ACCEPT_APP`** strategy - app receives callback to decide accept/reject
- **`Resource.reject(packet)`** - reject transfer before it starts
- **`Resource.accept(packet, callback)`** - accept and begin transfer

### Sideband's Approach
- Default 128KB message limit (`default_lxm_limit = 128*1000`)
- Optional 1MB mode via config
- No permission/request system - just hard limits

### Security Concern
Without inbound limits, a malicious sender could fill recipient's storage with very large files. Need both:
1. Configurable hard limit for auto-reject
2. Permission system for files above a threshold (e.g., 100MB)

---

## Implementation Plan

### Phase 1: Add Configurable Limits to Settings (COMPLETE)

**File: `app/src/main/java/com/lxmf/messenger/repository/SettingsRepository.kt`**

Add TWO separate limits:

```kotlin
// In PreferencesKeys:
val MAX_OUTBOUND_ATTACHMENT_SIZE_KB = intPreferencesKey("max_outbound_attachment_size_kb")
val MAX_INBOUND_ATTACHMENT_SIZE_KB = intPreferencesKey("max_inbound_attachment_size_kb")
val LARGE_FILE_PERMISSION_THRESHOLD_KB = intPreferencesKey("large_file_permission_threshold_kb")

// Flows with defaults:
val maxOutboundAttachmentSizeKbFlow: Flow<Int> = ...  // default 8192 (8MB)
val maxInboundAttachmentSizeKbFlow: Flow<Int> = ...   // default 8192 (8MB)
val largeFilePermissionThresholdKbFlow: Flow<Int> = ... // default 102400 (100MB)
```

**Behavior:**
- **Outbound**: Warn at 1MB, enforce max at configurable limit
- **Inbound**: Auto-reject files exceeding limit
- **Permission threshold**: Files above this trigger permission request flow

### Phase 2: Update FileUtils for Dynamic Limits (COMPLETE)

**File: `app/src/main/java/com/lxmf/messenger/util/FileUtils.kt`**

1. Change constants to defaults:
   ```kotlin
   const val DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE = 8 * 1024 * 1024  // 8MB
   const val DEFAULT_MAX_SINGLE_FILE_SIZE = 8 * 1024 * 1024
   const val SLOW_TRANSFER_WARNING_THRESHOLD = 1024 * 1024  // 1MB
   ```

2. Add parameter to `wouldExceedSizeLimit()`:
   ```kotlin
   fun wouldExceedSizeLimit(
       currentTotal: Int,
       newFileSize: Int,
       maxTotalSize: Int = DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE
   ): Boolean
   ```

### Phase 3: Update MessagingViewModel (COMPLETE)

**File: `app/src/main/java/com/lxmf/messenger/viewmodel/MessagingViewModel.kt`**

1. In `addFileAttachment()`, fetch limit from settings:
   ```kotlin
   val maxSizeKb = settingsRepository.getMaxAttachmentSizeKb()
   val maxSizeBytes = maxSizeKb * 1024
   ```

2. Use dynamic limit in validation calls

3. Expose `maxAttachmentSizeBytes` as StateFlow for UI

### Phase 4: Add Inline Warning Banner (COMPLETE)

**File: `app/src/main/java/com/lxmf/messenger/ui/components/FileAttachmentPreviewRow.kt`**

1. Add new composable `SlowTransferWarningBanner`:
   ```kotlin
   @Composable
   private fun SlowTransferWarningBanner(modifier: Modifier = Modifier) {
       Surface(
           color = MaterialTheme.colorScheme.tertiaryContainer,
           shape = RoundedCornerShape(8.dp)
       ) {
           Row(...) {
               Icon(Icons.Default.Warning, ...)
               Column {
                   Text("Large file transfer")
                   Text("Files over 1 MB may transfer slowly on mesh networks")
               }
           }
       }
   }
   ```

2. Update `FileAttachmentPreviewRow` signature:
   ```kotlin
   fun FileAttachmentPreviewRow(
       attachments: List<FileAttachment>,
       totalSizeBytes: Int,
       onRemove: (Int) -> Unit,
       maxSizeBytes: Int = FileUtils.DEFAULT_MAX_TOTAL_ATTACHMENT_SIZE,
       modifier: Modifier = Modifier,
   )
   ```

3. Add warning banner display logic:
   ```kotlin
   val showSlowTransferWarning = totalSizeBytes > FileUtils.SLOW_TRANSFER_WARNING_THRESHOLD

   Column(...) {
       if (showSlowTransferWarning) {
           SlowTransferWarningBanner()
       }
       // existing Row with chips...
   }
   ```

### Phase 5: Inbound File Size Enforcement (COMPLETE)

**File: `python/reticulum_wrapper.py`**

Implement inbound file rejection at the LXMF level:

```python
def _on_lxmf_delivery(self, message):
    """Handle incoming LXMF message with size validation."""
    # Check file attachments size (Field 5 = FILE_ATTACHMENTS)
    if hasattr(message, 'fields') and message.fields and 5 in message.fields:
        attachments = message.fields[5]
        total_size = sum(len(f[1]) for f in attachments)  # f = [filename, data]

        if total_size > self.max_inbound_attachment_size:
            # Silent reject - drop the file attachments, keep the message
            log.warning(f"Rejected file attachments from {message.source_hash.hex()[:8]}: "
                       f"{total_size} bytes exceeds limit of {self.max_inbound_attachment_size}")
            del message.fields[5]
            # Continue processing message without attachments

    # Continue normal processing...
```

**Behavior:**
- Silent rejection - no notification to sender
- Log warning for debugging
- Strip file attachments but still deliver the message text

**Configuration flow:**
- Kotlin sends limit to Python via config JSON during initialization
- Python stores and checks on each incoming message

### Phase 6: Large File Permission Request System (PLANNED)

This is the most complex phase - implementing request/approval for files >100MB.

**New LXMF Field (custom):**
```python
FIELD_FILE_TRANSFER_REQUEST = 0xF0  # Custom field ID
FIELD_FILE_TRANSFER_RESPONSE = 0xF1
```

**Protocol Flow:**

1. **Sender wants to send 150MB file:**
   - Columba creates "file transfer request" message containing:
     - Filename
     - Size
     - MIME type
     - Hash of file (for verification)
   - Sends as regular LXMF message with `FIELD_FILE_TRANSFER_REQUEST`

2. **Recipient receives request:**
   - UI shows notification: "Alice wants to send 'video.mp4' (150 MB)"
   - User can Accept or Decline
   - Response sent via `FIELD_FILE_TRANSFER_RESPONSE`

3. **On Accept:**
   - Sender receives approval response
   - File transfer begins normally
   - Progress tracking shows transfer status

4. **On Decline:**
   - Sender receives decline response
   - UI shows "Transfer declined by recipient"

**Kotlin Side:**
- New data class `FileTransferRequest(id, senderHash, filename, sizeBytes, mimeType, fileHash)`
- New database table or in-memory store for pending requests
- UI notification for incoming requests
- Accept/Decline actions

**Python Side:**
- Handle `FIELD_FILE_TRANSFER_REQUEST` messages
- Forward requests to Kotlin via callback
- Wait for approval before sending large files

### Phase 7: Settings UI (PLANNED)

**File: New card or extend existing**

Settings for:
- Max outbound file size: dropdown (1MB, 2MB, 4MB, 8MB, 16MB, Unlimited)
- Max inbound file size: dropdown (same options)
- Large file permission threshold: dropdown (50MB, 100MB, 200MB, 500MB)

### Phase 8: Python Wrapper Progress API (PLANNED)

**File: `python/reticulum_wrapper.py`**

Add method to track outbound message progress:
```python
def get_message_progress(self, message_hash: str) -> Dict:
    """Get transfer progress for an outbound message."""
    if message_hash in self._outbound_messages:
        msg = self._outbound_messages[message_hash]
        return {
            "success": True,
            "progress": msg.progress,
            "state": msg.state,
        }
    return {"success": False, "error": "Message not found"}
```

---

## Files to Modify

| File | Changes | Status |
|------|---------|--------|
| `app/.../repository/SettingsRepository.kt` | Add outbound/inbound size limits + permission threshold | Complete |
| `app/.../util/FileUtils.kt` | Change hard-coded limits to defaults, add parameters | Complete |
| `app/.../viewmodel/MessagingViewModel.kt` | Use configurable limits, handle permission flow | Complete (limits only) |
| `app/.../ui/components/FileAttachmentPreviewRow.kt` | Add warning banner, accept maxSizeBytes param | Complete |
| `app/.../ui/screens/messaging/MessagingScreen.kt` | Pass maxSizeBytes to preview row | Complete |
| `python/reticulum_wrapper.py` | Inbound size validation, permission request/response handling | Complete (validation only) |
| `app/.../data/model/FileTransferRequest.kt` | New data class for transfer requests | Planned |
| `app/.../ui/components/FileTransferRequestDialog.kt` | New UI for accept/decline incoming requests | Planned |

---

## Unit Tests

### FileUtils Tests (COMPLETE)
- `wouldExceedSizeLimit with custom limit returns correct result`
- `SLOW_TRANSFER_WARNING_THRESHOLD is 1MB`

### SettingsRepository Tests (COMPLETE)
- `maxOutboundAttachmentSizeKbFlow defaults to 8192`
- `maxInboundAttachmentSizeKbFlow defaults to 8192`
- `largeFilePermissionThresholdKbFlow defaults to 102400`

### FileAttachmentPreviewRow Tests (PLANNED)
- `warning banner appears when total size exceeds 1MB`
- `warning banner does not appear when under 1MB`

### MessagingViewModel Tests (COMPLETE)
- `addFileAttachment uses configurable limit from settings`
- `rejects files exceeding configured limit`
- `files over permission threshold trigger request flow` (planned)

### Python Wrapper Tests (PLANNED)
- `incoming file exceeding limit is rejected`
- `file transfer request message is properly formatted`
- `file transfer response is handled correctly`

---

## Implementation Order

### Setup (COMPLETE)
0. Create new git worktree for `feature/file-transfer-improvements` branch

### Core (COMPLETE)
1. SettingsRepository - add all three settings
2. FileUtils - parameter-based limits
3. MessagingViewModel - outbound limit integration
4. FileAttachmentPreviewRow - warning banner
5. Unit tests for phases 1-4

### Inbound Protection (COMPLETE)
6. Python wrapper - inbound size validation
7. Kotlin IPC - pass limit to Python on init
8. Unit tests for inbound rejection

### Permission System (PLANNED - Separate PR)
9. Define custom LXMF fields for transfer request/response
10. Python - handle request/response messages
11. Kotlin - FileTransferRequest model + database table
12. Kotlin - UI for incoming transfer requests
13. Integration tests for full permission flow

### Settings UI + Progress (PLANNED - Future)
14. Settings UI card for all file transfer settings
15. Python wrapper progress API
16. UI progress indicators
