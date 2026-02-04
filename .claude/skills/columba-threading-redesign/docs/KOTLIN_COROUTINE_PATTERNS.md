# Kotlin Coroutine Patterns for Columba

## Document Purpose

This document provides research-verified Kotlin coroutine best practices specific to the Columba Android app, including dispatcher selection rules, anti-patterns found in the codebase, and correct patterns for the threading redesign.

## Dispatcher Selection Rules

### The Four Dispatchers

Kotlin provides four main dispatchers, each optimized for different work types:

| Dispatcher | Thread Pool | Best For | Avoid For |
|------------|-------------|----------|-----------|
| **Main** | Android main thread | UI updates, StateFlow emissions | Any blocking operation |
| **IO** | Expandable pool (64+ threads) | Blocking I/O (network, files, database) | CPU-intensive work |
| **Default** | Fixed pool (CPU cores) | CPU-intensive computation | Blocking I/O |
| **Unconfined** | No specific thread | Special cases only | Production code (unpredictable) |

### Columba-Specific Dispatcher Strategy

Based on codebase analysis and architecture requirements:

```kotlin
// ViewModel layer (UI process)
class MessageListViewModel : ViewModel() {
    // ViewModel scope uses Main dispatcher by default
    init {
        viewModelScope.launch {  // Dispatchers.Main.immediate
            // Collect from service
            reticulumProtocol.messages.collect { messages ->
                _uiState.value = UiState.Messages(messages)
            }
        }
    }

    fun sendMessage(content: String) {
        viewModelScope.launch {  // Dispatchers.Main.immediate
            // State update happens on Main
            _uiState.value = UiState.Sending

            // Heavy work on appropriate dispatcher
            withContext(Dispatchers.Default) {
                val encrypted = encryptMessage(content)

                // IPC call returns immediately (async)
                reticulumProtocol.sendMessage(encrypted)
            }
        }
    }
}

// Service layer (background process)
class ReticulumService : Service() {
    // Service scope: Default for general work
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ALL Python calls go through executor
    private suspend fun <T> callPython(block: () -> T): T =
        PythonExecutor.execute(block)

    fun initialize(config: ReticulumConfig) {
        serviceScope.launch {
            // Python call
            callPython {
                wrapper.callAttr("initialize", config.toJson())
            }

            // State update (thread-safe)
            _networkStatus.value = NetworkStatus.READY
        }
    }

    // Database operations: Room handles threading automatically
    suspend fun saveMessage(message: Message) {
        // Room uses IO dispatcher internally
        messageDao.insert(message)
    }

    // CPU-intensive work: use Default
    suspend fun processAnnounce(data: ByteArray) {
        withContext(Dispatchers.Default) {
            val parsed = parseAnnounceData(data)
            val verified = verifySignature(parsed)
            // ...
        }
    }
}
```

### Decision Matrix

Use this matrix to choose the correct dispatcher:

```
Is it updating UI?
└─ YES → Dispatchers.Main
└─ NO → Continue...

Is it calling Python/Chaquopy?
└─ YES → PythonExecutor (single-threaded)
└─ NO → Continue...

Is it a Room database operation?
└─ YES → No dispatcher needed (Room handles it)
└─ NO → Continue...

Is it blocking I/O? (network, files, etc.)
└─ YES → Dispatchers.IO
└─ NO → Continue...

Is it CPU-intensive? (parsing, crypto, etc.)
└─ YES → Dispatchers.Default
└─ NO → Continue...

Does it need to run on a specific thread?
└─ YES → Custom dispatcher or HandlerThread
└─ NO → Dispatchers.Default (safe default)
```

## Anti-Patterns Found in Codebase

### 1. runBlocking in Production Code

**Location**: ReticulumService.kt:85

```kotlin
// ❌ WRONG: Blocks binder thread
override fun initialize(configJson: String): String {
    return runBlocking {  // Blocks caller's thread!
        serviceScope.async {
            val result = wrapper!!.callAttr("initialize", configJson)
            result.toString()
        }.await()
    }
}
```

**Why Wrong**:
- Blocks binder thread from limited pool (~16 threads)
- Can cause ANR if operation takes > 5 seconds
- Defeats purpose of async/await
- Other IPC calls blocked waiting for thread

**✅ CORRECT: Async with callback**
```kotlin
// Define callback interface in AIDL
interface IInitializationCallback {
    void onInitializationComplete(String result);
    void onInitializationError(String error);
}

// Implement async method
override fun initialize(configJson: String, callback: IInitializationCallback) {
    serviceScope.launch {
        try {
            val result = PythonExecutor.execute {
                wrapper!!.callAttr("initialize", configJson)
            }
            callback.onInitializationComplete(result.toString())
        } catch (e: Exception) {
            callback.onInitializationError(e.message ?: "Unknown error")
        }
    }
}
```

