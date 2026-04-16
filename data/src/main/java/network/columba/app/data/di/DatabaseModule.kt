package network.columba.app.data.di

import android.content.Context
import androidx.room.Room
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
