package network.columba.app.data.db

import android.database.sqlite.SQLiteDatabaseLockedException
import android.database.sqlite.SQLiteTransactionListener
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import kotlin.math.min
import kotlin.random.Random

/**
 * Retries write transaction starts that fail with `SQLITE_BUSY` ("database is locked").
 *
 * Background: the app and the `:reticulum` service both open the same Room database, and the UI
 * process also writes from many concurrent coroutines. SQLite lets exactly one writer hold the
 * write lock, and a contender waits only for the *connection's* busy timeout before reporting
 * `SQLITE_BUSY`. Android's per-connection default is 2.5 s and [network.columba.app.data.di.DatabaseModule]
 * raises it to 5 s only on the connection that runs the `onOpen` callback, so extra pooled
 * connections keep the 2.5 s default. A burst of announce/message writes (or a long migration
 * import transaction) can therefore starve a writer past its timeout and crash the process with
 * [SQLiteDatabaseLockedException] from `BEGIN IMMEDIATE` — see issue #1029.
 *
 * Retrying the transaction start is safe: `SQLITE_BUSY` means the `BEGIN` never took effect, so
 * there is no half-started transaction to unwind. The retry keeps writing until [totalWaitMs] of
 * wall time has elapsed and then rethrows, so a genuinely stuck database still surfaces instead of
 * hanging forever.
 *
 * Only transaction starts are retried. Single autocommit statements (insert/update/delete) hold the
 * lock for microseconds and are not the failure the app hit.
 */
class BusyRetryingOpenHelperFactory
    @JvmOverloads
    constructor(
        private val delegate: SupportSQLiteOpenHelper.Factory,
        private val totalWaitMs: Long = DEFAULT_TOTAL_WAIT_MS,
    ) : SupportSQLiteOpenHelper.Factory {
        override fun create(configuration: SupportSQLiteOpenHelper.Configuration): SupportSQLiteOpenHelper =
            BusyRetryingOpenHelper(delegate.create(configuration), totalWaitMs)

        companion object {
            /**
             * Wall-clock budget for one transaction start. Covers several of Android's 2.5 s busy
             * timeouts (and the 5 s widened by [network.columba.app.data.di.DatabaseModule]) without
             * parking a writer for so long that the wait itself becomes a hang.
             */
            const val DEFAULT_TOTAL_WAIT_MS = 15_000L
        }
    }

/** Wraps the helper so both database handles expose the busy-retrying [SupportSQLiteDatabase]. */
internal class BusyRetryingOpenHelper(
    private val delegate: SupportSQLiteOpenHelper,
    private val totalWaitMs: Long,
) : SupportSQLiteOpenHelper by delegate {
    private val wrappedWritable =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BusyRetryingDatabase(delegate.writableDatabase, totalWaitMs)
        }

    private val wrappedReadable =
        lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
            BusyRetryingDatabase(delegate.readableDatabase, totalWaitMs)
        }

    override val writableDatabase: SupportSQLiteDatabase
        get() = wrappedWritable.value

    override val readableDatabase: SupportSQLiteDatabase
        get() = wrappedReadable.value
}

/**
 * Delegating [SupportSQLiteDatabase] that retries the statements which take the SQLite write lock.
 */
internal class BusyRetryingDatabase(
    private val delegate: SupportSQLiteDatabase,
    private val totalWaitMs: Long,
) : SupportSQLiteDatabase by delegate {
    override fun beginTransaction() = retryWhileLocked { delegate.beginTransaction() }

    override fun beginTransactionNonExclusive() = retryWhileLocked { delegate.beginTransactionNonExclusive() }

    override fun beginTransactionWithListener(transactionListener: SQLiteTransactionListener) =
        retryWhileLocked { delegate.beginTransactionWithListener(transactionListener) }

    override fun beginTransactionWithListenerNonExclusive(transactionListener: SQLiteTransactionListener) =
        retryWhileLocked { delegate.beginTransactionWithListenerNonExclusive(transactionListener) }

    override fun execSQL(sql: String) {
        if (isTransactionStart(sql)) {
            retryWhileLocked { delegate.execSQL(sql) }
        } else {
            delegate.execSQL(sql)
        }
    }

    override fun execSQL(
        sql: String,
        bindArgs: Array<out Any?>,
    ) {
        if (isTransactionStart(sql)) {
            retryWhileLocked { delegate.execSQL(sql, bindArgs) }
        } else {
            delegate.execSQL(sql, bindArgs)
        }
    }

    private inline fun retryWhileLocked(block: () -> Unit) {
        val deadlineNanos = System.nanoTime() + totalWaitMs * NANOS_PER_MS
        var backoffMs = INITIAL_BACKOFF_MS
        var attempt = 0
        while (true) {
            try {
                block()
                if (attempt > 0) {
                    Log.i(TAG, "Transaction start succeeded after ${attempt + 1} attempts")
                }
                return
            } catch (locked: SQLiteDatabaseLockedException) {
                attempt++
                if (System.nanoTime() >= deadlineNanos) {
                    Log.e(TAG, "Transaction start still locked after $attempt attempts", locked)
                    throw locked
                }
                Thread.sleep(backoffMs + Random.nextLong(backoffMs / 2 + 1))
                backoffMs = min(backoffMs * 2, MAX_BACKOFF_MS)
            }
        }
    }

    private companion object {
        private const val TAG = "Columba/DB"
        private const val NANOS_PER_MS = 1_000_000L
        private const val INITIAL_BACKOFF_MS = 25L
        private const val MAX_BACKOFF_MS = 500L

        /** `BEGIN`, `BEGIN IMMEDIATE`, `BEGIN DEFERRED`, `BEGIN EXCLUSIVE TRANSACTION`, ... */
        private fun isTransactionStart(sql: String): Boolean {
            val trimmed = sql.trimStart()
            return trimmed.length >= 5 &&
                trimmed.regionMatches(0, "BEGIN", 0, 5, ignoreCase = true) &&
                (trimmed.length == 5 || trimmed[5].isWhitespace() || trimmed[5] == ';')
        }
    }
}
