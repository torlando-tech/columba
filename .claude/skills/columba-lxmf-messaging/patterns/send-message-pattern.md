# Send Message Pattern

This pattern demonstrates the complete end-to-end workflow for sending an LXMF message in Columba, from user tap to delivery confirmation.

## Overview

```
User Taps Send
      ↓
 UI (Compose)
      ↓
  ViewModel
      ↓
  Repository (Dispatchers.IO)
      ↓
Python Module (Chaquopy)
      ↓
 LXMF Router
      ↓
Reticulum Network
      ↓
Remote Peer
      ↓
Delivery Callback (Python)
      ↓
Event → Kotlin → Database
      ↓
Flow → UI Update
```

## Complete Implementation

### 1. Compose UI Layer

```kotlin
// MessageScreen.kt
@Composable
fun MessageScreen(
    conversationHash: String,
    viewModel: MessageViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState()
    val sendState by viewModel.sendState.collectAsState()
    var messageText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()  // Handle keyboard
    ) {
        // Message list
        LazyColumn(
            modifier = Modifier.weight(1f),
            reverseLayout = true  // Latest at bottom
        ) {
            items(messages, key = { it.id }) { message ->
                MessageBubble(message = message)
            }
        }

        // Input area
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = { messageText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                maxLines = 4
            )

            Spacer(modifier = Modifier.width(8.dp))

            // Send button
            IconButton(
                onClick = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(messageText)
                        messageText = ""
                    }
                },
                enabled = messageText.isNotBlank() && sendState !is SendState.Sending
            ) {
                when (sendState) {
                    is SendState.Sending -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp)
                        )
                    }
                    else -> {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = "Send"
                        )
                    }
                }
            }
        }

        // Error snackbar (if send failed)
        if (sendState is SendState.Error) {
            Snackbar(
                action = {
                    TextButton(onClick = { viewModel.retryLastMessage() }) {
                        Text("Retry")
                    }
                }
            ) {
                Text((sendState as SendState.Error).message)
            }
        }
    }
}

@Composable
fun MessageBubble(message: MessageEntity) {
    val alignment = if (message.isFromMe) Alignment.End else Alignment.Start
    val backgroundColor = if (message.isFromMe) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.secondaryContainer
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = alignment
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .background(backgroundColor, shape = RoundedCornerShape(12.dp))
                .padding(12.dp)
        ) {
            Text(
                text = message.content,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                horizontalArrangement = Arrangement.End,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                if (message.isFromMe) {
                    Spacer(modifier = Modifier.width(4.dp))
                    DeliveryStatusIcon(state = message.state)
                }
            }
        }
    }
}

@Composable
fun DeliveryStatusIcon(state: Int) {
    val (icon, tint) = when (state) {
        MessageState.OUTBOUND.value -> Icons.Default.Schedule to Color.Gray
        MessageState.SENDING.value -> Icons.Default.Schedule to Color.Gray
        MessageState.DELIVERED.value -> Icons.Default.DoneAll to MaterialTheme.colorScheme.primary
        MessageState.FAILED.value -> Icons.Default.Error to MaterialTheme.colorScheme.error
        else -> Icons.Default.Schedule to Color.Gray
    }

    Icon(
        imageVector = icon,
        contentDescription = "Delivery status",
        modifier = Modifier.size(12.dp),
        tint = tint
    )
}
```

### 2. ViewModel Layer

```kotlin
// MessageViewModel.kt
@HiltViewModel
class MessageViewModel @Inject constructor(
    private val lxmfRepository: LxmfRepository,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    private val conversationHash: String =
        savedStateHandle.get<String>("conversationHash")!!

    // Message list for current conversation
    val messages: StateFlow<List<MessageEntity>> = messageDao
        .getMessagesForConversation(conversationHash)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Send state
    private val _sendState = MutableStateFlow<SendState>(SendState.Idle)
    val sendState: StateFlow<SendState> = _sendState.asStateFlow()

    private var lastFailedMessage: String? = null

    fun sendMessage(content: String) {
        viewModelScope.launch {
            _sendState.value = SendState.Sending

            val result = lxmfRepository.sendMessage(
                destinationHash = conversationHash,
                content = content,
                method = DeliveryMethod.DIRECT
            )

            result.fold(
                onSuccess = { messageId ->
                    _sendState.value = SendState.Success
                    // Message already stored in database by repository
                    // UI will update automatically via Flow
                },
                onFailure = { error ->
                    _sendState.value = SendState.Error(
                        error.message ?: "Failed to send message"
                    )
                    lastFailedMessage = content
                }
            )
        }
    }

    fun retryLastMessage() {
        lastFailedMessage?.let { content ->
            sendMessage(content)
        }
    }

    fun markAllAsRead() {
        viewModelScope.launch {
            messages.value.filter { !it.isRead && !it.isFromMe }.forEach { message ->
                messageDao.markAsRead(message.id)
            }
            conversationDao.resetUnread(conversationHash)
        }
    }
}

sealed class SendState {
    object Idle : SendState()
    object Sending : SendState()
    object Success : SendState()
    data class Error(val message: String) : SendState()
}

enum class MessageState(val value: Int) {
    GENERATING(0x00),
    OUTBOUND(0x01),
    SENDING(0x02),
    SENT(0x04),
    DELIVERED(0x08),
    REJECTED(0xFD),
    CANCELLED(0xFE),
    FAILED(0xFF)
}
```

