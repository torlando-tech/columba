---
status: diagnosed
trigger: "coroutine-test-flakiness"
created: 2026-01-23T00:00:00Z
updated: 2026-01-23T00:10:00Z
---

## Current Focus

hypothesis: ROOT CAUSE IDENTIFIED - ViewModel is instantiated in @Before (outside runTest), launching init block coroutines on shared TestScheduler, which leak into subsequent tests
test: Confirmed by comparing RNodeWizardViewModelTest (broken) vs MessagingViewModelTest (working pattern)
expecting: Fix involves creating ViewModel inside runTest scope for each test
next_action: Document findings and create plan for proper fix

## Symptoms

expected: Tests pass consistently when run as part of the full suite
actual: Random tests fail with `UncaughtExceptionsBeforeTest` - the specific test that fails changes between runs
errors: kotlinx.coroutines.test.UncaughtExceptionsBeforeTest: There were uncaught exceptions before the test started
reproduction: Run `./gradlew :app:testDebugUnitTest` multiple times - different tests in RNodeWizardViewModelTest fail randomly
started: Tests pass when run individually or as a class, fail when run with other ViewModel tests

## Eliminated

- hypothesis: Fresh TestDispatcher per test would fix it
  evidence: Already using UnconfinedTestDispatcher() per test in @Before, still fails
  timestamp: 2026-01-23 (user tried this)

- hypothesis: Using UnconfinedTestDispatcher instead of StandardTestDispatcher would fix it
  evidence: Already switched, still fails randomly
  timestamp: 2026-01-23 (user tried this)

- hypothesis: advanceUntilIdle() in tearDown would clean up
  evidence: User tried this, didn't resolve the issue
  timestamp: 2026-01-23 (user tried this)

## Evidence

- timestamp: 2026-01-23T00:01:00Z
  checked: RNodeWizardViewModelTest.kt setup/tearDown
  found: ViewModel instantiated in @Before (line 104) - OUTSIDE runTest scope
  implication: ViewModel init block runs immediately, launching coroutines on shared TestScheduler

- timestamp: 2026-01-23T00:02:00Z
  checked: RNodeWizardViewModel.kt init block
  found: ViewModel has init block that launches coroutines via viewModelScope.launch (442+ lines show many viewModelScope.launch calls)
  implication: These coroutines are launched during construction, not controlled by individual test's runTest

- timestamp: 2026-01-23T00:03:00Z
  checked: MessagingViewModelTest.kt (working test)
  found: Uses createTestViewModel() function (line 154) called INSIDE each test's runTest scope (line 171, 182)
  implication: This is the correct pattern - ViewModel creation must happen inside runTest to properly track coroutines

- timestamp: 2026-01-23T00:04:00Z
  checked: Kotlin coroutines GitHub issues #4070, #4265, #3897, #4141
  found: UncaughtExceptionsBeforeTest occurs when runTest shares scheduler across tests and exceptions leak from previous tests
  implication: When ViewModel is created outside runTest, its coroutines aren't properly isolated per test

- timestamp: 2026-01-23T00:05:00Z
  checked: Android Testing Guide best practices
  found: "runTest will reuse the TestCoroutineScheduler" - coroutines launched with Dispatchers.Main (viewModelScope) share scheduler
  implication: All tests in suite share ONE scheduler. If ViewModel created outside runTest, its coroutines pollute the shared scheduler

## Resolution

root_cause: ViewModel is instantiated in @Before outside runTest scope. When ViewModel's init block or property initializers launch coroutines via viewModelScope, those coroutines run on the shared TestCoroutineScheduler. These coroutines can throw exceptions that leak into subsequent tests, causing random UncaughtExceptionsBeforeTest failures. The randomness occurs because test execution order and timing varies.

fix: Move ViewModel instantiation inside each test's runTest block. Create helper function like MessagingViewModelTest does (createTestViewModel()) and call it inside runTest. Remove lateinit var viewModel from class level - make it local to each test.

verification: Run full test suite 10+ times - should pass consistently. Individual tests should still pass. No UncaughtExceptionsBeforeTest errors.

files_changed:
- app/src/test/java/com/lxmf/messenger/viewmodel/RNodeWizardViewModelTest.kt
