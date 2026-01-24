# Coding Conventions

**Analysis Date:** 2026-01-23

## Naming Patterns

**Files:**
- `PascalCase` for class/interface files: `BleStatusRepository.kt`, `InterfaceDatabase.kt`, `MessageOrderingTest.kt`
- Test files: `{ClassName}Test.kt` (unit tests) or `{ClassName}RaceConditionTest.kt` (specific scenario tests)
- `camelCase` for Kotlin files with multiple internal functions (less common)

**Functions:**
- `camelCase` for all functions, both public and private: `getConnectedPeersFlow()`, `parseConnectionsJson()`, `stateToString()`
- Test functions use backtick-quoted descriptive names: `` `messages are ordered by timestamp ascending`() ``, `` `findByParams finds SHORT_TURBO`() ``
- Enforced by detekt rule `FunctionNaming` with pattern `[a-z][a-zA-Z0-9]*`, except for `@Composable` functions which are exempt

**Variables:**
- `camelCase` for all variables: `testPeerHash`, `adapterState`, `connectionType`
- Private class variables with backing fields: `private val bleBridge`
- Enforced by detekt rule `VariableNaming` with pattern `[a-z][A-Za-z0-9]*`

**Types:**
- `PascalCase` for data classes, enums, sealed classes: `BleConnectionInfo`, `ConnectionType`, `BleConnectionsState`
- Top-level constants in `UPPER_SNAKE_CASE`: `TAG` constant (enforced by detekt rule `TopLevelPropertyNaming`)

**Enums and Sealed Classes:**
- Use descriptive enum members: `EXCELLENT`, `GOOD`, `FAIR`, `POOR` in `SignalQuality`
- Sealed class variants use meaningful names: `BluetoothDisabled`, `Loading`, `Success`, `Error`

## Code Style

**Formatting:**
- 4-space indentation (per `.editorconfig`)
- 160-character max line length for Kotlin (enforced by detekt)
- LF line endings, UTF-8 encoding
- Insert final newline in all files
- Trim trailing whitespace

**Linting:**
- Tool: `detekt` (1.23.8) with custom Columba rules
- Tool: `ktlint` (1.0.1) in advisory mode (non-blocking)
- Baseline: `detekt-baseline.xml` captures pre-existing issues; new code must pass checks
- Run verification: `./gradlew detektCheck ktlintCheck`
- Update baseline after intentional fixes: `./gradlew detektBaseline`

