# Columba Project Instructions for Claude Code

## Common Pitfalls

### Chaquopy: Kotlin/Java Lists to Python

When passing a Kotlin/Java List or ArrayList to a Python function via Chaquopy, you **must** convert it to a Python list first. Otherwise you'll get `'ArrayList' object is not iterable`.

**Wrong:**
```kotlin
wrapperManager.withWrapper { wrapper ->
    val result = wrapper.callAttr("some_python_function", myKotlinList)
}
```

**Correct:**
```kotlin
wrapperManager.withWrapper { wrapper ->
    // Convert to Python list (Java ArrayList doesn't serialize properly to Python)
    val pyList = com.chaquo.python.Python.getInstance()
        .builtins.callAttr("list", myKotlinList.toTypedArray())
    val result = wrapper.callAttr("some_python_function", pyList)
}
```
