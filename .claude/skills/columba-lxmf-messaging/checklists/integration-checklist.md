# LXMF Integration Checklist

Use this checklist when integrating LXMF messaging into Columba to ensure all components are properly implemented and tested.

## Phase 1: Setup & Dependencies

### Build Configuration
- [ ] Add Chaquopy plugin to `build.gradle.kts`
- [ ] Set Python version to 3.11
- [ ] Add `lxmf` pip dependency (includes RNS)
- [ ] Add `msgpack` pip dependency
- [ ] Configure Chaquopy for release builds (if needed)
- [ ] Verify build completes successfully

### Python Module Structure
- [ ] Create `app/src/main/python/` directory
- [ ] Copy `lxmf_wrapper.py` template
- [ ] Create `event_emitter.py` (or use inline in wrapper)
- [ ] Create `__init__.py` files
- [ ] Verify Python module loads in Android app

### Directory Structure
- [ ] Create LXMF storage directory (app-private)
- [ ] Create identity storage location (app-private, backed up)
- [ ] Create Reticulum config directory
- [ ] Set proper file permissions
- [ ] Add to backup rules (identity critical!)

## Phase 2: Data Layer

### Room Database Entities
- [ ] Create `MessageEntity` with all required fields
  - [ ] id (LXM hash)
  - [ ] conversationHash (peer hash)
  - [ ] content
  - [ ] title
  - [ ] timestamp
  - [ ] isFromMe
  - [ ] state
  - [ ] method
  - [ ] packedLxm (ByteArray)
  - [ ] fields (JSON)
  - [ ] extras (JSON)
  - [ ] isRead
  - [ ] progress
- [ ] Create `ConversationEntity` with all required fields
  - [ ] peerHash (primary key)
  - [ ] peerName
  - [ ] lastMessage
  - [ ] lastMessageTimestamp
  - [ ] unreadCount
  - [ ] isTrusted
  - [ ] appearance (JSON)
  - [ ] sendTelemetry
- [ ] Create database migration (if existing DB)
- [ ] Add proper indices for performance
- [ ] Test database schema

### DAOs
- [ ] Create `MessageDao` with operations:
  - [ ] insert(message)
  - [ ] getMessagesForConversation(peerHash)
  - [ ] updateState(messageId, state)
  - [ ] updateProgress(messageId, progress)
  - [ ] markAsRead(messageId)
  - [ ] deleteMessage(messageId)
- [ ] Create `ConversationDao` with operations:
  - [ ] insert(conversation)
  - [ ] update(conversation)
  - [ ] getConversation(peerHash)
  - [ ] getAllConversations()
  - [ ] incrementUnread(peerHash)
  - [ ] resetUnread(peerHash)
  - [ ] deleteConversation(peerHash)
- [ ] Create Flow queries for reactive UI updates
- [ ] Test DAO operations

### Repository Layer
- [ ] Create `LxmfRepository`
- [ ] Inject Python module, DAOs, Context
- [ ] Implement `initialize()` with proper error handling
- [ ] Implement `sendMessage()` with database storage
- [ ] Implement `announce()`
- [ ] Implement `setPropagationNode()`
- [ ] Implement `requestPropagationMessages()`
- [ ] Use `Dispatchers.IO` for all Python calls
- [ ] Return `Result<T>` for error handling
- [ ] Test repository methods

## Phase 3: Event System

### Event Handler Setup
- [ ] Create `LxmfEventHandler` object
- [ ] Create sealed class `LxmfEvent` hierarchy
  - [ ] MessageReceived
  - [ ] DeliveryConfirmed
  - [ ] DeliveryFailed
  - [ ] PropagationProgress
- [ ] Implement JSON deserialization
- [ ] Create SharedFlow for event distribution
- [ ] Add error handling for malformed events
- [ ] Test event parsing

### Event Processor
- [ ] Create `MessageEventProcessor`
- [ ] Inject DAOs, NotificationManager
- [ ] Implement `handleMessageReceived()`
  - [ ] Store message in database
  - [ ] Create/update conversation
  - [ ] Increment unread count
  - [ ] Show notification
