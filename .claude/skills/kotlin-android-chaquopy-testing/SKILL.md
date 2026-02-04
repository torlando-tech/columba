---
name: kotlin-android-chaquopy-testing
description: This skill should be used when writing tests for Kotlin Android apps using Chaquopy for Python integration. It provides comprehensive testing patterns, templates, and best practices for unit tests, instrumented tests, Room database tests, Compose UI tests, Hilt DI tests, Python integration tests, and performance tests. Use when creating new tests, expanding test coverage, debugging test failures, or setting up testing infrastructure.
---

# Kotlin Android Chaquopy Testing Skill

## Overview

This skill provides comprehensive testing guidance for Kotlin Android applications that use Chaquopy for Python integration. It covers all testing types from unit tests to end-to-end tests, with special focus on the unique challenges of testing Kotlin-Python integration.

## When to Use This Skill

Use this skill when:
- Writing any type of test for a Kotlin Android + Chaquopy app
- Setting up testing infrastructure
- Expanding test coverage
- Debugging test failures
- Testing Python integration from Kotlin
- Testing threading and GIL behavior
- Creating test doubles (mocks, fakes, stubs)
- Measuring test coverage or performance

## Testing Philosophy

Follow the **70-20-10 rule**:
- **70% Unit Tests**: Fast, isolated, no Android dependencies
- **20% Integration Tests**: Cross-layer, real dependencies where practical
- **10% E2E/UI Tests**: Slow but high confidence, critical flows only

## Quick Start: Common Testing Scenarios

### "I need to test a ViewModel with StateFlow"
1. Use template: `templates/viewmodel-flow-test.kt`
2. Mock dependencies with MockK
3. Test Flow emissions with Turbine
4. Use `runTest` and `TestDispatcher`

### "I need to test a Room DAO"
1. Use template: `templates/room-database-test.kt`
2. Use in-memory database (`.inMemoryDatabaseBuilder()`)
3. Test in `app/src/androidTest/` (requires Android runtime)
4. Close database in `@After`

### "I need to test Python integration"
1. Use template: `templates/python-integration-test.kt`
2. Initialize Python in `@Before`
3. Test must be instrumented (requires device)
4. Handle `PyException` for errors

### "I need to test a Compose screen"
1. Use template: `templates/compose-screen-test.kt`
2. Use `ComposeTestRule`
3. Find nodes with `onNodeWithText()` or `onNodeWithContentDescription()`
4. Perform actions with `.performClick()`, `.performTextInput()`
5. Assert with `.assertIsDisplayed()`, `.assertTextEquals()`

### "I need to test Hilt dependency injection"
1. Use template: `templates/hilt-injection-test.kt`
2. Use `@HiltAndroidTest` annotation
3. Use `HiltAndroidRule`
4. Create test modules with `@TestInstallIn`

### "I need to test a Service with AIDL"
1. Use template: `templates/service-ipc-test.kt`
2. Use `ServiceTestRule`
3. Bind service in `@Before`
4. Test IPC methods with callbacks

## Testing Types

### 1. Unit Tests (JUnit, MockK)
**What**: Test individual components in isolation
**Where**: `app/src/test/` (runs on JVM, no emulator needed)
**Use When**: Testing ViewModels, Repositories, Domain logic, Utilities

**Key Libraries**:
- JUnit 4/5 - Test framework
- MockK - Kotlin-first mocking
- Turbine - Flow testing
- kotlinx-coroutines-test - Coroutine testing

**Templates**:
- `templates/viewmodel-flow-test.kt`
- `templates/repository-test.kt`
- `templates/usecase-test.kt`
- `templates/coroutine-test.kt`

### 2. Instrumented Tests (AndroidJUnit4)
**What**: Tests that require Android framework or device
**Where**: `app/src/androidTest/` (runs on emulator/device)
**Use When**: Testing Python integration, Room database, Services, IPC

**Key Libraries**:
- AndroidJUnit4 - Android test runner
- Espresso - UI testing
- Room Testing - Database testing

**Templates**:
- `templates/python-integration-test.kt`
- `templates/python-threading-test.kt`
- `templates/room-database-test.kt`
- `templates/service-ipc-test.kt`

### 3. Compose UI Tests
**What**: Test Jetpack Compose UI components
**Where**: `app/src/androidTest/` (requires Android runtime)
**Use When**: Testing screen rendering, user interactions, navigation

**Key Library**: androidx.compose.ui:ui-test-junit4

**Templates**:
- `templates/compose-screen-test.kt`
- `templates/compose-navigation-test.kt`
- `templates/compose-state-test.kt`

### 4. Integration Tests
**What**: Test multiple layers working together
**Where**: Can be unit or instrumented depending on dependencies
**Use When**: Testing ViewModel + Repository + Database flows

**Templates**:
- `templates/viewmodel-repository-database-test.kt`
- `templates/service-python-callback-test.kt`

### 5. Performance Tests
**What**: Measure and benchmark performance
**Where**: `app/src/androidTest/` (Jetpack Benchmark)
**Use When**: Measuring initialization time, IPC latency, Python call overhead

**Templates**:
- `templates/benchmark-test.kt`
- `templates/python-performance-test.kt`

## Chaquopy-Specific Testing

### Critical Constraint: Python Requires Device/Emulator

**Chaquopy Python cannot run in JVM-based unit tests**. All Python integration tests MUST be instrumented tests (`app/src/androidTest/`).

### Python Initialization Pattern

