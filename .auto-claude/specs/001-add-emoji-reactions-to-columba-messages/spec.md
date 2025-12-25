# Specification: Add Signal-Style Emoji Reactions to Columba Messages

## Overview

This feature adds Signal-style emoji reactions to Columba messages, leveraging the existing extended LXMF fields infrastructure (Field 16) that was recently implemented for the reply feature. Users will be able to long-press on a message to select an emoji reaction, which will be displayed as a small overlay on the message bubble. The implementation follows the established patterns from the reply feature, extending the Field 16 dictionary to include a `reactions` field.

## Workflow Type

**Type**: feature

**Rationale**: This is a new feature addition that builds upon existing infrastructure. The reply feature's architecture using Field 16 (app extensions dictionary) provides a clear pattern to follow. The implementation spans UI, data layer, and Python networking code, making it a multi-layer feature addition rather than a simple change.

## Task Scope

### Services Involved
- **app** (primary) - UI components, ViewModel, and message rendering
- **data** (primary) - Database entity and repository for reaction persistence
- **python** (integration) - LXMF message field handling for sending/receiving reactions

### This Task Will:
- [ ] Add reactions field to LXMF Field 16 structure in Python wrapper
- [ ] Extend MessageEntity to store reactions (or use existing fieldsJson)
- [ ] Add ReactionUi data class to MessageUi.kt
- [ ] Create ReactionComponents.kt with reaction picker and display components
- [ ] Add reaction parsing to MessageMapper.kt
- [ ] Integrate reaction UI into MessagingScreen.kt
- [ ] Add reaction state management to MessagingViewModel.kt
- [ ] Handle incoming reactions in Python polling and Kotlin callback

### Out of Scope:
- Reaction removal/undo functionality (can be added later)
- Custom emoji support beyond standard emoji set
- Reaction animations (keep initial implementation simple)
- Multi-user reaction aggregation (single user reactions for v1)
- Reaction history/notifications

## Service Context

### App Module

**Tech Stack:**
- Language: Kotlin
- Framework: Android (Jetpack Compose), Hilt for DI
- Key directories:
  - `app/src/main/java/com/lxmf/messenger/ui/` - UI components
  - `app/src/main/java/com/lxmf/messenger/viewmodel/` - ViewModels
  - `app/src/test/java/` - Unit tests (Robolectric)

**Entry Point:** `app/src/main/java/com/lxmf/messenger/MainActivity.kt`

**How to Run:**
```bash
./gradlew :app:installDebug
# or
./build-debug.sh && ./install-debug.sh
```

**Port:** N/A (Android app)

### Data Module

**Tech Stack:**
- Language: Kotlin
- Framework: Room (SQLite), Kotlin Coroutines
- Key directories:
  - `data/src/main/java/com/lxmf/messenger/data/db/entity/` - Entities
  - `data/src/main/java/com/lxmf/messenger/data/repository/` - Repositories

### Python Module

**Tech Stack:**
- Language: Python 3.11
- Framework: Reticulum Network Stack, LXMF Protocol
- Key files: `python/reticulum_wrapper.py`

**How to Run Tests:**
```bash
cd python && pytest
```

## Files to Modify

| File | Module | What to Change |
|------|--------|---------------|
| `app/src/main/java/com/lxmf/messenger/ui/model/MessageUi.kt` | app | Add `reactions: Map<String, List<String>>` field and `ReactionUi` data class |
| `app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt` | app | Add `parseReactionsFromField16()` function, update `toMessageUi()` |
| `app/src/main/java/com/lxmf/messenger/ui/screens/MessagingScreen.kt` | app | Add long-press handler, integrate reaction picker and display |
| `app/src/main/java/com/lxmf/messenger/viewmodel/MessagingViewModel.kt` | app | Add reaction state management and send reaction method |
| `python/reticulum_wrapper.py` | python | Handle reactions in Field 16 for send and receive |
| `app/src/main/aidl/com/lxmf/messenger/IReticulumService.aidl` | app | Add `sendReaction` method |
| `app/src/main/java/com/lxmf/messenger/service/binder/ReticulumServiceBinder.kt` | app | Implement `sendReaction` |

## Files to Create

| File | Module | Purpose |
|------|--------|---------|
| `app/src/main/java/com/lxmf/messenger/ui/components/ReactionComponents.kt` | app | Reaction picker dialog and reaction display overlay components |
| `app/src/test/java/com/lxmf/messenger/ui/components/ReactionComponentsTest.kt` | app | Unit tests for reaction UI components |
| `app/src/test/java/com/lxmf/messenger/ui/model/ReactionMapperTest.kt` | app | Unit tests for reaction parsing |

