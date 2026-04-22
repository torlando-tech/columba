package network.columba.app.data.di

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.db.dao.AnnounceDao
import network.columba.app.data.db.dao.BlockedPeerDao
import network.columba.app.data.db.dao.ContactDao
import network.columba.app.data.db.dao.ConversationDao
import network.columba.app.data.db.dao.CustomThemeDao
import network.columba.app.data.db.dao.DraftDao
import network.columba.app.data.db.dao.InterfaceFirstSeenDao
import network.columba.app.data.db.dao.LocalIdentityDao
import network.columba.app.data.db.dao.MessageDao
import network.columba.app.data.db.dao.OfflineMapRegionDao
import network.columba.app.data.db.dao.PeerIconDao
import network.columba.app.data.db.dao.PeerIdentityDao
import network.columba.app.data.db.dao.ReceivedLocationDao
import network.columba.app.data.db.dao.RmspServerDao
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
@Suppress("TooManyFunctions") // Hilt modules have one @Provides per DAO
object DatabaseModule {
    const val DATABASE_NAME = "columba_database"

    /**
     * Harden SQLite against process-kill-induced corruption.
     *
     * Background: the app runs two processes (main + `:reticulum`) that both open this
     * Room DB. If either is OOM-killed mid-write, the default `synchronous=NORMAL` in
     * WAL mode only fsyncs on checkpoint, which on some kernels/filesystems (f2fs on
     * older Android kernels) can leave torn WAL pages and produce `SQLITE_CORRUPT` on
     * next read. `synchronous=FULL` fsyncs on every commit, closing that window.
     *
     * Applied from both [provideColumbaDatabase] and the `:reticulum` process's
     * `ServiceDatabaseProvider` so both processes agree on journal mode and durability.
     *
     * Note: `onOpen` fires after Room has already run any pending migrations, so the
     * migration window itself still runs at `synchronous=NORMAL`. That's a narrow
     * residual risk (migrations execute once per schema bump, for seconds) accepted
     * for this patch; a full fix would require a custom `SupportSQLiteOpenHelper.Factory`.
     * `onCreate` is also overridden so first-install schema creation is durable.
     */
    val DURABILITY_CALLBACK: RoomDatabase.Callback =
        object : RoomDatabase.Callback() {
            private fun applyPragmas(db: SupportSQLiteDatabase) {
                // All four PRAGMAs return a result row (either the new value or the
                // activated mode). Android's SupportSQLiteDatabase rejects execSQL for
                // any statement that produces rows, so everything must go through
                // query() and close the cursor even if we don't care about the value.
                //
                // SQLite does not allow PRAGMA journal_mode or PRAGMA synchronous to be
                // changed while a transaction is active (it raises SQLITE_ERROR: "Safety
                // level may not be changed inside a transaction"). Room's InvalidationTracker
                // can invoke onCreate() from within an internal transaction, so we guard
                // these two PRAGMAs with an inTransaction() check. onOpen() is typically
                // called outside of a transaction in current Room versions, so the skipped
                // PRAGMAs get applied on the next open. The guard is also our defense if
                // a future Room version ever calls onOpen() transactionally — we'd just
                // log the skip instead of crashing.
                if (!db.inTransaction()) {
                    db.query("PRAGMA journal_mode=WAL").use { cursor ->
                        if (cursor.moveToFirst() && !cursor.getString(0).equals("wal", ignoreCase = true)) {
                            Log.e("Columba/DB", "journal_mode=WAL not activated; mode=${cursor.getString(0)}")
                        }
                    }
                    db.query("PRAGMA synchronous=FULL").use {
                        /* drain row */ it.moveToFirst()
                    }
                } else {
                    Log.d(
                        "Columba/DB",
                        "applyPragmas: inside transaction, skipping journal_mode and synchronous " +
                            "(will retry on next transaction-free callback)",
                    )
                }
                db.query("PRAGMA wal_autocheckpoint=100").use {
                    /* drain row */ it.moveToFirst()
                }
                db.query("PRAGMA busy_timeout=5000").use {
                    /* drain row */ it.moveToFirst()
                }
            }

            override fun onCreate(db: SupportSQLiteDatabase) = applyPragmas(db)

            override fun onOpen(db: SupportSQLiteDatabase) = applyPragmas(db)
        }

    @Provides
    @Singleton
    fun provideColumbaDatabase(
        @ApplicationContext context: Context,
    ): ColumbaDatabase =
        Room
            .databaseBuilder(
                context,
                ColumbaDatabase::class.java,
                DATABASE_NAME,
            ).fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .enableMultiInstanceInvalidation()
            .addCallback(DURABILITY_CALLBACK)
            .build()

    @Provides
    fun provideConversationDao(database: ColumbaDatabase): ConversationDao = database.conversationDao()

    @Provides
    fun provideMessageDao(database: ColumbaDatabase): MessageDao = database.messageDao()

    @Provides
    fun provideAnnounceDao(database: ColumbaDatabase): AnnounceDao = database.announceDao()

    @Provides
    fun providePeerIdentityDao(database: ColumbaDatabase): PeerIdentityDao = database.peerIdentityDao()

    @Provides
    fun providePeerIconDao(database: ColumbaDatabase): PeerIconDao = database.peerIconDao()

    @Provides
    fun provideContactDao(database: ColumbaDatabase): ContactDao = database.contactDao()

    @Provides
    fun provideCustomThemeDao(database: ColumbaDatabase): CustomThemeDao = database.customThemeDao()

    @Provides
    fun provideLocalIdentityDao(database: ColumbaDatabase): LocalIdentityDao = database.localIdentityDao()

    @Provides
    fun provideReceivedLocationDao(database: ColumbaDatabase): ReceivedLocationDao = database.receivedLocationDao()

    @Provides
    fun provideOfflineMapRegionDao(database: ColumbaDatabase): OfflineMapRegionDao = database.offlineMapRegionDao()

    @Provides
    fun provideRmspServerDao(database: ColumbaDatabase): RmspServerDao = database.rmspServerDao()

    @Provides
    fun provideDraftDao(database: ColumbaDatabase): DraftDao = database.draftDao()

    @Provides
    fun provideBlockedPeerDao(database: ColumbaDatabase): BlockedPeerDao = database.blockedPeerDao()

    @Provides
    fun provideInterfaceFirstSeenDao(database: ColumbaDatabase): InterfaceFirstSeenDao = database.interfaceFirstSeenDao()

    @Provides
    @Singleton
    @Suppress("InjectDispatcher") // This IS the DI provider for the IO dispatcher
    fun provideIODispatcher(): CoroutineDispatcher = Dispatchers.IO
}
