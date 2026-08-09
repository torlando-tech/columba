package network.columba.app.rns.api.call

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Serializes exact-attempt cleanup and atomically transfers failed synchronous work to a job. */
internal class AcceptedCallCleanupRetrier(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val retryDelayMillis: Long,
    private val retryAttempts: Int,
    private val isOwned: (String) -> Boolean,
    private val release: (String) -> Unit,
) {
    private val ownerLock = Any()
    private var owner: CleanupOwner? = null

    suspend fun retry(
        callAttemptId: String,
        cleanup: suspend () -> Result<Unit>,
        onDeferredComplete: () -> Unit = {},
        deferOnFailure: Boolean = true,
    ): Result<Unit> {
        val acquired = acquire(callAttemptId)
        if (!acquired.acquired) return acquired.owner.completion.await()
        if (!isOwned(callAttemptId)) {
            return Result.success(Unit).also { complete(acquired.owner, it) }
        }
        var result = cleanup()
        var attempts = 1
        while (result.isFailure && isOwned(callAttemptId) && attempts < retryAttempts) {
            delay(retryDelayMillis)
            result = cleanup()
            attempts++
        }
        if (result.isSuccess) {
            release(callAttemptId)
            complete(acquired.owner, result)
        } else if (deferOnFailure && isOwned(callAttemptId)) {
            transferToDeferred(acquired.owner, cleanup, onDeferredComplete)
        } else {
            complete(acquired.owner, result)
        }
        return result
    }

    private fun acquire(callAttemptId: String): AcquiredOwner =
        synchronized(ownerLock) {
            owner?.let {
                check(it.callAttemptId == callAttemptId) { "Cleanup ownership belongs to another attempt" }
                return@synchronized AcquiredOwner(it, acquired = false)
            }
            val created = CleanupOwner(callAttemptId)
            owner = created
            AcquiredOwner(created, acquired = true)
        }

    private fun transferToDeferred(
        expected: CleanupOwner,
        cleanup: suspend () -> Result<Unit>,
        onDeferredComplete: () -> Unit,
    ) {
        val job =
            synchronized(ownerLock) {
                check(owner === expected && expected.job == null) { "Cleanup ownership transfer was not exact" }
                scope.launch(dispatcher, start = CoroutineStart.LAZY) {
                    var result: Result<Unit>
                    do {
                        delay(retryDelayMillis)
                        result = cleanup()
                    } while (result.isFailure && isOwned(expected.callAttemptId))
                    if (result.isSuccess) release(expected.callAttemptId)
                    complete(expected, result)
                    onDeferredComplete()
                }.also { expected.job = it }
            }
        job.start()
    }

    private fun complete(expected: CleanupOwner, result: Result<Unit>) {
        synchronized(ownerLock) {
            if (owner === expected) owner = null
        }
        expected.completion.complete(result)
    }

    private class CleanupOwner(val callAttemptId: String) {
        val completion = CompletableDeferred<Result<Unit>>()
        var job: Job? = null
    }

    private data class AcquiredOwner(val owner: CleanupOwner, val acquired: Boolean)
}
