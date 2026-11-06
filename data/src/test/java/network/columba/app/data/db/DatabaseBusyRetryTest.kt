package network.columba.app.data.db

import android.app.Application
import android.content.Context
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteOpenHelper
import androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import network.columba.app.data.db.entity.PeerActivityType
import network.columba.app.data.di.DatabaseModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Regression test for issue #1029: `SQLiteDatabaseLockedException: database is locked
 * (code 5 SQLITE_BUSY)` while starting a write transaction.
 *
 * SQLite allows a single writer. Android gives every connection a 2.5 s busy timeout, and
 * [DatabaseModule] widens it to 5 s only on the connection that runs the `onOpen` callback. When
 * another writer (the `:reticulum` service process, a migration import, a burst of announce
 * persistence) holds the write lock past that window, the transaction start throws and the app
 * crashes. Two Room instances on the same file model the two processes sharing `columba_database`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class DatabaseBusyRetryTest {
    private companion object {
        const val DB_NAME = "columba_busy_retry_test"

        /** Longer than the 5 s busy timeout DatabaseModule sets on the connection that runs onOpen. */
        const val LONG_HOLD_MS = 7_000L

        /** Longer than the 2.5 s framework default busy timeout, shorter than the retry budget test. */
        const val SHORT_HOLD_MS = 4_000L
    }

    private lateinit var context: Context
    private var holderDb: ColumbaDatabase? = null
    private var writerDb: ColumbaDatabase? = null

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DB_NAME)
    }

    @After
    fun tearDown() {
        holderDb?.close()
        writerDb?.close()
        context.deleteDatabase(DB_NAME)
    }

    @Test
    fun `write transaction survives a lock held past the default busy timeout`() =
        runBlocking {
            holderDb = DatabaseModule.buildColumbaDatabase(context, DB_NAME)
            writerDb = DatabaseModule.buildColumbaDatabase(context, DB_NAME)
            val writer = checkNotNull(writerDb)

            val transactionStarted = CompletableDeferred<Unit>()
            val hold =
                async(Dispatchers.IO) {
                    checkNotNull(holderDb).withTransaction {
                        transactionStarted.complete(Unit)
                        delay(LONG_HOLD_MS)
                    }
                }
            transactionStarted.await()

            writer.withTransaction {
                writer.peerActivityDao().recordActivity(
                    destinationHash = "busy-retry-peer",
                    receivedAt = 1L,
                    activityType = PeerActivityType.ANNOUNCE,
                )
            }

            hold.await()
            assertEquals(
                1L,
                writer.peerActivityDao().getActivity("busy-retry-peer")?.lastReceivedAt,
            )
        }

    @Test
    fun `transaction start rethrows once the retry budget is exhausted`() {
        val holderHelper = newRawHelper()
        val writerHelper = newRawHelper()
        try {
            val holderDb = holderHelper.writableDatabase
            val bounded = BusyRetryingDatabase(writerHelper.writableDatabase, totalWaitMs = 300L)
            val holderStarted = CountDownLatch(1)
            val holder =
                thread(name = "busy-holder") {
                    holderDb.beginTransactionNonExclusive()
                    holderStarted.countDown()
                    try {
                        Thread.sleep(SHORT_HOLD_MS)
                        holderDb.setTransactionSuccessful()
                    } finally {
                        holderDb.endTransaction()
                    }
                }
            assertTrue(
                "holder did not start its transaction in time",
                holderStarted.await(10, TimeUnit.SECONDS),
            )

            val error = runCatching { bounded.beginTransactionNonExclusive() }.exceptionOrNull()

            assertEquals(
                "android.database.sqlite.SQLiteDatabaseLockedException",
                error?.javaClass?.name,
            )
            holder.join()
        } finally {
            holderHelper.close()
            writerHelper.close()
        }
    }

    /** A plain helper on the test database file; one helper is one connection pool. */
    private fun newRawHelper(): SupportSQLiteOpenHelper =
        FrameworkSQLiteOpenHelperFactory().create(
            SupportSQLiteOpenHelper.Configuration
                .builder(context)
                .name(DB_NAME)
                .callback(
                    object : SupportSQLiteOpenHelper.Callback(1) {
                        override fun onCreate(db: SupportSQLiteDatabase) = Unit

                        override fun onUpgrade(
                            db: SupportSQLiteDatabase,
                            oldVersion: Int,
                            newVersion: Int,
                        ) = Unit
                    },
                ).build(),
        )
}
