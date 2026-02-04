# Python LXMF Integration Guide

## Overview

This guide covers integrating LXMF messaging into Columba using **Chaquopy** to bridge Kotlin and Python. The Python layer handles LXMF protocol operations while Kotlin manages UI, database, and Android lifecycle.

## Architecture

```
┌─────────────────────────────────────────┐
│         Kotlin Layer (Android)          │
│  ┌───────────┐  ┌──────────────────┐   │
│  │ViewModel  │→ │ LxmfRepository   │   │
│  └───────────┘  └──────────────────┘   │
│         ↓                ↓              │
│    ┌────────────────────────┐          │
│    │   Chaquopy Bridge      │          │
│    └────────────────────────┘          │
└─────────────────────────────────────────┘
                 ↕ (JNI)
┌─────────────────────────────────────────┐
│         Python Layer (Chaquopy)         │
│    ┌────────────────────┐               │
│    │  lxmf_wrapper.py   │               │
│    │  ┌──────────────┐  │               │
│    │  │  LXMRouter   │  │               │
│    │  └──────────────┘  │               │
│    └────────────────────┘               │
└─────────────────────────────────────────┘
                 ↕
        [Reticulum Network]
```

## Python Module Structure

### File Location
```
app/src/main/python/
├── __init__.py
├── lxmf_wrapper.py        # Main LXMF wrapper
├── event_emitter.py       # Event callback system
└── utils.py               # Helper functions
```

### Dependencies (build.gradle.kts)

```kotlin
plugins {
    id("com.chaquo.python")
}

chaquopy {
    defaultConfig {
        version = "3.11"
        pip {
            install("lxmf")           // Includes RNS dependency
            install("msgpack")
        }
    }
}
```

## Event System Design

### Python → Kotlin Events

Events use **JSON serialization** for cross-language communication:

```python
# event_emitter.py
import json
from typing import Callable, Dict, Any

class EventEmitter:
    def __init__(self):
        self.kotlin_callback = None

    def set_callback(self, callback: Callable[[str], None]):
        """Set Kotlin callback function"""
        self.kotlin_callback = callback

    def emit(self, event_type: str, data: Dict[str, Any]):
        """Emit event to Kotlin layer"""
        if self.kotlin_callback:
            event = {
                "type": event_type,
                "timestamp": time.time(),
                "data": data
            }
            self.kotlin_callback(json.dumps(event))

# Global emitter instance
_emitter = EventEmitter()

def set_event_callback(callback):
    """Called from Kotlin to register callback"""
    _emitter.set_callback(callback)

def get_emitter() -> EventEmitter:
    return _emitter
```

### Kotlin Event Handler

```kotlin
// LxmfEventHandler.kt
object LxmfEventHandler {
    private val _events = MutableSharedFlow<LxmfEvent>()
    val events: SharedFlow<LxmfEvent> = _events.asSharedFlow()

    // Called by Python via Chaquopy
    @JvmStatic
    fun onPythonEvent(jsonEvent: String) {
        try {
            val event = Json.decodeFromString<LxmfEvent>(jsonEvent)
            CoroutineScope(Dispatchers.IO).launch {
                _events.emit(event)
            }
        } catch (e: Exception) {
            Log.e("LxmfEventHandler", "Failed to parse event", e)
        }
    }
}

@Serializable
sealed class LxmfEvent {
    @Serializable
    @SerialName("message_received")
    data class MessageReceived(
        val messageId: String,
        val sourceHash: String,
        val content: String,
        val title: String = "",
        val timestamp: Double,
        val fields: Map<String, String> = emptyMap(),
        val packedMessage: String
    ) : LxmfEvent()

    @Serializable
    @SerialName("delivery_confirmed")
    data class DeliveryConfirmed(
        val messageId: String,
        val state: Int
    ) : LxmfEvent()

    @Serializable
    @SerialName("delivery_failed")
    data class DeliveryFailed(
        val messageId: String,
        val state: Int
    ) : LxmfEvent()

    @Serializable
    @SerialName("propagation_progress")
    data class PropagationProgress(
        val state: Int,
        val progress: Float,
        val messagesReceived: Int
    ) : LxmfEvent()
}
```

