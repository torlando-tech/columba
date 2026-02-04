# Testing Patterns and Best Practices

## Overview

General testing patterns and best practices for Kotlin Android development.

## Test Structure

### AAA Pattern (Arrange-Act-Assert)

```kotlin
@Test
fun testFeature() {
    // Arrange - Set up test conditions
    val input = createTestInput()
    coEvery { mock.method() } returns expected

    // Act - Execute the code under test
    val result = systemUnderTest.method(input)

    // Assert - Verify the outcome
    assertEquals(expected, result)
}
```

### Given-When-Then Pattern

```kotlin
@Test
fun `given user is logged in when logout clicked then user is logged out`() {
    // Given
    val user = createLoggedInUser()
    setupMocks(user)

    // When
    viewModel.logout()
    advanceUntilIdle()

    // Then
    assertTrue(viewModel.isLoggedOut.value)
}
```

## Test Naming Conventions

### Descriptive Test Names

```kotlin
// ✅ Good: Describes behavior
@Test
fun `when network error occurs, error message is displayed`()

@Test
fun `clicking send button saves message to database`()

// ❌ Bad: Vague or implementation-focused
@Test
fun testError()

@Test
fun testSendButton()
```

### Structured Naming

```kotlin
// Pattern: `method_stateUnderTest_expectedBehavior`
@Test
fun login_withValidCredentials_returnsSuccess()

@Test
fun fetchData_whenNetworkUnavailable_returnsError()
```

## Test Data Builders

### Builder Functions

```kotlin
fun createTestMessage(
    id: String = "test-id-${UUID.randomUUID()}",
    content: String = "test content",
    sender: String = "test-sender",
    timestamp: Long = System.currentTimeMillis(),
    isRead: Boolean = false
) = Message(id, content, sender, timestamp, isRead)

// Usage
@Test
fun test() {
    val message = createTestMessage(isRead = true)
    // Use in test
}
```

### Builder Pattern for Complex Objects

```kotlin
class TestMessageBuilder {
    private var id: String = "test-id"
    private var content: String = "test content"
    private var isRead: Boolean = false

    fun withId(id: String) = apply { this.id = id }
    fun withContent(content: String) = apply { this.content = content }
    fun asRead() = apply { this.isRead = true }

    fun build() = Message(id, content, sender, timestamp, isRead)
}

// Usage
val message = TestMessageBuilder()
    .withId("custom-id")
    .withContent("custom content")
    .asRead()
    .build()
```

## Mocking with MockK

### Basic Mocking

```kotlin
val mock: Repository = mockk()

// Stub method return
every { mock.getData() } returns testData

// Verify method called
verify { mock.getData() }

// Verify method called with specific args
verify { mock.saveData(testData) }

// Verify method never called
verify(exactly = 0) { mock.deleteData() }
```

### Suspending Functions

```kotlin
val mock: Repository = mockk()

// Stub suspend function
coEvery { mock.fetchData() } returns testData

// Verify suspend function called
coVerify { mock.fetchData() }
```

### Relaxed Mocks

```kotlin
// Relaxed mock returns default values for unstubbed methods
val mock: Repository = mockk(relaxed = true)

mock.getData() // Returns default value (null, 0, false, etc.)
```

## Flow Testing with Turbine

### Testing Single Emission

```kotlin
@Test
fun testFlowEmission() = runTest {
    flow.test {
        assertEquals(expected, awaitItem())
        awaitComplete()
    }
}
```

### Testing Multiple Emissions

```kotlin
@Test
fun testMultipleEmissions() = runTest {
    flow.test {
        assertEquals(value1, awaitItem())
        assertEquals(value2, awaitItem())
        assertEquals(value3, awaitItem())
        awaitComplete()
    }
}
```

### Testing StateFlow

```kotlin
@Test
fun testStateFlow() = runTest {
    stateFlow.test {
        // Skip initial value
        skipItems(1)

        // Trigger change
        viewModel.action()
        advanceUntilIdle()

        // Verify new value
        assertEquals(expected, awaitItem())
    }
}
```

## Coroutine Testing

### Using runTest

```kotlin
@Test
fun testCoroutine() = runTest {
    // runTest uses TestDispatcher - controls virtual time
    viewModel.action()

    // Advance until all coroutines complete
    advanceUntilIdle()

    // Assert
    assertEquals(expected, viewModel.state.value)
}
```

### Testing Delays

```kotlin
@Test
fun testDelay() = runTest {
    val job = launch {
        delay(5000) // Virtual time
        performAction()
    }

    // Advance virtual time by 5 seconds instantly
    advanceTimeBy(5000)

    // Verify action performed
    assertTrue(actionPerformed)
}
```

### Testing Timeouts

```kotlin
@Test
fun testTimeout() = runTest {
    assertFailsWith<TimeoutCancellationException> {
        withTimeout(1000) {
            delay(2000) // Takes longer than timeout
        }
    }
}
```