### 3. Repository Layer

```kotlin
// LxmfRepository.kt
class LxmfRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) {
    private val pythonModule by lazy {
        Python.getInstance().getModule("lxmf_wrapper")
    }

    suspend fun sendMessage(
        destinationHash: String,
        content: String,
        title: String = "",
        method: DeliveryMethod = DeliveryMethod.DIRECT,
        fields: Map<String, Any> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            // Validate input
            if (content.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Message content cannot be empty")
                )
            }

            // Check message size
            val contentBytes = content.toByteArray(Charsets.UTF_8)
            if (contentBytes.size > 128 * 1024) {  // 128KB limit
                return@withContext Result.failure(
                    IllegalArgumentException("Message too large (max 128KB)")
                )
            }

            // Serialize fields to JSON
            val fieldsJson = Json.encodeToString(fields)

            // Call Python wrapper
            val resultJson = pythonModule.callAttr(
                "send_message",
                destinationHash,
                content,
                title,
                method.value,
                fieldsJson,
                false  // include_ticket
            ).toString()

            // Parse result
            val result = Json.decodeFromString<SendMessageResult>(resultJson)

            if (result.success) {
                // Store in database
                val message = MessageEntity(
                    id = result.messageId!!,
                    conversationHash = destinationHash,
                    content = content,
                    title = title,
                    timestamp = (result.timestamp!! * 1000).toLong(),  // Convert to ms
                    isFromMe = true,
                    state = result.state!!,
                    method = method.value,
                    packedLxm = result.packedMessage!!.hexToByteArray(),
                    fields = fieldsJson,
                    progress = 0f
                )
                messageDao.insert(message)

                // Update conversation
                updateConversationAfterSend(destinationHash, content)

                Log.d("LxmfRepository", "Message sent: ${result.messageId}")
                Result.success(result.messageId)
            } else {
                Log.e("LxmfRepository", "Send failed: ${result.error}")
                Result.failure(
                    when (result.errorCode) {
                        "NOT_INITIALIZED" -> LxmfNotInitializedException()
                        "UNKNOWN_DESTINATION" -> UnknownDestinationException()
                        "INVALID_METHOD" -> IllegalArgumentException(result.error)
                        else -> Exception(result.error)
                    }
                )
            }
        } catch (e: PythonException) {
            Log.e("LxmfRepository", "Python error during send", e)
            Result.failure(e)
        } catch (e: SerializationException) {
            Log.e("LxmfRepository", "JSON parsing error", e)
            Result.failure(e)
        } catch (e: Exception) {
            Log.e("LxmfRepository", "Unexpected error during send", e)
            Result.failure(e)
        }
    }

    private suspend fun updateConversationAfterSend(
        destinationHash: String,
        content: String
    ) {
        val existing = conversationDao.getConversation(destinationHash)
        val now = System.currentTimeMillis()

        if (existing != null) {
            conversationDao.update(
                existing.copy(
                    lastMessage = content.take(100),
                    lastMessageTimestamp = now
                )
            )
        } else {
            // Create new conversation
            conversationDao.insert(
                ConversationEntity(
                    peerHash = destinationHash,
                    peerName = "Unknown",  // Will be updated when we learn name
                    lastMessage = content.take(100),
                    lastMessageTimestamp = now,
                    unreadCount = 0
                )
            )
        }
    }
}

enum class DeliveryMethod(val value: Int) {
    OPPORTUNISTIC(1),
    DIRECT(2),
    PROPAGATED(3)
}

@Serializable
data class SendMessageResult(
    val success: Boolean,
    val messageId: String? = null,
    val packedMessage: String? = null,
    val timestamp: Double? = null,
    val state: Int? = null,
    val error: String? = null,
    val errorCode: String? = null
)

class LxmfNotInitializedException : Exception("LXMF not initialized")
class UnknownDestinationException : Exception("Unknown destination. Path requested.")

// Extension function
fun String.hexToByteArray(): ByteArray {
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
```

