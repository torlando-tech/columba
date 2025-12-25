# Gotchas & Pitfalls

Things to watch out for in this codebase.

## [2025-12-25 01:19]
The gradlew command is blocked in this environment. Cannot run verification tests directly via ./gradlew :app:testDebugUnitTest.

_Context: When implementing subtask-1-4, tried to run verification but gradlew was blocked by permission restrictions. Tests should be verified manually in a different environment or by the QA phase._

## [2025-12-25 02:05]
The pytest command is blocked in this environment. Cannot run Python tests directly via pytest. Tests should be verified in a different environment or by manual QA.

_Context: When implementing subtask-7-3 (Run Python tests), pytest was blocked by permission restrictions. The test file test_wrapper_reactions.py exists with 1010 lines of comprehensive tests but cannot be executed in this sandbox._
