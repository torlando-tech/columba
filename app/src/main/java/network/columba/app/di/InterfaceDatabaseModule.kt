package network.columba.app.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import network.columba.app.data.database.InterfaceDatabase
import network.columba.app.data.database.dao.InterfaceDao
import network.columba.app.data.db.ColumbaDatabase
import network.columba.app.data.repository.ConversationRepository
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.service.AutoAnnounceManager
import network.columba.app.service.IdentityResolutionManager
import network.columba.app.service.InterfaceConfigManager
import network.columba.app.service.MessageCollector
import network.columba.app.service.PropagationNodeManager
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
 * Qualifier for the default coroutine dispatcher (testable alternative to hardcoding Dispatchers.Default).
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class DefaultDispatcher

/**
 * Qualifier for the IO coroutine dispatcher (testable alternative to hardcoding Dispatchers.IO).
 */
@Retention(AnnotationRetention.RUNTIME)
@Qualifier
annotation class IoDispatcher

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
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob())

    /**
     * Provides the default dispatcher for background work that needs to be off the Main thread.
     */
    @DefaultDispatcher
    @Provides
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    /**
     * Provides the IO dispatcher for disk/network-bound work.
     */
    @IoDispatcher
    @Provides
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /**
     * Provides the Interface database singleton.
     */
    @Provides
    @Singleton
    fun provideInterfaceDatabase(
        @ApplicationContext context: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        database: Provider<InterfaceDatabase>,
    ): InterfaceDatabase =
        Room
            .databaseBuilder(
                context,
                InterfaceDatabase::class.java,
                "interface_database",
            ).addCallback(InterfaceDatabase.Callback(context, database, applicationScope))
            .fallbackToDestructiveMigration()
            .fallbackToDestructiveMigrationOnDowngrade()
            .build()

    /**
     * Provides the InterfaceDao from the database.
     */
    @Provides
    fun provideInterfaceDao(database: InterfaceDatabase): InterfaceDao = database.interfaceDao()

    /**
     * Provides the InterfaceConfigManager for applying configuration changes.
     */
    @Suppress("LongParameterList") // Hilt DI requires all dependencies as parameters
    @Provides
    @Singleton
    fun provideInterfaceConfigManager(
        @ApplicationContext context: Context,
        reticulumProtocol: ReticulumProtocol,
        interfaceRepository: InterfaceRepository,
        identityRepository: IdentityRepository,
        identityKeyProvider: network.columba.app.data.crypto.IdentityKeyProvider,
        conversationRepository: ConversationRepository,
        messageCollector: MessageCollector,
        database: ColumbaDatabase,
        settingsRepository: SettingsRepository,
        autoAnnounceManager: AutoAnnounceManager,
        identityResolutionManager: IdentityResolutionManager,
        propagationNodeManager: PropagationNodeManager,
        @ApplicationScope applicationScope: CoroutineScope,
    ): InterfaceConfigManager =
        InterfaceConfigManager(
            context = context,
            reticulumProtocol = reticulumProtocol,
            interfaceRepository = interfaceRepository,
            identityRepository = identityRepository,
            identityKeyProvider = identityKeyProvider,
            conversationRepository = conversationRepository,
            messageCollector = messageCollector,
            database = database,
            settingsRepository = settingsRepository,
            autoAnnounceManager = autoAnnounceManager,
            identityResolutionManager = identityResolutionManager,
            propagationNodeManager = propagationNodeManager,
            applicationScope = applicationScope,
        )
}