### 4. Event Processing (Delivery Confirmation)

```kotlin
// Application.kt or MessageEventProcessor.kt
class MessageEventProcessor @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val notificationManager: ColumbaNotificationManager
) {

    init {
        // Collect events from Python
        CoroutineScope(Dispatchers.IO).launch {
            LxmfEventHandler.events.collect { event ->
                processEvent(event)
            }
        }
    }

    private suspend fun processEvent(event: LxmfEvent) {
        when (event) {
            is LxmfEvent.DeliveryConfirmed -> handleDeliveryConfirmed(event)
            is LxmfEvent.DeliveryFailed -> handleDeliveryFailed(event)
            is LxmfEvent.MessageReceived -> handleMessageReceived(event)
            is LxmfEvent.PropagationProgress -> handlePropagationProgress(event)
        }
    }

    private suspend fun handleDeliveryConfirmed(event: LxmfEvent.DeliveryConfirmed) {
        withContext(Dispatchers.IO) {
            messageDao.updateState(event.messageId, event.state)
            messageDao.updateProgress(event.messageId, 1.0f)
            Log.d("MessageEventProcessor", "Message delivered: ${event.messageId}")
        }
    }

    private suspend fun handleDeliveryFailed(event: LxmfEvent.DeliveryFailed) {
        withContext(Dispatchers.IO) {
            messageDao.updateState(event.messageId, event.state)
            Log.e("MessageEventProcessor", "Message failed: ${event.messageId}")
        }
    }

    private suspend fun handleMessageReceived(event: LxmfEvent.MessageReceived) {
        // (Implementation in receive-message-pattern.md)
    }

    private suspend fun handlePropagationProgress(event: LxmfEvent.PropagationProgress) {
        Log.d("MessageEventProcessor", "Propagation sync: ${event.progress * 100}%")
    }
}
```

### 5. DAO Layer

```kotlin
// MessageDao.kt
@Dao
interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT * FROM messages WHERE conversationHash = :peerHash ORDER BY timestamp DESC")
    fun getMessagesForConversation(peerHash: String): Flow<List<MessageEntity>>

    @Query("UPDATE messages SET state = :state WHERE id = :messageId")
    suspend fun updateState(messageId: String, state: Int)

    @Query("UPDATE messages SET progress = :progress WHERE id = :messageId")
    suspend fun updateProgress(messageId: String, progress: Float)

    @Query("UPDATE messages SET isRead = 1 WHERE id = :messageId")
    suspend fun markAsRead(messageId: String)

    @Query("DELETE FROM messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: String)

    @Query("SELECT * FROM messages WHERE id = :messageId")
    suspend fun getMessage(messageId: String): MessageEntity?
}

// ConversationDao.kt
@Dao
interface ConversationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(conversation: ConversationEntity)

    @Update
    suspend fun update(conversation: ConversationEntity)

    @Query("SELECT * FROM conversations WHERE peerHash = :peerHash")
    suspend fun getConversation(peerHash: String): ConversationEntity?

    @Query("SELECT * FROM conversations ORDER BY lastMessageTimestamp DESC")
    fun getAllConversations(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET unreadCount = unreadCount + 1 WHERE peerHash = :peerHash")
    suspend fun incrementUnread(peerHash: String)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE peerHash = :peerHash")
    suspend fun resetUnread(peerHash: String)

    @Query("DELETE FROM conversations WHERE peerHash = :peerHash")
    suspend fun deleteConversation(peerHash: String)
}
```

## Flow Summary

### Happy Path (Success)

1. **User taps send** → MessageScreen
2. **ViewModel.sendMessage()** called
3. **SendState** → `Sending` (UI shows loading)
4. **Repository.sendMessage()** → `Dispatchers.IO`
5. **Python wrapper** called via Chaquopy
6. **LXMF creates message**, registers callbacks, sends via router
7. **Python returns** success with message ID
8. **Repository stores** message in database (state = OUTBOUND)
9. **SendState** → `Success`
10. **Flow updates** → UI shows message bubble with "sending" icon
11. **Network delivery** occurs asynchronously
12. **Python delivery callback** fires
13. **Event emitted** → `DeliveryConfirmed`
14. **Event processor** updates database (state = DELIVERED)
15. **Flow updates** → UI shows "delivered" icon (✓✓)

