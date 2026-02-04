# Phase 1: Immediate Stabilization - Checklist

## Task 1.1: Verify Python Threading Safety

- [ ] Created PythonThreadSafetyTest.kt
- [ ] Test: 10+ concurrent Python calls pass
- [ ] Test: 1000 rapid invocations complete without crashes
- [ ] Test: Long-running operations don't block other threads
- [ ] Test: Multiple dispatchers work correctly
- [ ] Added thread-safety comments to all Python call sites
- [ ] Team reviewed and approved approach

**Verification**: `./gradlew :app:testDebugUnitTest --tests "*.PythonThreadSafetyTest"`

## Task 1.2: Remove runBlocking from IPC

- [ ] Found all runBlocking in production code: `grep -r "runBlocking" app/src/main --include="*.kt" | grep -v "Test"`
- [ ] Created callback interfaces for all blocking AIDL methods
- [ ] Updated AIDL interface definitions
- [ ] Converted service implementations to async
- [ ] Updated client code to use suspend functions
- [ ] Measured IPC return time (< 1ms)
- [ ] No ANRs observed in testing

**Verification**: `grep -r "runBlocking" app/src/main --include="*.kt" | grep -v "Test" | wc -l` should return 0

## Task 1.3: Fix Database Transaction Nesting

- [ ] Audited all @Transaction methods: `grep -r "@Transaction" app/src/main --include="*.kt"`
- [ ] Created internal transaction-free helpers in DAOs
- [ ] Refactored to fetch data before transactions
- [ ] Added documentation about transaction boundaries
- [ ] No "nested transaction" warnings in logs during 100 operations
- [ ] Measured performance improvement (target: 20% faster)

**Verification**: Run app, check logs for "nested transaction" warnings (should be zero)

## Overall Phase 1 Success Criteria

- [ ] All unit tests pass: `./gradlew :app:test`
- [ ] Manual testing shows stable initialization
- [ ] Initialization < 3 seconds consistently
- [ ] Code reviewed by team
- [ ] Changes committed with clear message
- [ ] Documentation updated

## Metrics to Verify

| Metric | Target | Actual | Pass? |
|--------|--------|--------|-------|
| Concurrent Python calls | 10+ succeed | ___ | [ ] |
| Rapid Python invocations | 1000 complete | ___ | [ ] |
| runBlocking count | 0 | ___ | [ ] |
| Nested transaction warnings | 0 | ___ | [ ] |
| Initialization time | < 3s | ___ | [ ] |

---

**Don't proceed to Phase 2 until ALL items checked!**