## Files to Reference

These files show patterns to follow:

| File | Pattern to Copy |
|------|----------------|
| `app/src/main/java/com/lxmf/messenger/ui/components/ReplyComponents.kt` | Component structure, gesture handling, Material3 theming |
| `app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt` | Field 16 JSON parsing pattern for `parseReplyToFromField16()` |
| `app/src/test/java/com/lxmf/messenger/ui/components/ReplyComponentsTest.kt` | Robolectric Compose test structure |
| `python/reticulum_wrapper.py` (lines 2811-2863) | Field 16 handling in Python for send/receive |

## Patterns to Follow

### Pattern 1: Field 16 App Extensions Structure

From `python/reticulum_wrapper.py`:

```python
# Field 16 is app extensions dict: {"reply_to": "...", "reactions": {...}, etc.}
# Add reactions alongside existing reply_to
app_extensions = {"reply_to": reply_to_message_id}
app_extensions["reactions"] = {"👍": ["sender_hash1", "sender_hash2"]}
fields[16] = app_extensions
```

**Key Points:**
- Field 16 is a dictionary that can contain multiple extension types
- Each extension is a key-value pair within the dict
- Already designed for extensibility (comment mentions reactions)

### Pattern 2: Parsing Field 16 in MessageMapper

From `app/src/main/java/com/lxmf/messenger/ui/model/MessageMapper.kt`:

```kotlin
@Suppress("SwallowedException", "ReturnCount")
private fun parseReplyToFromField16(fieldsJson: String?): String? {
    if (fieldsJson == null) return null
    return try {
        val fields = JSONObject(fieldsJson)
        val field16 = fields.optJSONObject("16") ?: return null
        if (field16.isNull("reply_to")) return null
        val replyTo = field16.optString("reply_to", "")
        replyTo.ifEmpty { null }
    } catch (e: Exception) {
        null
    }
}
```

**Key Points:**
- Use `optJSONObject` for null-safe access
- Handle missing keys gracefully
- Return null on any parsing error
- Use `@Suppress` for expected exception handling

### Pattern 3: Composable UI Components (ReplyComponents style)

From `app/src/main/java/com/lxmf/messenger/ui/components/ReplyComponents.kt`:

```kotlin
@Composable
fun ReplyPreviewBubble(
    replyPreview: ReplyPreviewUi,
    isFromMe: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = if (isFromMe) {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.6f)
    } else {
        MaterialTheme.colorScheme.primary
    }
    // ... themed Surface with content
}
```

**Key Points:**
- Take immutable data class as parameter
- Support `isFromMe` for color theming
- Use `MaterialTheme.colorScheme` for consistent theming
- Include `modifier: Modifier = Modifier` parameter

### Pattern 4: ViewModel State Management

From `app/src/main/java/com/lxmf/messenger/viewmodel/MessagingViewModel.kt`:

```kotlin
// Reply state - tracks which message is being replied to
private val _pendingReplyTo = MutableStateFlow<ReplyPreviewUi?>(null)
val pendingReplyTo: StateFlow<ReplyPreviewUi?> = _pendingReplyTo.asStateFlow()

fun setReplyTo(messageId: String) {
    viewModelScope.launch {
        // Load data, update state
        _pendingReplyTo.value = loadedPreview
    }
}
```

**Key Points:**
- Use `MutableStateFlow` for private mutable state
- Expose as `StateFlow` via `asStateFlow()`
- Launch coroutines in `viewModelScope`

## Requirements

### Functional Requirements

1. **Display Reactions on Messages**
   - Description: Show emoji reactions as small overlays below message bubbles
   - Acceptance: Reactions appear on messages that have them, positioned consistently

2. **Reaction Picker UI**
   - Description: Long-press on message shows emoji picker with common reactions (👍 ❤️ 😂 😮 😢 😡)
   - Acceptance: Picker appears on long-press, dismisses on selection or tap outside

3. **Send Reaction**
   - Description: Selecting an emoji sends a reaction message via LXMF
   - Acceptance: Reaction appears on target message after sending

4. **Receive Reactions**
   - Description: Incoming reaction messages update the target message's reaction display
   - Acceptance: Received reactions appear on correct message

5. **Reaction Persistence**
   - Description: Reactions are stored in database and survive app restart
   - Acceptance: Reactions persist after closing and reopening app

### Edge Cases