```kotlin
@RunWith(AndroidJUnit4::class)
class PythonIntegrationTest {
    @Before
    fun setup() {
        if (!Python.isStarted()) {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            Python.start(AndroidPlatform(context))
        }

        val py = Python.getInstance()
        wrapper = py.getModule("your_module").callAttr("YourClass", args)
    }
}
```

### Testing Python Threading and GIL

The Global Interpreter Lock (GIL) serializes Python bytecode execution. Test that:
1. Multiple Kotlin threads can call Python safely
2. Long-running Python operations release GIL during I/O
3. Python callbacks execute on correct thread

**Template**: `templates/python-threading-test.kt`

### Testing Type Conversions

Test Kotlin ↔ Python type conversions:
- `String` ↔ `str`
- `Int` ↔ `int`
- `ByteArray` ↔ `bytes`
- `List` ↔ `list`
- `Map` ↔ `dict`

### Handling PyException

Python exceptions propagate as `PyException` in Kotlin:

```kotlin
@Test
fun testPythonError() {
    assertThrows<PyException> {
        wrapper.callAttr("method_that_raises")
    }
}
```

## Using Templates

All templates are in `templates/` directory. Each template includes:
- Complete imports
- Proper annotations
- Setup/teardown methods
- Example test methods
- Inline comments explaining patterns

**To use a template**:
1. Copy template file to your test directory
2. Rename class and file
3. Update imports and dependencies
4. Customize test methods
5. Run and verify

**Available templates**:
1. `viewmodel-flow-test.kt` - ViewModel with StateFlow testing
2. `repository-test.kt` - Repository with mocked dependencies
3. `usecase-test.kt` - Domain use case testing
4. `coroutine-test.kt` - Coroutine patterns
5. `room-database-test.kt` - Room DAO testing
6. `hilt-injection-test.kt` - Hilt DI testing
7. `python-integration-test.kt` - Python method call testing
8. `python-threading-test.kt` - Python threading safety
9. `service-ipc-test.kt` - Service and AIDL testing
10. `compose-screen-test.kt` - Compose UI testing
11. `compose-navigation-test.kt` - Compose navigation
12. `compose-state-test.kt` - Compose state management
13. `viewmodel-repository-database-test.kt` - Integration testing
14. `service-python-callback-test.kt` - Service + Python integration
15. `benchmark-test.kt` - Performance benchmarking
16. `python-performance-test.kt` - Python call performance

## Using References

Reference documentation is in `references/` directory:

- `chaquopy-testing.md` - Chaquopy-specific patterns and limitations
- `android-testing-fundamentals.md` - Android testing basics
- `kotlin-coroutines-testing.md` - Coroutine and Flow testing
- `compose-testing.md` - Compose UI testing
- `room-testing.md` - Room database testing
- `hilt-testing.md` - Hilt DI testing
- `testing-patterns.md` - General testing best practices
- `chaquopy-limitations.md` - Known Chaquopy issues and workarounds

**When to consult references**:
- Need deeper understanding of a pattern
- Debugging complex test failures
- Learning new testing APIs
- Understanding framework limitations

## Using Scripts

Helper scripts are in `scripts/` directory:

- `analyze_test_coverage.sh` - Generate and analyze coverage reports
- `run_all_tests.sh` - Run complete test suite

**Usage**:
```bash
# Analyze coverage
scripts/analyze_test_coverage.sh

# Run all tests
scripts/run_all_tests.sh
```

## Best Practices

### Test Naming
Use descriptive names that explain behavior:
```kotlin
// Good
@Test
fun `when user clicks button, navigation occurs`()

// Bad
@Test
fun testButton()
```

### AAA Pattern
Structure tests with Arrange-Act-Assert:
```kotlin
@Test
fun test() {
    // Arrange
    val input = setupTestData()
    coEvery { dependency.method() } returns expected

    // Act
    val result = systemUnderTest.method(input)

    // Assert
    assertEquals(expected, result)
}
```

### One Assertion Per Test
Keep tests focused on single behavior

### Test Data Builders
Create builders for complex test data

## Performance Targets

| Metric | Target | How to Test |
|--------|--------|-------------|
| Initialization | < 3s | `templates/benchmark-test.kt` |
| IPC latency | < 10ms | Measure in integration test |
| Python call | < 10ms avg | `templates/python-performance-test.kt` |
| Unit test suite | < 30s | Fast feedback loop |
| Full test suite | < 5min | CI execution time |

## Coverage Goals

- **Overall**: 80%+
- **Critical paths**: 90%+
- **ViewModels**: 90%+
- **Repositories**: 85%+
- **Domain logic**: 95%+

## Additional Resources

### Online Documentation
- Android Testing: https://developer.android.com/training/testing
- Compose Testing: https://developer.android.com/jetpack/compose/testing
- Room Testing: https://developer.android.com/training/data-storage/room/testing-db
- Chaquopy: https://chaquo.com/chaquopy/doc/current/
- kotlinx-coroutines-test: https://kotlin.github.io/kotlinx.coroutines/kotlinx-coroutines-test/
- MockK: https://mockk.io/
- Turbine: https://github.com/cashapp/turbine

### Via context7 MCP
Request latest documentation:
- `/android/testing` - Android testing fundamentals
- `/androidx/compose` - Compose testing APIs
- `/androidx/room` - Room testing
- `/jetbrains/kotlin` - Coroutines testing

---

*Last Updated: 2025-10-27*
*Version: 1.0.0*