**Rule**: Never use `runBlocking` except in:
- Test code (explicitly marked as test)
- `main()` functions (bridging sync/async worlds)
- Never in AIDL implementations
- Never in callbacks/listeners

### 2. Handler.post() with CountDownLatch

**Location**: ReticulumService.kt (now fixed in commit b8fdbb2)

```kotlin
// ❌ WRONG: Synchronous wait on async operation
val latch = CountDownLatch(1)
val resultRef = AtomicReference<PyObject>()

Handler(Looper.getMainLooper()).post {
    resultRef.set(wrapper!!.callAttr("initialize", configJson))
    latch.countDown()
}

// Blocks coroutine thread waiting for main thread
latch.await(60, TimeUnit.SECONDS)
```

**Why Wrong**:
- Posts to potentially busy main thread
- Blocks current thread waiting
- Arbitrary timeout (60 seconds - why?)
- Main thread queue can be severely backed up
- No way to cancel or get progress

**✅ CORRECT: Direct call on appropriate dispatcher**
```kotlin
// Python doesn't need main thread
val result = withContext(Dispatchers.IO) {
    wrapper!!.callAttr("initialize", configJson)
}
```

**Or with dedicated executor**:
```kotlin
val result = PythonExecutor.execute {
    wrapper!!.callAttr("initialize", configJson)
}
```

### 3. Polling with delay()

**Location**: ServiceReticulumProtocol.kt:247-278

```kotlin
// ❌ WRONG: Polling loop with arbitrary delay
suspend fun waitForReady(timeoutMillis: Long = 10000): Boolean {
    var attempts = 0
    val maxAttempts = (timeoutMillis / 100).toInt()

    while (attempts < maxAttempts) {
        when (val status = getStatus()) {
            is NetworkStatus.READY -> return true
            else -> delay(100)  // Why 100ms?
        }
        attempts++
    }
    return false
}
```

**Why Wrong**:
- Wakes up CPU every 100ms even if no change
- Wastes battery
- Up to 100ms latency for status change
- Arbitrary delay value
- No way to cancel or adjust

**✅ CORRECT: Event-driven with Flow**
```kotlin
// Service emits to StateFlow when status changes
private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)
val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

// Callers wait for specific state
suspend fun waitForReady(timeoutMillis: Long = 10000): Boolean {
    return withTimeoutOrNull(timeoutMillis) {
        networkStatus.first { it is NetworkStatus.READY }
        true
    } ?: false
}
```

**Benefits**:
- Zero CPU when status unchanged
- Instant notification (< 10ms)
- No arbitrary delays
- Cancellable
- Type-safe

### 4. Incorrect Dispatcher Choice

**Found Throughout Codebase**:

```kotlin
// ❌ WRONG: Python on Default dispatcher
launch(Dispatchers.Default) {
    wrapper.callAttr("some_method")  // Blocking I/O on CPU pool
}

// ❌ WRONG: CPU work on IO dispatcher
launch(Dispatchers.IO) {
    val json = parseComplexJson(data)  // CPU work on I/O pool
}

// ❌ WRONG: Blocking operation on Main
launch(Dispatchers.Main) {
    val result = someBlockingCall()  // Freezes UI!
}
```

**✅ CORRECT: Match dispatcher to work type**
```kotlin
// Python: Use dedicated executor
launch {
    val result = PythonExecutor.execute {
        wrapper.callAttr("some_method")
    }
}

// CPU work: Use Default
launch(Dispatchers.Default) {
    val json = parseComplexJson(data)
}

// UI update: Use Main
launch(Dispatchers.Main) {
    _uiState.value = UiState.Updated
}
```

### 5. GlobalScope Usage

**Potential Issue**:

```kotlin
// ❌ WRONG: Using GlobalScope
GlobalScope.launch {
    // Work happens but:
    // - No structured concurrency
    // - Can't cancel from parent
    // - Leaks if not properly managed
}
```

**✅ CORRECT: Use structured concurrency**
```kotlin
// Service has its own scope
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

// Work is scoped
fun doWork() {
    serviceScope.launch {
        // Automatically cancelled when service destroyed
    }
}

// Clean up when done
override fun onDestroy() {
    serviceScope.cancel()
    super.onDestroy()
}
```

## Correct Patterns

### Pattern 1: StateFlow for Status Updates

**Use Case**: Distributing state changes across processes

