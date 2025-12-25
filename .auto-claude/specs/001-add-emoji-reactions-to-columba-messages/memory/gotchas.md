# Gotchas & Pitfalls

Things to watch out for in this codebase.

## [2025-12-25 01:19]
The gradlew command is blocked in this environment. Cannot run verification tests directly via ./gradlew :app:testDebugUnitTest.

_Context: When implementing subtask-1-4, tried to run verification but gradlew was blocked by permission restrictions. Tests should be verified manually in a different environment or by the QA phase._