## LXMFWrapper Implementation

### Complete Python Wrapper

See `templates/lxmf_wrapper.py` for full implementation.

**Key methods:**

```python
class LXMFWrapper:
    def initialize(self, rns_config_path: str, display_name: str) -> Dict
    def send_message(self, dest_hash: str, content: str,
                    title: str = "", method: int = 2,
                    fields: Dict = None) -> Dict
    def announce(self) -> Dict
    def set_propagation_node(self, node_hash: str) -> Dict
    def request_messages(self, max_messages: int = 100) -> Dict
    def get_propagation_state(self) -> Dict
    def shutdown(self) -> Dict
```

### Module-Level Functions (Kotlin Interface)

```python
# lxmf_wrapper.py

# Global instance
_wrapper: Optional[LXMFWrapper] = None

def initialize_lxmf(storage_path: str, identity_path: str,
                    rns_config: str, display_name: str) -> str:
    """Initialize LXMF router. Returns JSON result."""
    global _wrapper
    _wrapper = LXMFWrapper(storage_path, identity_path)
    result = _wrapper.initialize(rns_config, display_name)
    return json.dumps(result)

def send_message(dest_hash: str, content: str, title: str = "",
                method: int = 2, fields_json: str = "{}") -> str:
    """Send LXMF message. Returns JSON result."""
    if not _wrapper:
        return json.dumps({"success": False, "error": "Not initialized"})
    fields = json.loads(fields_json) if fields_json else {}
    result = _wrapper.send_message(dest_hash, content, title, method, fields)
    return json.dumps(result)

def announce_destination() -> str:
    """Announce LXMF destination. Returns JSON result."""
    if not _wrapper:
        return json.dumps({"success": False, "error": "Not initialized"})
    result = _wrapper.announce()
    return json.dumps(result)

def set_propagation_node(node_hash: str) -> str:
    """Set active propagation node. Returns JSON result."""
    if not _wrapper:
        return json.dumps({"success": False, "error": "Not initialized"})
    result = _wrapper.set_propagation_node(node_hash)
    return json.dumps(result)

def request_propagation_messages(max_messages: int = 100) -> str:
    """Request messages from propagation node. Returns JSON result."""
    if not _wrapper:
        return json.dumps({"success": False, "error": "Not initialized"})
    result = _wrapper.request_messages(max_messages)
    return json.dumps(result)

def get_propagation_status() -> str:
    """Get propagation sync status. Returns JSON result."""
    if not _wrapper:
        return json.dumps({"success": False, "error": "Not initialized"})
    result = _wrapper.get_propagation_state()
    return json.dumps(result)

def shutdown_lxmf() -> str:
    """Shutdown LXMF router gracefully. Returns JSON result."""
    global _wrapper
    if _wrapper:
        result = _wrapper.shutdown()
        _wrapper = None
        return json.dumps(result)
    return json.dumps({"success": True})
```

## Kotlin Integration

### Repository Layer