- [ ] Implement `handleDeliveryConfirmed()`
  - [ ] Update message state
  - [ ] Update progress to 1.0
- [ ] Implement `handleDeliveryFailed()`
  - [ ] Update message state
  - [ ] Optional: retry logic
- [ ] Implement `handlePropagationProgress()`
  - [ ] Update UI state
- [ ] Test event processing

### Application Initialization
- [ ] Initialize Python in `Application.onCreate()`
- [ ] Register event callback BEFORE initializing LXMF
- [ ] Initialize LXMF in background (Dispatchers.IO)
- [ ] Collect events in Application scope
- [ ] Route events to processor
- [ ] Test initialization flow

## Phase 4: UI Layer

### ViewModel
- [ ] Create `MessageViewModel`
- [ ] Inject `LxmfRepository`, `MessageDao`, `ConversationDao`
- [ ] Expose message Flow for current conversation
- [ ] Implement `sendMessage()` function
- [ ] Implement `loadMoreMessages()` for pagination
- [ ] Implement `markAsRead()` function
- [ ] Handle send errors gracefully
- [ ] Maintain UI state (sending, error, etc.)
- [ ] Test ViewModel logic

### Compose UI
- [ ] Create `MessageScreen` composable
- [ ] Display message list (LazyColumn)
- [ ] Implement message bubbles (sent/received)
- [ ] Add message input field
- [ ] Add send button with loading state
- [ ] Show delivery status (sending, delivered, failed)
- [ ] Handle keyboard (IME) properly
- [ ] Implement scroll-to-bottom on new message
- [ ] Show timestamps
- [ ] Handle long press for message actions
- [ ] Test UI interactions

### Conversation List
- [ ] Create `ConversationListScreen`
- [ ] Display conversations sorted by timestamp
- [ ] Show unread count badges
- [ ] Show last message preview
- [ ] Handle conversation selection
- [ ] Implement pull-to-refresh (optional)
- [ ] Test navigation

## Phase 5: Advanced Features

### Propagation Node Support
- [ ] Add propagation node settings UI
- [ ] Implement node configuration (hash input)
- [ ] Add "Sync Messages" button/action
- [ ] Show sync progress (state, percentage)
- [ ] Handle sync errors
- [ ] Periodic background sync (optional)
- [ ] Test propagation sync

### Message Fields Support
- [ ] Implement attachment sending
- [ ] Implement image sending (FIELD_IMAGE)
- [ ] Implement audio messages (FIELD_AUDIO)
- [ ] Display received attachments
- [ ] Display received images
- [ ] Play received audio
- [ ] Test field serialization

### Delivery Preferences
- [ ] Add delivery method settings (per-conversation or global)
- [ ] Implement "Try propagation on fail" toggle
- [ ] Implement stamp cost configuration
- [ ] Implement delivery ticket inclusion logic
- [ ] Test delivery method selection

### Identity Management
- [ ] Display user's own destination hash
- [ ] Implement identity backup mechanism
- [ ] Implement identity restore
- [ ] Add identity sharing (QR code, text)
- [ ] Test identity persistence

## Phase 6: Testing

### Unit Tests
- [ ] Test `LxmfRepository` with mocked Python module
- [ ] Test `MessageViewModel` with mocked repository
- [ ] Test `MessageEventProcessor` with fake events
- [ ] Test DAOs with in-memory database
- [ ] Test event JSON serialization/deserialization
- [ ] All unit tests pass

### Integration Tests
- [ ] Test Python wrapper initialization
- [ ] Test send message flow (Python → Database)
- [ ] Test receive message flow (Python → Database → UI)
- [ ] Test delivery confirmation flow
- [ ] Test propagation sync
- [ ] Test event end-to-end
- [ ] All integration tests pass

### Manual Testing
- [ ] Install app on physical device
- [ ] Initialize LXMF successfully
- [ ] Send message to self (loopback test)
- [ ] Verify message received
- [ ] Verify delivery confirmation
- [ ] Test multi-device messaging
- [ ] Test propagation node sync
- [ ] Test app backgrounding/foregrounding
- [ ] Test notifications
- [ ] Test database persistence across restarts
- [ ] Test identity persistence across reinstalls (with backup)

