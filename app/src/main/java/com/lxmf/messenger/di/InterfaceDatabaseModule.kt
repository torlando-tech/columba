package com.lxmf.messenger.di

import android.content.Context
import androidx.room.Room
import com.lxmf.messenger.data.database.InterfaceDatabase
import com.lxmf.messenger.data.database.dao.InterfaceDao
import com.lxmf.messenger.data.db.ColumbaDatabase
import com.lxmf.messenger.data.repository.ConversationRepository
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.service.InterfaceConfigManager
import com.lxmf.messenger.service.MessageCollector
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Provider
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Qualifier for the application-level coroutine scope.
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class ApplicationScope

/**
 * Hilt module for providing the Interface database and related DAOs.
 */
@Module
@InstallIn(SingletonComponent::class)
object InterfaceDatabaseModule {
    /**
     * Provides an application-level coroutine scope for database initialization.
     */
    @ApplicationScope
    @Provides
    @Singleton
    fun provideApplicationScope(): CoroutineScope {
        return CoroutineScope(SupervisorJob())
    }

    /**
     * Provides the Interface database singleton.
     */
    @Provides
    @Singleton
    fun provideInterfaceDatabase(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        database: Provider<InterfaceDatabase>,
    ): InterfaceDatabase {
        return Room.databaseBuilder(
            context,
            InterfaceDatabase::class.java,
            "interface_database",
        )
            .addCallback(InterfaceDatabase.Callback(context, database, applicationScope))
            .addMigrations(InterfaceDatabase.MIGRATION_1_2)
            .build()
    }

    /**
     * Provides the InterfaceDao from the database.
     */
    @Provides
    fun provideInterfaceDao(database: InterfaceDatabase): InterfaceDao {
        return database.interfaceDao()
    }

    /**
     * Provides the InterfaceConfigManager for applying configuration changes.
     */
    @Provides
    @Singleton
    fun provideInterfaceConfigManager(
        @ApplicationContext context: Context,
        reticulumProtocol: ReticulumProtocol,
        interfaceRepository: InterfaceRepository,
        identityRepository: IdentityRepository,
        conversationRepository: ConversationRepository,
        messageCollector: MessageCollector,
        database: ColumbaDatabase,
    ): InterfaceConfigManager {
        return InterfaceConfigManager(
            context = context,
            reticulumProtocol = reticulumProtocol,
            interfaceRepository = interfaceRepository,
            identityRepository = identityRepository,
            conversationRepository = conversationRepository,
            messageCollector = messageCollector,
            database = database,
        )
    }
}