```kotlin
// LxmfRepository.kt
class LxmfRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val pythonModule: Python
) {
    private val lxmfModule by lazy {
        pythonModule.getModule("lxmf_wrapper")
    }

    suspend fun initialize(displayName: String): Result<LxmfIdentity> = withContext(Dispatchers.IO) {
        try {
            val storagePath = File(context.filesDir, "lxmf_storage").absolutePath
            val identityPath = File(context.filesDir, "lxmf_identity").absolutePath
            val rnsConfig = File(context.filesDir, ".reticulum").absolutePath

            val resultJson = lxmfModule.callAttr(
                "initialize_lxmf",
                storagePath,
                identityPath,
                rnsConfig,
                displayName
            ).toString()

            val result = Json.decodeFromString<InitializeResult>(resultJson)

            if (result.success) {
                Result.success(
                    LxmfIdentity(
                        destinationHash = result.destinationHash!!,
                        identityHash = result.identityHash!!
                    )
                )
            } else {
                Result.failure(Exception(result.error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun sendMessage(
        destinationHash: String,
        content: String,
        title: String = "",
        method: DeliveryMethod = DeliveryMethod.DIRECT,
        fields: Map<String, Any> = emptyMap()
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val fieldsJson = Json.encodeToString(fields)

            val resultJson = lxmfModule.callAttr(
                "send_message",
                destinationHash,
                content,
                title,
                method.value,
                fieldsJson
            ).toString()

            val result = Json.decodeFromString<SendMessageResult>(resultJson)

            if (result.success) {
                // Store in database
                messageDao.insert(
                    MessageEntity(
                        id = result.messageId!!,
                        conversationHash = destinationHash,
                        content = content,
                        title = title,
                        timestamp = result.timestamp!!,
                        isFromMe = true,
                        state = result.state!!,
                        method = method.value,
                        packedLxm = result.packedMessage!!.hexToByteArray(),
                        fields = fieldsJson,
                        progress = 0f
                    )
                )

                Result.success(result.messageId)
            } else {
                Result.failure(Exception(result.error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun requestPropagationMessages(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val resultJson = lxmfModule.callAttr(
                "request_propagation_messages",
                100
            ).toString()

            val result = Json.decodeFromString<PropagationRequestResult>(resultJson)

            if (result.success) {
                Result.success(0)  // Actual count comes via events
            } else {
                Result.failure(Exception(result.error))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

enum class DeliveryMethod(val value: Int) {
    OPPORTUNISTIC(1),
    DIRECT(2),
    PROPAGATED(3)
}
```

### Initialization in Application Class

```kotlin
// ColumbaApplication.kt
class ColumbaApplication : Application() {

    override fun onCreate() {
        super.onCreate()

        // Initialize Python
        if (!Python.isStarted()) {
            Python.start(AndroidPlatform(this))
        }

        // Set up event callback
        val python = Python.getInstance()
        val eventEmitter = python.getModule("event_emitter")

        eventEmitter.callAttr(
            "set_event_callback",
            object : com.chaquo.python.PyObject.Callback {
                override fun call(vararg args: Any?): Any? {
                    val jsonEvent = args[0] as String
                    LxmfEventHandler.onPythonEvent(jsonEvent)
                    return null
                }
            }
        )

        // Initialize LXMF in background
        CoroutineScope(Dispatchers.IO).launch {
            val repository = // ... get from DI
            repository.initialize(displayName = "User")
        }
    }
}
```

### Event Processing

```kotlin
// MessageEventProcessor.kt
class MessageEventProcessor @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val notificationManager: NotificationManager
) {

    suspend fun processEvent(event: LxmfEvent) {
        when (event) {
            is LxmfEvent.MessageReceived -> handleMessageReceived(event)
            is LxmfEvent.DeliveryConfirmed -> handleDeliveryConfirmed(event)
            is LxmfEvent.DeliveryFailed -> handleDeliveryFailed(event)
            is LxmfEvent.PropagationProgress -> handlePropagationProgress(event)
        }
    }

    private suspend fun handleMessageReceived(event: LxmfEvent.MessageReceived) {
        withContext(Dispatchers.IO) {
            // Store message
            messageDao.insert(
                MessageEntity(
                    id = event.messageId,
                    conversationHash = event.sourceHash,
                    content = event.content,
                    title = event.title,
                    timestamp = (event.timestamp * 1000).toLong(),
                    isFromMe = false,
                    state = MessageState.DELIVERED.value,
                    method = DeliveryMethod.DIRECT.value,  // Will be updated
                    packedLxm = event.packedMessage.hexToByteArray(),
                    fields = Json.encodeToString(event.fields),
                    isRead = false
                )
            )

            // Update or create conversation
            val existing = conversationDao.getConversation(event.sourceHash)
            if (existing != null) {
                conversationDao.update(
                    existing.copy(
                        lastMessage = event.content.take(100),
                        lastMessageTimestamp = (event.timestamp * 1000).toLong(),
                        unreadCount = existing.unreadCount + 1
                    )
                )
            } else {
                conversationDao.insert(
                    ConversationEntity(
                        peerHash = event.sourceHash,
                        peerName = "Unknown",  // Update when we have name
                        lastMessage = event.content.take(100),
                        lastMessageTimestamp = (event.timestamp * 1000).toLong(),
                        unreadCount = 1
                    )
                )
            }

            // Show notification
            notificationManager.showMessageNotification(
                conversationHash = event.sourceHash,
                content = event.content
            )
        }
    }

    private suspend fun handleDeliveryConfirmed(event: LxmfEvent.DeliveryConfirmed) {
        withContext(Dispatchers.IO) {
            messageDao.updateState(event.messageId, event.state)
            messageDao.updateProgress(event.messageId, 1.0f)
        }
    }

    private suspend fun handleDeliveryFailed(event: LxmfEvent.DeliveryFailed) {
        withContext(Dispatchers.IO) {
            messageDao.updateState(event.messageId, event.state)
        }
    }

    private suspend fun handlePropagationProgress(event: LxmfEvent.PropagationProgress) {
        // Update UI state, show progress
        Log.d("PropagationSync", "Progress: ${event.progress}, Messages: ${event.messagesReceived}")
    }
}
```

