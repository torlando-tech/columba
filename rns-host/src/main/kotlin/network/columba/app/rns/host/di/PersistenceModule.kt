package network.columba.app.rns.host.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.data.db.dao.CallHistoryDao
import network.columba.app.data.db.dao.CallHistoryDeletionDao
import network.columba.app.rns.api.call.CallLifecycleRecorder
import network.columba.app.rns.host.call.ServiceCallLifecycle
import network.columba.app.rns.host.persistence.CallsFromContactsGate
import network.columba.app.rns.host.persistence.ServiceSettingsAccessor
import javax.inject.Singleton

/**
 * Hilt wiring for `:reticulum`-side persistence helpers that need to be
 * injected into flavor-specific call managers.
 *
 * Both [ServiceSettingsAccessor] and [CallsFromContactsGate] are
 * flavor-independent (no `pythonBackend` / `kotlinBackend` references) so
 * they live in `:rns-host/src/main/` rather than being duplicated in both
 * flavor `HostBackendModule.kt`s.
 *
 * Note that [ServiceSettingsAccessor] is also constructed manually inside
 * [network.columba.app.rns.host.di.ServiceModule.createManagers] for the
 * UI-process binder path. That path predates Hilt being available to
 * `:reticulum` (it explicitly notes "Hilt doesn't work across process
 * boundaries"). Both constructions are cheap — the class is stateless above
 * `SharedPreferences` — so the duplicate is acceptable. Consumers that go
 * through Hilt (the call managers + the contacts gate) all share the
 * `@Singleton`-scoped instance provided here.
 */
@Module
@InstallIn(SingletonComponent::class)
object PersistenceModule {
    @Provides
    @Singleton
    fun provideCallHistoryDao(
        @ApplicationContext context: Context,
    ): CallHistoryDao = ServiceDatabaseProvider.getDatabase(context).callHistoryDao()

    @Provides
    @Singleton
    fun provideCallHistoryDeletionDao(
        @ApplicationContext context: Context,
    ): CallHistoryDeletionDao = ServiceDatabaseProvider.getDatabase(context).callHistoryDeletionDao()

    @Provides
    @Singleton
    fun provideCallLifecycleRecorder(lifecycle: ServiceCallLifecycle): CallLifecycleRecorder = lifecycle

    @Provides
    @Singleton
    fun provideServiceSettingsAccessor(
        @ApplicationContext context: Context,
    ): ServiceSettingsAccessor = ServiceSettingsAccessor(context)

    @Provides
    @Singleton
    fun provideCallsFromContactsGate(
        @ApplicationContext context: Context,
        settingsAccessor: ServiceSettingsAccessor,
    ): CallsFromContactsGate = CallsFromContactsGate(context, settingsAccessor)
}