## Phase 7: Performance & Optimization

### Performance Checks
- [ ] Python call times < 100ms (measured)
- [ ] Event processing < 50ms (measured)
- [ ] Database writes < 100ms (measured)
- [ ] Message list scrolls at 60 FPS (visual check)
- [ ] Send button responds < 200ms (visual check)
- [ ] No ANRs during Python calls (StrictMode check)
- [ ] No main thread violations (StrictMode check)

### Memory & Battery
- [ ] No memory leaks (LeakCanary check)
- [ ] Reasonable memory usage (< 100MB for messaging)
- [ ] Battery usage acceptable (< 5%/hr background)
- [ ] Python module shutdown on app close
- [ ] Reticulum cleanup proper

### Optimization Opportunities
- [ ] Lazy-load Python module (don't init on app start)
- [ ] Implement message pagination (load 50 at a time)
- [ ] Use message caching (avoid redundant DB queries)
- [ ] Optimize event processing (batch if needed)
- [ ] Profile and optimize hot paths

## Phase 8: Error Handling & Edge Cases

### Error Scenarios
- [ ] Handle "LXMF not initialized" gracefully
- [ ] Handle "Unknown destination" (show path request)
- [ ] Handle send failures (show retry option)
- [ ] Handle propagation node unreachable
- [ ] Handle corrupted identity file
- [ ] Handle database migration failures
- [ ] Handle Python exceptions (don't crash app)
- [ ] Handle malformed events (log and skip)

### Edge Cases
- [ ] Empty message content (validation)
- [ ] Very long messages (> 128KB, warn user)
- [ ] Rapid message sending (rate limiting?)
- [ ] Receiving messages while app closed (notifications)
- [ ] Network changes (airplane mode, WiFi ↔ cellular)
- [ ] Storage full (handle gracefully)
- [ ] Low memory conditions (Android kills app)

### User Feedback
- [ ] Show loading states for all async operations
- [ ] Show error messages with actionable info
- [ ] Provide retry mechanisms where appropriate
- [ ] Show network status (connected, offline, syncing)
- [ ] Toast/snackbar for important events

## Phase 9: Security & Privacy

### Security Checks
- [ ] Identity stored in app-private directory
- [ ] Identity included in backup (encrypted by Android)
- [ ] No sensitive data in logs (use redaction)
- [ ] Validate message signatures (done by LXMF)
- [ ] Proper random number generation (RNS handles)
- [ ] No hardcoded credentials/keys

### Privacy
- [ ] No analytics/tracking without consent
- [ ] No cloud backup of messages (optional setting)
- [ ] Clear conversation data option
- [ ] Delete identity option (with warning)

## Phase 10: Documentation & Polish

### Code Documentation
- [ ] KDoc for all public classes/functions
- [ ] Python docstrings for all module functions
- [ ] README for Python module
- [ ] Architecture documentation (ADR)

### User Documentation
- [ ] In-app help/tutorial
- [ ] FAQ for common issues
- [ ] Troubleshooting guide

### Polish
- [ ] Consistent Material 3 theming
- [ ] Smooth animations
- [ ] Haptic feedback (optional)
- [ ] Accessibility (content descriptions, TalkBack)
- [ ] Edge-to-edge display (Android 15)
- [ ] Dark mode support
- [ ] Landscape orientation support

## Final Checks

- [ ] All checklists above completed
- [ ] Code reviewed by another developer
- [ ] No lint warnings/errors
- [ ] No StrictMode violations
- [ ] All tests pass (unit + integration + manual)
- [ ] Performance targets met
- [ ] Security review completed
- [ ] User testing completed (feedback addressed)
- [ ] Ready for production deployment

## Post-Launch Monitoring

- [ ] Monitor crash reports (Firebase Crashlytics?)
- [ ] Monitor ANR reports (Play Console)
- [ ] Monitor user feedback
- [ ] Track messaging success rates
- [ ] Identify and fix issues
- [ ] Plan next iteration improvements

---

**Checklist Version**: 1.0.0
**Last Updated**: 2025-10-30
**Target**: Columba LXMF Messaging Integration