## Threading Considerations

### Python Call Dispatcher

**Always use `Dispatchers.IO` for Python calls:**

```kotlin
// CORRECT
viewModelScope.launch {
    withContext(Dispatchers.IO) {
        pythonModule.callAttr("send_message", ...)
    }
}

// WRONG - blocks main thread
viewModelScope.launch {
    pythonModule.callAttr("send_message", ...)  // ANR risk!
}
```

### Python Thread Safety

LXMF/Reticulum operations are **not thread-safe** by default. The wrapper uses a single thread approach:

```python
# lxmf_wrapper.py
class LXMFWrapper:
    def __init__(self):
        self._lock = threading.RLock()  # Reentrant lock

    def send_message(self, ...):
        with self._lock:
            # All LXMF operations protected
            lxm = LXMF.LXMessage(...)
            self.message_router.handle_outbound(lxm)
```

### Event Callback Thread

Python callbacks execute on Reticulum's internal threads. Emit events without blocking:

```python
def _on_message_received(self, message):
    # Quick validation
    if not message.signature_validated:
        return

    # Emit event (non-blocking)
    event_data = {
        "messageId": message.hash.hex(),
        "sourceHash": message.source_hash.hex(),
        "content": message.content.decode("utf-8", errors="replace"),
        # ...
    }
    self.emitter.emit("message_received", event_data)

    # Don't do heavy work here
```

## Error Handling

### Python Error Handling

```python
def send_message(dest_hash: str, content: str, ...) -> str:
    try:
        if not _wrapper:
            return json.dumps({
                "success": False,
                "error": "LXMF not initialized",
                "error_code": "NOT_INITIALIZED"
            })

        result = _wrapper.send_message(dest_hash, content, ...)
        return json.dumps(result)

    except ValueError as e:
        return json.dumps({
            "success": False,
            "error": f"Invalid parameter: {str(e)}",
            "error_code": "INVALID_PARAMETER"
        })
    except Exception as e:
        return json.dumps({
            "success": False,
            "error": str(e),
            "error_code": "UNKNOWN_ERROR"
        })
```

### Kotlin Error Handling

```kotlin
suspend fun sendMessage(...): Result<String> = withContext(Dispatchers.IO) {
    try {
        val resultJson = lxmfModule.callAttr(...).toString()
        val result = Json.decodeFromString<SendMessageResult>(resultJson)

        if (result.success) {
            Result.success(result.messageId!!)
        } else {
            when (result.errorCode) {
                "NOT_INITIALIZED" -> Result.failure(LxmfNotInitializedException())
                "INVALID_PARAMETER" -> Result.failure(IllegalArgumentException(result.error))
                "UNKNOWN_DESTINATION" -> Result.failure(UnknownDestinationException())
                else -> Result.failure(Exception(result.error))
            }
        }
    } catch (e: PythonException) {
        Log.e("LxmfRepository", "Python error", e)
        Result.failure(e)
    } catch (e: SerializationException) {
        Log.e("LxmfRepository", "JSON parsing error", e)
        Result.failure(e)
    }
}
```

## Performance Optimization

### Message Batching

For bulk operations (e.g., loading old messages from propagation node):