**Quality Thresholds (detekt):**
- Cyclomatic complexity: max 18 (ignores `@Composable`)
- Function length: max 80 lines (ignores `@Composable`)
- Class size: max 600 lines
- Parameter list: 8 params (functions), 10 params (constructors) - ignored for `@Composable`
- Nested block depth: max 5 levels
- Functions per file: max 15 (classes), max 25 (interfaces)
- Max line length: 200 characters (detekt override vs editorconfig's 160)
- Return count: max 3 per function
- Throw count: max 2 per function
- No wildcard imports (except in test and androidTest)

## Import Organization

**Order:**
1. Package declaration
2. Blank line
3. Standard library imports (e.g., `java.*`, `kotlin.*`)
4. Third-party imports (Android framework, Dagger, kotlinx, etc.)
5. Project-local imports (`com.lxmf.messenger.*`)
6. Blank line
7. Code

**Pattern observed:** Imports grouped by origin with blank lines between groups.

Example from `BleStatusRepository.kt`:
```kotlin
package com.lxmf.messenger.data.repository

import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.util.Log
import com.lxmf.messenger.data.model.BleConnectionInfo
import com.lxmf.messenger.data.model.BleConnectionsState
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import javax.inject.Inject
import javax.inject.Singleton
```

**Path Aliases:**
- Not used; fully qualified imports throughout

**Wildcard Imports:**
- Forbidden in production code (detekt enforces)
- Allowed in test and androidTest only

## Error Handling

**Pattern - Try/Catch with Logging:**
```kotlin
try {
    // operation
    return result
} catch (e: Exception) {
    Log.e(TAG, "Error message describing what failed", e)
    // Return default/empty value (never throw up)
    return emptyList()
}
```

**Pattern - Explicit Exception Types:**
- Prefer catching specific exceptions where possible
- Detekt enforces: `TooGenericExceptionCaught` disallows `RuntimeException`, `Throwable`, `Exception` without justification
- Allowed with regex pattern: `_|(ignore|expected).*` (e.g., `_e` or `ignoredException`)

**Pattern - Graceful Degradation:**
- Functions return safe defaults on error: `emptyList()`, `null`, `0`
- Never crash or throw exceptions to caller
- Log full exception with context for debugging

Example from `BleStatusRepository.kt`:
```kotlin
private fun parseConnectionsJson(jsonString: String): List<BleConnectionInfo> {
    return try {
        // parse logic
        connections
    } catch (e: Exception) {
        Log.e(TAG, "Error parsing connections JSON", e)
        emptyList()  // Safe default
    }
}
```

## Logging

**Framework:** Android `Log` class (no third-party logging framework)

**Tag Pattern:**
- Define as companion object constant: `private const val TAG = "ClassName"`
- Must be present in all classes that use logging (detekt custom rule: `BleLoggingTag`)

**Patterns:**
- `Log.d(TAG, "message")` - Debug: informational messages, state transitions
- `Log.w(TAG, "message")` - Warning: unexpected but recoverable conditions
- `Log.e(TAG, "message", exception)` - Error: failures with full exception context
- `Log.v(TAG, "message")` - Verbose: detailed diagnostic info (only when needed)

**Logging in Repositories:**
- Log state changes: `"Bluetooth turning on - returning Loading state"`
- Log data received: `"Event-driven: received ${connections.size} BLE connections"`
- Log errors with context: `Log.e(TAG, "Error parsing connections JSON", e)`

## Comments

**When to Comment:**
- Non-obvious logic or business rules: Explain the "why" not the "what"
- Complex parsing or configuration: Document data format expectations
- TODOs for future work (sparingly)
- Do NOT comment obvious code: `val name = obj.name // set name`

**JSDoc/KDoc:**
- Use KDoc format (/** */) for public classes, interfaces, and functions
- Include `@param` and `@return` tags for public APIs
- Document intent and expected behavior, not implementation details

Example from `BleStatusRepository.kt`:
```kotlin
/**
 * Get a flow of BLE connection state that combines adapter state with connection data.
 * Uses event-driven updates from the service (< 100ms latency).
 *
 * @return Flow emitting BleConnectionsState
 */
fun getConnectedPeersFlow(): Flow<BleConnectionsState>
```

Example from `BleConnectionInfo.kt`:
```kotlin
/**
 * Returns a shortened version of the identity hash (first 8 characters).
 */
val shortIdentityHash: String
    get() = identityHash.take(8)
```

## Function Design

**Size Guidelines:**
- Prefer functions under 80 lines (enforced by detekt)
- Extract helper functions for repeated logic
- One responsibility per function

**Parameters:**
- Max 8 parameters in regular functions, 10 in constructors (enforced by detekt)
- Use data classes to bundle related parameters
- Order: required params first, optional/default params last

**Return Values:**
- Prefer explicit return types on public functions
- Max 3 return statements per function (enforced by detekt)
- Use `sealed class` for complex return types with states: `BleConnectionsState`

Example pattern from `BleConnectionInfo.kt`:
```kotlin
val signalQuality: SignalQuality
    get() =
        when {
            rssi > -50 -> SignalQuality.EXCELLENT
            rssi > -70 -> SignalQuality.GOOD
            rssi > -85 -> SignalQuality.FAIR
            else -> SignalQuality.POOR
        }
```

## Module Design

**Exports:**
- All public classes in a module should serve a clear external API
- Keep implementation details package-private (`internal` or default visibility)

**Barrel Files:**
- Not observed; each class in its own file

**Package Organization:**
- `data.repository`: high-level data access (repositories like `BleStatusRepository`)
- `data.model`: data classes, enums, sealed classes representing domain concepts
- `data.database`: Room entities, DAOs, migrations
- `reticulum.protocol`: protocol communication abstractions
- `di`: dependency injection (Dagger Hilt modules)
- `service`: Android services and lifecycle management
- `ui`: Compose screens and UI components

---

*Convention analysis: 2026-01-23*
