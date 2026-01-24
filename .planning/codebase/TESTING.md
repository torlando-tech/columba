# Testing Patterns

**Analysis Date:** 2026-01-23

## Test Framework

**Runner:**
- JUnit 4 with Robolectric for unit tests
- AndroidJUnit4 (AndroidX Test) for instrumentation tests
- Config: `app/build.gradle.kts` specifies `AndroidJUnitRunner`

**Assertion Library:**
- `org.junit.Assert.*` for all assertions
- Common: `assertEquals()`, `assertTrue()`, `assertFalse()`, `assertNull()`, `assertNotNull()`

**Async/Coroutine Testing:**
- `kotlinx.coroutines.test.runTest` for coroutine-based tests
- `@OptIn(ExperimentalCoroutinesApi::class)` annotation required
- `InstantTaskExecutorRule` for LiveData/Flow testing: `@get:Rule val instantExecutorRule = InstantTaskExecutorRule()`
- Test dispatcher setup: `val testDispatcher = StandardTestDispatcher()`

**Run Commands:**
```bash
./gradlew testDebugUnitTest              # Run all unit tests
./gradlew testDebugUnitTest --watch      # Watch mode (if supported)
./gradlew jacocoTestReport               # Generate unified coverage report
./gradlew :app:testDebugUnitTest         # Run tests for app module only
./gradlew connectedAndroidTest           # Run instrumentation tests on device
```

## Test File Organization

**Location:**
- **Unit tests**: `app/src/test/java/com/lxmf/messenger/{feature}/` (co-located by feature)
- **Instrumentation tests**: `app/src/androidTest/java/com/lxmf/messenger/{feature}/`
- Tests live in same package structure as production code

**Naming:**
- `{ClassName}Test.kt` for standard unit tests
- `{ClassName}{Scenario}Test.kt` for scenario-specific tests (e.g., `InterfaceDatabaseRaceConditionTest.kt`)
- Test classes: `class {Name}Test { ... }`

**Structure:**
```
app/src/
├── test/java/com/lxmf/messenger/            # Unit tests
│   ├── data/
│   │   ├── MessageOrderingTest.kt
│   │   ├── model/
│   │   │   └── ModemPresetTest.kt
│   │   └── repository/
│   │       └── ConversationRepositoryTest.kt
│   ├── service/
│   ├── util/
│   └── viewmodel/
├── androidTest/java/com/lxmf/messenger/     # Integration/instrumentation tests
│   ├── data/database/
│   │   └── InterfaceDatabaseRaceConditionTest.kt
│   ├── initialization/
│   └── ui/
└── main/
```

## Test Structure

**Suite Organization:**
```kotlin
@OptIn(ExperimentalCoroutinesApi::class)
class MessageOrderingTest {
    @get:Rule
    val instantExecutorRule = InstantTaskExecutorRule()

    private lateinit var conversationRepository: ConversationRepository
    private val testPeerHash = "abcd1234"
    private val testPeerName = "Test Peer"

    @Before
    fun setup() {
        conversationRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `test description in backticks`() = runTest {
        // Arrange
        val expected = listOf(/* data */)

        // Act
        val result = conversationRepository.getMessages(testPeerHash).first()

        // Assert
        assertEquals(expected, result)
    }
}
```

**Patterns:**
- `@Before` setup: Initialize mocks and test fixtures
- `@After` teardown: Clear mocks with `clearAllMocks()`, reset dispatchers with `Dispatchers.resetMain()`
- Backtick test names: `` `describe behavior in natural language`() ``
- Comments in test names: Avoid; use descriptive names instead
- Comments in setup: Explain non-obvious test data creation

**Test Organization within Files:**
- Group related tests with section comments: `// ========== Scenario Name Tests ==========`
- Arrange-Act-Assert pattern (AAA):
  - Arrange: Setup test data and mocks
  - Act: Call the code being tested
  - Assert: Verify results with assertions and explanatory messages

## Mocking

**Framework:** `io.mockk.mockk` (MockK)