```python
def process_received_messages(self, messages: List[LXMF.LXMessage]):
    """Batch process multiple received messages"""
    events = []
    for message in messages:
        if message.signature_validated:
            events.append({
                "type": "message_received",
                "data": self._serialize_message(message)
            })

    # Emit as single batch event
    self.emitter.emit("messages_batch", {"messages": events})
```

### Lazy Initialization

Don't initialize on app startup if not needed:

```kotlin
class LxmfManager @Inject constructor(...) {
    private var isInitialized = false

    suspend fun ensureInitialized() {
        if (!isInitialized) {
            repository.initialize("User")
            isInitialized = true
        }
    }

    suspend fun sendMessage(...) {
        ensureInitialized()
        repository.sendMessage(...)
    }
}
```

## Testing

### Mocking Python Module

```kotlin
// For unit tests
@Before
fun setup() {
    val mockPythonModule = mockk<PyObject>()
    every { mockPythonModule.callAttr("send_message", any(), any(), any(), any(), any()) } returns
        PyObject.fromJava("""{"success": true, "messageId": "abc123"}""")

    repository = LxmfRepository(
        context = context,
        messageDao = messageDao,
        conversationDao = conversationDao,
        pythonModule = mockPythonModule
    )
}
```

### Integration Testing

```kotlin
@Test
fun testMessageRoundTrip() = runTest {
    // Requires actual Python runtime
    val python = Python.getInstance()
    val lxmfModule = python.getModule("lxmf_wrapper")

    // Initialize two identities
    val alice = initializeIdentity(lxmfModule, "Alice")
    val bob = initializeIdentity(lxmfModule, "Bob")

    // Alice sends to Bob
    sendMessage(lxmfModule, alice, bob.destinationHash, "Hello Bob!")

    // Wait for event
    val event = withTimeout(5000) {
        LxmfEventHandler.events.first { it is LxmfEvent.MessageReceived }
    }

    assertEquals("Hello Bob!", (event as LxmfEvent.MessageReceived).content)
}
```

## Debugging

### Enable Python Logging

```python
# lxmf_wrapper.py
import logging
import RNS

# Enable RNS logging
RNS.loglevel = RNS.LOG_DEBUG

# Python logging
logging.basicConfig(
    level=logging.DEBUG,
    format='%(asctime)s - %(name)s - %(levelname)s - %(message)s'
)
logger = logging.getLogger(__name__)
```

### View Python Logs in Logcat

```bash
adb logcat -s python.stdout python.stderr
```

### Inspect Event Flow

```kotlin
// Add logging to event handler
LxmfEventHandler.events.onEach { event ->
    Log.d("LxmfEvent", "Received: ${event::class.simpleName}")
}.launchIn(scope)
```

## Common Issues

### Issue 1: Chaquopy Module Not Found

**Symptom:** `ModuleNotFoundError: No module named 'LXMF'`

**Solution:** Ensure pip install in build.gradle.kts:
```kotlin
chaquopy {
    defaultConfig {
        pip {
            install("lxmf")
        }
    }
}
```

### Issue 2: Events Not Received

**Symptom:** Python sends events but Kotlin doesn't receive them

**Solution:** Ensure callback is set **before** initializing LXMF:
```kotlin
// Set callback FIRST
eventEmitter.callAttr("set_event_callback", callback)

// THEN initialize
lxmfModule.callAttr("initialize_lxmf", ...)
```

### Issue 3: Main Thread Blocked

**Symptom:** ANRs when sending messages

**Solution:** Always use `Dispatchers.IO`:
```kotlin
withContext(Dispatchers.IO) {
    pythonModule.callAttr(...)
}
```

### Issue 4: Identity/Storage Corruption

**Symptom:** Initialization fails with "corrupted identity"

**Solution:** Ensure proper paths and permissions:
```kotlin
val identityPath = File(context.filesDir, "lxmf_identity").absolutePath
// context.filesDir is app-private, safe for identities
```

## Next Steps

1. Review `templates/lxmf_wrapper.py` for complete implementation
2. Study `templates/MessageRepository.kt` for repository pattern
3. Check `patterns/send-message-pattern.md` for complete flow
4. Test with `checklists/integration-checklist.md`
