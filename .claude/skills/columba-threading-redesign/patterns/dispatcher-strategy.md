# Pattern: Dispatcher Selection Strategy

## Decision Matrix

```
Is it UI update?
└─ YES → Dispatchers.Main

Is it Python call?
└─ YES → PythonExecutor

Is it Room database?
└─ YES → No dispatcher (Room handles it)

Is it blocking I/O? (files, network)
└─ YES → Dispatchers.IO

Is it CPU-intensive? (parsing, crypto)
└─ YES → Dispatchers.Default

└─ DEFAULT → Dispatchers.Default
```

---

## Phase 1 Lesson: Signal Handlers Require Specific Thread

**Critical Discovery**: Python operations that register signal handlers (like initialization) require the thread where `Python.start()` was called.

### The Evolution

**Original (SLOW)**:
```kotlin
// Handler.post() - caused 60+ second delays
Handler(Looper.getMainLooper()).post {
    wrapper.callAttr("initialize", configJson)
}
```

**Phase 1 Initial Attempt (BROKEN)**:
```kotlin
// ❌ WRONG - Breaks signal handlers!
withContext(Dispatchers.IO) {
    wrapper.callAttr("initialize", configJson)
}
// Error: "signal only works in main thread of the main interpreter"
```

**Phase 1 Final Solution (CORRECT)**:
```kotlin
// ✅ CORRECT - Fast main thread execution
withContext(Dispatchers.Main.immediate) {
    wrapper.callAttr("initialize", configJson)
}
```

### Why This Matters

**From Chaquopy maintainer** (Issue #1243):
> "if you call Python.start on a different thread, then Python will consider that to be the main thread"

Since `Python.start()` is called on Android's main thread (in `Application.onCreate()`), operations that register signal handlers MUST use Android's main thread.

**Dispatchers.Main.immediate benefits**:
- ✅ Executes on main thread (satisfies signal handler requirement)
- ✅ Runs immediately without Handler queue delays
- ✅ Works with async patterns (no ANR when used in coroutine)

### Updated Decision for Python Operations

```
Is it Python initialization or signal handler registration?
└─ YES → Dispatchers.Main.immediate (Phase 1)

Is it regular Python call?
└─ YES → PythonExecutor (Phase 3)
      OR Dispatchers.IO for simple calls (Phase 1-2)
```

---

## Examples

### Python Operations

```kotlin
// ✅ CORRECT
val result = PythonExecutor.execute("method_name") {
    wrapper.callAttr("some_method")
}
```

### Database Operations

```kotlin
// ✅ CORRECT (Room handles threading)
suspend fun saveMessage(message: Message) {
    messageDao.insert(message)  // Room uses IO automatically
}
```

### CPU Work

```kotlin
// ✅ CORRECT
suspend fun parseData(json: String): Data {
    return withContext(Dispatchers.Default) {
        val parsed = JSON.parse(json)
        val decrypted = crypto.decrypt(parsed.data)
        Data(decrypted)
    }
}
```

### File I/O

```kotlin
// ✅ CORRECT
suspend fun loadConfig(): Config {
    return withContext(Dispatchers.IO) {
        val file = File(configPath)
        JSON.parse(file.readText())
    }
}
```

### UI Updates

```kotlin
// ✅ CORRECT
viewModelScope.launch {  // Uses Main.immediate
    _uiState.value = UiState.Loading
    
    val data = withContext(Dispatchers.IO) {
        fetchData()  // Off main thread
    }
    
    _uiState.value = UiState.Success(data)  // Back on main
}
```

---

## Common Mistakes

```kotlin
// ❌ WRONG: Python on Default
launch(Dispatchers.Default) {
    wrapper.callAttr("method")  // Should use PythonExecutor
}

// ❌ WRONG: CPU work on IO
withContext(Dispatchers.IO) {
    expensiveParsing()  // Should be Default
}

// ❌ WRONG: Blocking on Main
launch(Dispatchers.Main) {
    heavyOperation()  // Freezes UI!
}
```

---

*Right dispatcher = Right performance*