### Error Path (Failure)

1. Steps 1-6 same as above
2. **Python returns** error (e.g., "UNKNOWN_DESTINATION")
3. **SendState** → `Error("Unknown destination")`
4. **UI shows** error snackbar with "Retry" button
5. **User taps retry** → Repeat from step 1

### Alternative: Delivery Failed

1. Steps 1-10 same as happy path
2. **Network delivery fails** (timeout, link failure, etc.)
3. **Python failed callback** fires
4. **Event emitted** → `DeliveryFailed`
5. **Event processor** updates database (state = FAILED)
6. **Flow updates** → UI shows "failed" icon (❌)
7. **User can retry** manually

## Threading Diagram

```
┌─ Main Thread ────────────────────────────┐
│  Compose UI                              │
│    ↓ (user action)                       │
│  ViewModel.sendMessage()                 │
│    ↓ (launches coroutine)                │
└──────────────────────────────────────────┘
         ↓
┌─ Dispatchers.IO ─────────────────────────┐
│  Repository.sendMessage()                │
│    ↓ (withContext)                       │
│  Python Module Call (Chaquopy JNI)       │
│    ↓                                     │
└──────────────────────────────────────────┘
         ↓
┌─ Python Thread (Chaquopy) ───────────────┐
│  lxmf_wrapper.send_message()             │
│    ↓ (with lock)                         │
│  LXMF.LXMessage created                  │
│  Router.handle_outbound()                │
│    ↓ (returns immediately)               │
│  Return JSON result                      │
└──────────────────────────────────────────┘
         ↓
┌─ Dispatchers.IO ─────────────────────────┐
│  Parse JSON, store in DB                 │
│  Return Result.success                   │
└──────────────────────────────────────────┘
         ↓
┌─ Main Thread ────────────────────────────┐
│  SendState = Success                     │
│  Flow emits → Recompose                  │
└──────────────────────────────────────────┘

... later, asynchronously ...

┌─ Reticulum Thread (Python) ──────────────┐
│  Message delivered over network          │
│  Delivery callback fires                 │
│  EventEmitter.emit()                     │
└──────────────────────────────────────────┘
         ↓
┌─ Kotlin Callback (JNI) ──────────────────┐
│  LxmfEventHandler.onPythonEvent()        │
│  Parse JSON → LxmfEvent.DeliveryConfirmed│
│  Emit to SharedFlow                      │
└──────────────────────────────────────────┘
         ↓
┌─ Dispatchers.IO ─────────────────────────┐
│  MessageEventProcessor                   │
│  Update database (state = DELIVERED)     │
└──────────────────────────────────────────┘
         ↓
┌─ Main Thread ────────────────────────────┐
│  Flow emits updated message              │
│  Recompose → Show delivered icon         │
└──────────────────────────────────────────┘
```

## Key Takeaways

1. **Always use `Dispatchers.IO` for Python calls** to avoid ANRs
2. **Store message immediately** after successful Python call, don't wait for delivery
3. **Use Flow for reactive UI** - updates happen automatically
4. **Track state properly** - OUTBOUND → SENDING → DELIVERED/FAILED
5. **Handle errors gracefully** - show retry option when appropriate
6. **Events are asynchronous** - delivery confirmation comes later
7. **Thread safety** - Python wrapper uses locks, Kotlin uses proper dispatchers
8. **Don't block UI** - show loading states, use progress indicators

## Testing This Pattern

```kotlin
@Test
fun testSendMessageFlow() = runTest {
    // Arrange
    val mockPythonModule = mockk<PyObject>()
    every { mockPythonModule.callAttr("send_message", any(), any(), any(), any(), any(), any()) } returns
        PyObject.fromJava("""
            {
                "success": true,
                "messageId": "abc123",
                "packedMessage": "deadbeef",
                "timestamp": 1234567890.0,
                "state": 1
            }
        """)

    val repository = LxmfRepository(context, messageDao, conversationDao, mockPythonModule)

    // Act
    val result = repository.sendMessage(
        destinationHash = "destination123",
        content = "Hello, world!"
    )

    // Assert
    assertTrue(result.isSuccess)
    assertEquals("abc123", result.getOrNull())

    // Verify database insert
    verify { messageDao.insert(match { it.id == "abc123" && it.content == "Hello, world!" }) }
}
```

## Related Patterns

- **receive-message-pattern.md** - Handling incoming messages
- **conversation-management.md** - Managing conversation context
- **propagation-node-sync.md** - Store-and-forward messaging