```kotlin
// In Service
sealed class NetworkStatus {
    object SHUTDOWN : NetworkStatus()
    object INITIALIZING : NetworkStatus()
    object READY : NetworkStatus()
    data class ERROR(val message: String) : NetworkStatus()
}

class ReticulumService : Service() {
    private val _networkStatus = MutableStateFlow<NetworkStatus>(NetworkStatus.SHUTDOWN)

    // Expose as read-only StateFlow
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private fun updateStatus(newStatus: NetworkStatus) {
        Log.d(TAG, "Status transition: ${_networkStatus.value} -> $newStatus")
        _networkStatus.value = newStatus
    }

    fun initialize() {
        serviceScope.launch {
            updateStatus(NetworkStatus.INITIALIZING)

            try {
                PythonExecutor.execute {
                    wrapper.callAttr("initialize", configJson)
                }
                updateStatus(NetworkStatus.READY)
            } catch (e: Exception) {
                updateStatus(NetworkStatus.ERROR(e.message ?: "Unknown error"))
            }
        }
    }
}

// In ViewModel
class StatusViewModel : ViewModel() {
    val statusText: StateFlow<String> = protocol.networkStatus
        .map { status ->
            when (status) {
                NetworkStatus.READY -> "Connected"
                NetworkStatus.INITIALIZING -> "Connecting..."
                NetworkStatus.SHUTDOWN -> "Disconnected"
                is NetworkStatus.ERROR -> "Error: ${status.message}"
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = "Unknown"
        )
}
```

**When to Use**:
- Single current value (status, configuration, etc.)
- Need to know latest value immediately
- UI that displays current state

**When NOT to Use**:
- Events that happen (messages, announces) - use SharedFlow instead
- One-shot operations - use suspend functions

### Pattern 2: SharedFlow for Events

**Use Case**: Broadcasting events like messages or announces

```kotlin
// In Service
class ReticulumService : Service() {
    // Events don't need replay (usually)
    private val _announceEvents = MutableSharedFlow<AnnounceEvent>(
        replay = 0,  // No replay for events
        extraBufferCapacity = 100,  // Buffer up to 100 events
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val announceEvents: SharedFlow<AnnounceEvent> = _announceEvents.asSharedFlow()

    private fun onAnnounceReceived(announce: Announce) {
        serviceScope.launch {
            _announceEvents.emit(AnnounceEvent.NewAnnounce(announce))
        }
    }
}

// In ViewModel
class AnnounceStreamViewModel : ViewModel() {
    val announces: StateFlow<List<Announce>> = protocol.announceEvents
        .scan(emptyList<Announce>()) { list, event ->
            when (event) {
                is AnnounceEvent.NewAnnounce -> list + event.announce
                is AnnounceEvent.AnnounceExpired -> list.filterNot { it.id == event.id }
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
```

**When to Use**:
- Events that happen over time
- Multiple subscribers need to receive
- Don't need to know past events (or use replay parameter)

**When NOT to Use**:
- Single current value - use StateFlow
- One subscriber - use Channel

### Pattern 3: async/await for Concurrent Operations

**Use Case**: Multiple independent operations that can run in parallel

```kotlin
suspend fun initializeSubsystems() = coroutineScope {
    // Launch all concurrently
    val pythonInit = async(Dispatchers.IO) {
        PythonExecutor.execute {
            wrapper.callAttr("initialize", config)
        }
    }

    val databaseInit = async {
        database.init()
    }

    val interfacesInit = async {
        loadInterfaceConfigs()
    }

    // Wait for all to complete
    val pythonResult = pythonInit.await()
    val dbResult = databaseInit.await()
    val interfacesResult = interfacesInit.await()

    InitializationResult(pythonResult, dbResult, interfacesResult)
}
```

**When to Use**:
- Operations can run in parallel
- Need results from all operations
- Structured error handling

**When NOT to Use**:
- Operations must be sequential - use regular suspend calls
- Don't need results - use separate launch calls

### Pattern 4: withTimeout for Operations with Time Limits

**Use Case**: Operations that shouldn't run indefinitely

```kotlin
suspend fun initializeWithTimeout(): Result<Unit> {
    return try {
        withTimeout(30_000) {  // 30 second timeout
            PythonExecutor.execute {
                wrapper.callAttr("initialize", configJson)
            }
            Result.success(Unit)
        }
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Initialization timed out after 30 seconds")
        Result.failure(e)
    } catch (e: Exception) {
        Log.e(TAG, "Initialization failed", e)
        Result.failure(e)
    }
}
```

