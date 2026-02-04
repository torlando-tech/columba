# Pattern: Synchronous → Async IPC

## Problem

Blocking IPC calls with `runBlocking` exhaust binder thread pool, causing ANRs.

## Solution

Convert to async with callbacks.

---

## ❌ BEFORE: Blocking IPC

**AIDL**:
```java
interface IReticulumService {
    String initialize(String configJson);  // Blocks caller!
}
```

**Service Implementation**:
```kotlin
override fun initialize(configJson: String): String {
    return runBlocking {  // ❌ Blocks binder thread!
        val result = PythonExecutor.execute {
            wrapper!!.callAttr("initialize", configJson)
        }
        result.toString()
    }
}
```

**Client**:
```kotlin
val result = service.initialize(configJson)  // Blocks for 2+ seconds
```

**Problems**:
- Binder thread blocked 2+ seconds
- Can exhaust thread pool (only 16 threads)
- ANR risk
- No cancellation

---

## ✅ AFTER: Async with Callback

### Step 1: Define Callback Interface

**IInitializationCallback.aidl**:
```java
package com.lxmf.messenger.reticulum;

interface IInitializationCallback {
    void onSuccess(String result);
    void onError(String error);
}
```

### Step 2: Update Service Interface

**IReticulumService.aidl**:
```java
import com.lxmf.messenger.reticulum.IInitializationCallback;

interface IReticulumService {
    void initialize(String configJson, IInitializationCallback callback);
}
```

### Step 3: Implement Async in Service

```kotlin
override fun initialize(configJson: String, callback: IInitializationCallback) {
    // Binder thread returns IMMEDIATELY (< 1ms)
    serviceScope.launch {
        try {
            val result = PythonExecutor.execute("initialize") {
                wrapper!!.callAttr("initialize", configJson)
            }
            callback.onSuccess(result.toString())
        } catch (e: Exception) {
            callback.onError(e.message ?: "Unknown error")
        }
    }
}
```

### Step 4: Use Suspend Function in Client

```kotlin
suspend fun initialize(config: ReticulumConfig): Result<String> {
    return suspendCancellableCoroutine { continuation ->
        val callback = object : IInitializationCallback.Stub() {
            override fun onSuccess(result: String) {
                continuation.resume(Result.success(result))
            }

            override fun onError(error: String) {
                continuation.resume(Result.failure(Exception(error)))
            }
        }

        try {
            service?.initialize(config.toJson(), callback)
        } catch (e: RemoteException) {
            continuation.resume(Result.failure(e))
        }
    }
}

// Usage (looks synchronous but isn't!)
val result = protocol.initialize(config)
```

**Benefits**:
- ✅ Binder thread returns in < 1ms
- ✅ No thread pool exhaustion
- ✅ No ANR risk
- ✅ Cancellable (via coroutine cancellation)
- ✅ Better error handling

---

## Pattern Summary

### For Every Blocking AIDL Method

1. Create callback interface (Success + Error)
2. Change AIDL method to accept callback, return void
3. Service: Launch coroutine, call callback when done
4. Client: Wrap in `suspendCancellableCoroutine`

### Template

**Callback AIDL**:
```java
interface I[Operation]Callback {
    void onSuccess([ReturnType] result);
    void onError(String error);
}
```

**Service**:
```kotlin
override fun [operation]([params], callback: I[Operation]Callback) {
    serviceScope.launch {
        try {
            val result = doWork([params])
            callback.onSuccess(result)
        } catch (e: Exception) {
            callback.onError(e.message ?: "Unknown")
        }
    }
}
```

**Client**:
```kotlin
suspend fun [operation]([params]): Result<[ReturnType]> {
    return suspendCancellableCoroutine { cont ->
        service?.[operation]([params], object : I[Operation]Callback.Stub() {
            override fun onSuccess(result: [ReturnType]) {
                cont.resume(Result.success(result))
            }
            override fun onError(error: String) {
                cont.resume(Result.failure(Exception(error)))
            }
        })
    }
}
```

---

*Async IPC = No ANRs*
