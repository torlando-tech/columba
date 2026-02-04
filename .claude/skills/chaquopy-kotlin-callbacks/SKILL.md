---
name: chaquopy-kotlin-callbacks
description: Patterns for Kotlin-Python callback integration with Chaquopy. Use when implementing callbacks from Python to Kotlin that return values, passing Kotlin functions to Python, or troubleshooting Chaquopy type conversion issues (ClassCastException, TypeError, buffer protocol errors).
---

# Chaquopy Kotlin-Python Callback Patterns

Patterns learned from implementing native Kotlin stamp generation called from Python LXMF.

## Callback Definition

Use method references instead of lambdas. R8/ProGuard strips type information from lambdas, causing `ClassCastException` at runtime.

```kotlin
// BAD - lambda loses type info after R8 obfuscation
val callback = { workblock: ByteArray, cost: Int ->
    // ...
    listOf(result, count)  // ClassCastException: Object[] cannot be cast to byte[]
}
wrapper.callAttr("set_callback", callback)

// GOOD - method reference preserves type info
wrapper.callAttr("set_callback", ::myCallbackMethod)

fun myCallbackMethod(workblock: ByteArray, cost: Int): PyObject {
    // ...
}
```

## Returning Values to Python

Return `PyObject` directly. Kotlin collections don't convert cleanly to Python iterables.

```kotlin
// BAD - Kotlin list becomes Arrays$ArrayList, not iterable by Python
return listOf(bytes, count)  // TypeError: 'Arrays$ArrayList' object is not iterable

// BAD - arrayOf has same problem
return arrayOf(bytes, count)  // ClassCastException: Object[] cannot be cast to byte[]

// GOOD - build Python list manually
fun myCallback(data: ByteArray, param: Int): PyObject {
    val py = Python.getInstance()
    val builtins = py.getBuiltins()

    val pyList = builtins.callAttr("list")
    pyList.callAttr("append", resultBytes)
    pyList.callAttr("append", resultInt)
    return pyList
}
```

## ByteArray to Python bytes

Java `ByteArray` doesn't support Python buffer protocol. Convert explicitly for operations like concatenation or hashing.

```kotlin
// BAD - Java ByteArray fails buffer protocol
pyList.callAttr("append", myByteArray)  // TypeError: object supporting the buffer API required

// GOOD - convert to Python bytes
val pyBytes = builtins.callAttr("bytes", myByteArray)
pyList.callAttr("append", pyBytes)
```

## Complete Pattern

```kotlin
private var callbackInstance: MyProcessor? = null

fun setCallback(processor: MyProcessor) {
    callbackInstance = processor
    withWrapper { wrapper ->
        wrapper.callAttr("set_callback", ::processForPython)
    }
}

fun processForPython(inputData: ByteArray, param: Int): PyObject {
    val processor = callbackInstance
        ?: throw IllegalStateException("Processor not initialized")

    val result = runBlocking(Dispatchers.Default) {
        processor.process(inputData, param)
    }

    val py = Python.getInstance()
    val builtins = py.getBuiltins()

    // Convert ByteArray to Python bytes
    val pyBytes = builtins.callAttr("bytes", result.data)

    // Build Python list for unpacking: data, count = callback(input, param)
    val pyList = builtins.callAttr("list")
    pyList.callAttr("append", pyBytes)
    pyList.callAttr("append", result.count)
    return pyList
}
```

## Python Multiprocessing Limitation

Python `multiprocessing` module fails on Android:
- No `sem_open` support (semaphores)
- Android aggressively kills child processes
- Stamp generation and similar CPU-intensive tasks hang or fail silently

Solution: Implement CPU-intensive work in native Kotlin with coroutines, expose via callback.