**When to Use**:
- Operations that might hang
- External dependencies (Python, network, etc.)
- User-facing operations (don't want to wait forever)

**When NOT to Use**:
- Fast operations (< 1 second)
- Operations that MUST complete

### Pattern 5: Structured Concurrency with SupervisorJob

**Use Case**: Service or ViewModel scope that shouldn't cancel on single failure

```kotlin
class ReticulumService : Service() {
    // SupervisorJob: One child failure doesn't cancel others
    private val serviceScope = CoroutineScope(
        Dispatchers.Default + SupervisorJob()
    )

    override fun onCreate() {
        super.onCreate()

        // Launch multiple operations
        serviceScope.launch {
            handleAnnounces()  // If this fails...
        }

        serviceScope.launch {
            handleMessages()  // ...this keeps running
        }

        serviceScope.launch {
            monitorStatus()  // ...and so does this
        }
    }

    override fun onDestroy() {
        // Cancel all work
        serviceScope.cancel()
        super.onDestroy()
    }
}
```

**When to Use**:
- Long-lived scopes (Service, ViewModel)
- Independent operations that shouldn't affect each other
- Want to handle errors per-operation

**When NOT to Use**:
- Related operations (one failure should cancel all) - use regular Job
- Short-lived scopes - use coroutineScope or supervisorScope

### Pattern 6: Flow.first { } for Waiting on Condition

**Use Case**: Wait for specific state without polling

```kotlin
// ❌ OLD: Polling
suspend fun waitForReady() {
    while (getStatus() != NetworkStatus.READY) {
        delay(100)
    }
}

// ✅ NEW: Event-driven
suspend fun waitForReady() {
    networkStatus.first { it is NetworkStatus.READY }
}

// With timeout
suspend fun waitForReadyOrTimeout(timeoutMs: Long = 10_000): Boolean {
    return try {
        withTimeout(timeoutMs) {
            networkStatus.first { it is NetworkStatus.READY }
        }
        true
    } catch (e: TimeoutCancellationException) {
        false
    }
}
```

**Benefits**:
- No polling delay
- Instant notification
- Cancellable
- Composable with other Flow operators

## Testing Coroutines

### Test Dispatcher

```kotlin
@Test
fun testInitialization() = runTest {
    // runTest uses TestDispatcher - controls virtual time
    val service = ReticulumService()

    service.initialize(config)

    // Advance virtual time
    advanceUntilIdle()

    // Assert state
    assertEquals(NetworkStatus.READY, service.networkStatus.value)
}
```

### Testing Timeouts

```kotlin
@Test
fun testTimeoutBehavior() = runTest {
    // Simulate slow operation
    val result = withTimeoutOrNull(1000) {
        delay(2000)  // Takes longer than timeout
        "success"
    }

    assertNull(result)  // Should timeout
}
```

### Testing Concurrent Operations

```kotlin
@Test
fun testConcurrentPythonCalls() = runTest {
    val results = (1..10).map { i ->
        async {
            PythonExecutor.execute {
                wrapper.callAttr("method_$i")
            }
        }
    }.awaitAll()

    assertEquals(10, results.size)
    // All calls should complete successfully
    results.forEach { assertNotNull(it) }
}
```

## Common Questions

### Q: When should I use launch vs async?

**A: Depends on whether you need the result**

```kotlin
// Use launch when you don't need the result
launch {
    updateDatabase()  // Fire and forget
}

// Use async when you need the result
val result = async {
    computeSomething()
}.await()
```

### Q: What's the difference between withContext and launch?

**A: withContext suspends, launch doesn't**

```kotlin
// withContext: Suspends until complete
suspend fun getData(): String {
    return withContext(Dispatchers.IO) {
        fetchFromNetwork()  // Suspends here
    }
}

// launch: Returns immediately, runs in background
fun startWork() {
    scope.launch {
        doWork()  // Runs in background
    }
}
```

### Q: Should I use Dispatchers.Main or Main.immediate?

**A: Main.immediate avoids unnecessary dispatch**

```kotlin
// Main: Always dispatches
withContext(Dispatchers.Main) {
    updateUI()  // Even if already on main thread
}

// Main.immediate: Dispatch only if needed
withContext(Dispatchers.Main.immediate) {
    updateUI()  // No dispatch if already on main thread
}
```

For ViewModels, use default (Main.immediate).

### Q: How do I handle errors in coroutines?

**A: Use try-catch, or CoroutineExceptionHandler**

```kotlin
// Per-coroutine error handling
launch {
    try {
        riskyOperation()
    } catch (e: Exception) {
        Log.e(TAG, "Operation failed", e)
        _errorState.value = e.message
    }
}

// Scope-level error handling
val exceptionHandler = CoroutineExceptionHandler { _, exception ->
    Log.e(TAG, "Uncaught exception", exception)
}

val scope = CoroutineScope(Dispatchers.Default + SupervisorJob() + exceptionHandler)
```

## Summary Checklist

When writing coroutine code, verify:

- [ ] Correct dispatcher for work type (IO/Default/Main/PythonExecutor)
- [ ] No `runBlocking` in production code
- [ ] Using StateFlow for state, SharedFlow for events
- [ ] Proper structured concurrency (scoped launches)
- [ ] Timeouts for operations that might hang
- [ ] Error handling (try-catch or exception handler)
- [ ] Tests use runTest and TestDispatcher
- [ ] Documented why specific dispatcher chosen

---

*Reference: Kotlin Coroutines Guide - https://kotlinlang.org/docs/coroutines-guide.html*