**Patterns:**
```kotlin
// Create relaxed mock (no need to specify every call)
val mockRepository: ConversationRepository = mockk(relaxed = true)

// Specify return value for function
every { mockRepository.getMessages(testPeerHash) } returns flowOf(messages)

// Verify function was called
coVerify { mockRepository.saveMessage(message) }

// Clear all mocks after test
clearAllMocks()
```

**What to Mock:**
- External dependencies: repositories, services, DAOs, network clients
- Complex objects with many dependencies
- Android system services passed as parameters
- Any class marked with `@Inject` (dependencies)

**What NOT to Mock:**
- Pure data classes: `BleConnectionInfo`, `Message`, `ModemPreset`
- Simple enums and sealed classes used as return types
- Model objects created in test (use real instances)
- Value objects representing test data

**Mocking Async Code:**
```kotlin
// For suspend functions
coEvery { mockDao.messageExists(id, hash) } returns false

// For Flow-returning functions
every { mockRepository.getMessages(hash) } returns flowOf(messages)

// For LiveData (rarely used)
every { mockLiveData.value } returns expectedValue
```

**Mock Verification:**
```kotlin
// Verify was called with specific args
coVerify { mockDao.insert(message) }

// Verify was called N times
coVerify(exactly = 1) { mockDao.saveMessage(any()) }

// Verify was NOT called
coVerify(inverse = true) { mockDao.delete(any()) }
```

## Fixtures and Factories

**Test Data:**
```kotlin
// In-test data creation - simple cases
private val testPeerHash = "abcd1234"
private val testPeerName = "Test Peer"

private val testIdentity = LocalIdentityEntity(
    identityHash = testIdentityHash,
    displayName = "Test Identity",
    destinationHash = "dest_hash",
    filePath = "/path/to/identity",
    createdTimestamp = 1000L,
    lastUsedTimestamp = 2000L,
    isActive = true,
)

// Inline list creation in test
val messages = listOf(
    DataMessage(
        id = "msg1",
        destinationHash = testPeerHash,
        content = "First message",
        timestamp = 1000L,
        isFromMe = false,
        status = "delivered",
    ),
    DataMessage(
        id = "msg2",
        destinationHash = testPeerHash,
        content = "Second message",
        timestamp = 2000L,
        isFromMe = true,
        status = "sent",
    ),
)
```

**Location:**
- Test fixtures defined in `@Before` setup method or as class properties
- Simple constants as private class properties: `private val testPeerHash = "abcd1234"`
- Complex fixtures created in individual test methods when only used once

**Factory Pattern (implicit):**
- No dedicated factory classes found
- Test data constructed inline in tests
- Benefit: Test data is self-documenting; readers see exact structure being tested

## Coverage

**Requirements:** No minimum coverage enforced (advisory mode)

**View Coverage:**
```bash
./gradlew jacocoTestReport
# HTML report: app/build/reports/jacoco/jacocoTestReport/html/index.html
```

**Exclusions from Coverage** (in `build.gradle.kts`):
- Generated classes: `R.class`, `BuildConfig.*`, `Manifest*.*`
- Hilt-generated: `Hilt_*.*`, `*_Factory.*`, `*_MembersInjector.*`
- Test classes: `**/*Test*.*`
- Only debug unit tests included (Robolectric issues with release builds)

## Test Types

**Unit Tests:**
- Scope: Single class or function in isolation with mocked dependencies
- Location: `app/src/test/java/`
- Examples:
  - `MessageOrderingTest.kt` - Tests message ordering logic with mocked repository
  - `ModemPresetTest.kt` - Tests enum matching and validation
  - `ConversationRepositoryTest.kt` - Tests repository with mocked DAOs

**Integration Tests:**
- Scope: Multiple components working together; can use real database/objects
- Location: `app/src/androidTest/java/`
- Examples:
  - `InterfaceDatabaseRaceConditionTest.kt` - Tests Room database with real schema
  - `BlePermissionRestartTest.kt` - Tests BLE permission handling across app lifecycle
  - `ServiceProcessInitializationTest.kt` - Tests service startup sequence