1. **Message Not Found** - Reaction received for unknown message ID: Log warning, ignore reaction
2. **Duplicate Reaction** - Same user sends same emoji again: Update timestamp, don't duplicate
3. **Invalid Emoji** - Non-standard emoji in reaction: Display as-is or fallback to placeholder
4. **Network Failure** - Reaction send fails: Show error toast, optionally retry

## Implementation Notes

### DO
- Follow the pattern in `ReplyComponents.kt` for component structure
- Reuse `SwipeableMessageBubble` pattern for long-press gesture detection
- Parse reactions from Field 16 similar to `parseReplyToFromField16()`
- Use `@Immutable` annotation on reaction data classes for Compose performance
- Write Robolectric tests for all new components (aim for 80%+ coverage)
- Run `detekt`, `ktlint`, and tests before committing

### DON'T
- Create separate LXMF message type for reactions (use Field 16)
- Store reactions in separate database table (use fieldsJson or message column)
- Add animations in initial implementation (keep simple)
- Modify existing test fixtures without good reason

## Data Model

### Reaction Field 16 Structure

```json
{
  "16": {
    "reply_to": "optional_message_id",
    "reactions": {
      "👍": ["sender_hash_1", "sender_hash_2"],
      "❤️": ["sender_hash_3"]
    }
  }
}
```

### ReactionUi Data Class

```kotlin
@Immutable
data class ReactionUi(
    val emoji: String,
    val senderHashes: List<String>,
    val count: Int = senderHashes.size,
)
```

### MessageUi Extension

```kotlin
data class MessageUi(
    // ... existing fields
    val reactions: List<ReactionUi> = emptyList(),
)
```

## Development Environment

### Start Services

```bash
# Build and install debug APK
./build-debug.sh && ./install-debug.sh

# Run unit tests
./gradlew :app:testDebugUnitTest

# Run all quality checks
./gradlew detekt ktlintCheck :app:testDebugUnitTest
```

### Required Environment Variables
- None required for development

## Success Criteria

The task is complete when:

1. [ ] Long-press on message opens reaction picker
2. [ ] Selecting emoji sends reaction via LXMF Field 16
3. [ ] Reactions display as emoji overlays on messages
4. [ ] Incoming reactions update message display
5. [ ] Reactions persist in database
6. [ ] No console errors
7. [ ] Existing tests still pass
8. [ ] New functionality verified via emulator/device
9. [ ] Code passes detekt and ktlint checks
10. [ ] New code has 80%+ test coverage

## QA Acceptance Criteria

**CRITICAL**: These criteria must be verified by the QA Agent before sign-off.

### Unit Tests
| Test | File | What to Verify |
|------|------|----------------|
| ReactionComponents display | `ReactionComponentsTest.kt` | Picker shows all emoji options, reaction overlay displays correctly |
| Reaction parsing | `MessageMapperTest.kt` | `parseReactionsFromField16()` handles all edge cases |
| Reaction state | `MessagingViewModelTest.kt` | Send reaction updates state correctly |

### Integration Tests
| Test | Services | What to Verify |
|------|----------|----------------|
| Reaction send flow | app ↔ python | AIDL call sends reaction, Python handles Field 16 |
| Reaction receive flow | python ↔ app | Python parses incoming reaction, callback updates UI |

### End-to-End Tests
| Flow | Steps | Expected Outcome |
|------|-------|------------------|
| Send Reaction | 1. Long-press message 2. Select 👍 3. Observe message | Reaction emoji appears on message |
| Receive Reaction | 1. Have another device send reaction 2. Observe message | Reaction appears on correct message |
| Multiple Reactions | 1. Send different reactions to same message | All reactions display with counts |

### Browser Verification (Emulator/Device)
| Page/Component | Checks |
|----------------|--------|
| MessagingScreen | Long-press shows picker, reactions display correctly |
| Reaction Picker | All 6 emoji options visible, selection works |
| Reaction Display | Emoji overlay positioned correctly on both sent/received messages |

### Database Verification
| Check | Query/Command | Expected |
|-------|---------------|----------|
| Reactions stored | `SELECT fieldsJson FROM messages WHERE id='...'` | Field 16 contains reactions dict |

### QA Sign-off Requirements
- [ ] All unit tests pass
- [ ] All integration tests pass
- [ ] All E2E tests pass
- [ ] Emulator verification complete
- [ ] Database state verified
- [ ] No regressions in existing functionality (replies, attachments)
- [ ] Code follows established patterns (ReplyComponents structure)
- [ ] No security vulnerabilities introduced
- [ ] Test coverage >= 80% for new code