## One Assertion Per Test

### Keep Tests Focused

```kotlin
// ✅ Good: One behavior per test
@Test
fun `saves data to database`() {
    repository.save(data)
    verify { database.insert(data) }
}

@Test
fun `emits success state`() {
    assertEquals(UiState.Success, viewModel.state.value)
}

// ❌ Bad: Testing multiple behaviors
@Test
fun `saveData works`() {
    repository.save(data)
    verify { database.insert(data) }
    assertEquals(UiState.Success, viewModel.state.value)
    assertTrue(eventEmitted)
}
```

## Test Independence

### Each Test Should Be Independent

```kotlin
// ❌ Bad: Shared mutable state
class BadTest {
    companion object {
        val sharedData = mutableListOf<String>()
    }

    @Test
    fun test1() {
        sharedData.add("item") // Affects test2!
    }

    @Test
    fun test2() {
        assertEquals(0, sharedData.size) // Fails if test1 ran first
    }
}

// ✅ Good: Fresh state per test
class GoodTest {
    private lateinit var testData: MutableList<String>

    @Before
    fun setup() {
        testData = mutableListOf() // Fresh for each test
    }

    @Test
    fun test1() {
        testData.add("item")
        assertEquals(1, testData.size)
    }

    @Test
    fun test2() {
        assertEquals(0, testData.size) // Always passes
    }
}
```

## Testing Edge Cases

### Boundary Value Testing

```kotlin
@Test
fun `handles empty input`() {
    val result = process(emptyList())
    assertTrue(result.isEmpty())
}

@Test
fun `handles null input`() {
    val result = process(null)
    assertNull(result)
}

@Test
fun `handles maximum input`() {
    val largeInput = (1..10_000).toList()
    val result = process(largeInput)
    assertNotNull(result)
}
```

### Error Case Testing

```kotlin
@Test
fun `handles network error gracefully`() {
    coEvery { repository.fetchData() } throws IOException("Network error")

    viewModel.loadData()
    advanceUntilIdle()

    assertTrue(viewModel.state.value is UiState.Error)
}
```

## Test Coverage Strategy

### What to Test

**High Priority** (always test):
- ✅ Public API methods
- ✅ Business logic
- ✅ Error handling
- ✅ State transitions
- ✅ Edge cases (null, empty, max)

**Medium Priority** (test when complex):
- ⚠️ Data transformations
- ⚠️ Validation logic
- ⚠️ Formatting functions

**Low Priority** (optional):
- ℹ️ Simple getters/setters
- ℹ️ Data classes (auto-generated)
- ℹ️ Trivial delegates

### What NOT to Test

- Framework code (Android SDK, libraries)
- Auto-generated code (Room DAOs, Hilt modules)
- Third-party libraries

## Test Organization

### Package Structure

```
app/src/test/ (unit tests)
├── com/package/
    ├── ui/
    │   └── viewmodel/
    │       ├── MainViewModelTest.kt
    │       └── SettingsViewModelTest.kt
    ├── data/
    │   └── repository/
    │       └── MessageRepositoryTest.kt
    └── domain/
        └── usecase/
            └── SendMessageUseCaseTest.kt

app/src/androidTest/ (instrumented tests)
├── com/package/
    ├── data/
    │   └── dao/
    │       ├── MessageDaoTest.kt
    │       └── ConversationDaoTest.kt
    ├── python/
    │   ├── PythonIntegrationTest.kt
    │   └── PythonThreadingSafetyTest.kt
    └── ui/
        └── screen/
            └── MainScreenTest.kt
```

## Common Pitfalls

### Pitfall 1: Not Using runTest

```kotlin
// ❌ Wrong
@Test
fun test() {
    viewModel.action() // Coroutine not executed!
    assertEquals(expected, viewModel.state.value) // Fails
}

// ✅ Correct
@Test
fun test() = runTest {
    viewModel.action()
    advanceUntilIdle() // Process coroutines
    assertEquals(expected, viewModel.state.value)
}
```

### Pitfall 2: Not Resetting Main Dispatcher

```kotlin
// ❌ Wrong - Dispatcher leaked to other tests
@Before
fun setup() {
    Dispatchers.setMain(testDispatcher)
}

// ✅ Correct - Clean up after test
@After
fun teardown() {
    Dispatchers.resetMain()
}
```

### Pitfall 3: Testing Implementation, Not Behavior

```kotlin
// ❌ Wrong: Tests internal implementation
@Test
fun testInternalVariable() {
    assertEquals("value", viewModel.internalField)
}

// ✅ Correct: Tests observable behavior
@Test
fun testPublicBehavior() {
    viewModel.action()
    assertEquals(expected, viewModel.publicState.value)
}
```

---

*General testing best practices for Kotlin Android development*