**E2E Tests:**
- Not observed in current codebase
- Would test full app flows from UI through all layers

## Common Patterns

**Async Testing:**
```kotlin
@Test
fun `async operation completes`() = runTest {
    // runTest provides TestScope with test dispatcher

    // Act: Call suspend function
    val result = repository.getMessages(hash).first()

    // Assert
    assertEquals(expected, result)
}
```

**Error Testing:**
```kotlin
@Test
fun `invalid preset parameters return null`() {
    val preset = ModemPreset.findByParams(
        spreadingFactor = 12,
        bandwidth = 500_000,  // Invalid combination
        codingRate = 5,
    )
    assertNull(preset)
}

@Test
fun `error parsing JSON returns empty list`() = runTest {
    every { mockRepository.getMessages(any()) } throws Exception("Invalid JSON")

    val result = repository.parseConnectionsJson(invalidJson)

    assertEquals(emptyList(), result)
}
```

**State Machine / Flow Testing:**
```kotlin
@Test
fun `state transitions correctly`() = runTest {
    val state = BleConnectionsState.Loading

    // Act: Trigger state change
    val nextState = when (state) {
        BleConnectionsState.BluetoothDisabled -> BleConnectionsState.Loading
        BleConnectionsState.Loading -> BleConnectionsState.Success(emptyList())
        else -> state
    }

    // Assert
    assertTrue(nextState is BleConnectionsState.Success)
}
```

**Collection Validation:**
```kotlin
@Test
fun `rapid message exchange maintains correct order`() = runTest {
    val result = repository.getMessages(hash).first()

    // Verify size
    assertEquals(5, result.size)

    // Verify ordering
    for (i in 0 until result.size - 1) {
        assertTrue(
            "Message $i should come before ${i + 1}",
            result[i].timestamp < result[i + 1].timestamp,
        )
    }
}
```

**Scenario-Based Test with Narrative:**
```kotlin
@Test
fun `received messages use local reception time not sender time`() = runTest {
    // This test verifies the fix for clock skew issues
    // Scenario:
    // - User A sends message at 10:00:00 (their time)
    // - User B's clock is 30 seconds behind
    // - User B receives message at 09:59:40 (their time, if using sender's timestamp)
    // - User B replies at 09:59:50 (their time)
    //
    // BUG (before fix): Reply appears ABOVE the original message (09:59:50 > 09:59:40)
    // FIX (after fix): Reply appears BELOW using local reception time

    val currentTime = System.currentTimeMillis()

    val receivedMessage = DataMessage(
        id = "msg1",
        destinationHash = testPeerHash,
        content = "Message from A",
        timestamp = currentTime - 100,
        isFromMe = false,
        status = "delivered",
    )

    val sentMessage = DataMessage(
        id = "msg2",
        destinationHash = testPeerHash,
        content = "Reply from B",
        timestamp = currentTime,
        isFromMe = true,
        status = "sent",
    )

    every { mockRepository.getMessages(testPeerHash) } returns flowOf(listOf(receivedMessage, sentMessage))

    val result = mockRepository.getMessages(testPeerHash).first()

    assertEquals(2, result.size)
    assertTrue("Received message should come first", result[0].timestamp < result[1].timestamp)
    assertFalse("First message is from peer", result[0].isFromMe)
    assertTrue("Second message is from me", result[1].isFromMe)
}
```

## Test Discipline

**Golden Rules:**
- Tests MUST execute production code - no mocking the entire system being tested
- Tests fail if production code breaks, not due to test setup fragility
- Clear test names that read like requirements: `` `messages ordered by timestamp`() ``
- Comprehensive assertions with meaningful failure messages

**Ignored Tests:**
- `@Ignore("Reason")` used for flaky timing-sensitive tests
- Example: `InterfaceDatabaseRaceConditionTest.kt` marks timing test as ignored (flaky on CI with resource constraints)

---

*Testing analysis: 2026-01-23*
