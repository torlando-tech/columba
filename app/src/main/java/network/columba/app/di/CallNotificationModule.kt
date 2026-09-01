package network.columba.app.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.columba.app.notifications.CallNotificationHelper
import network.columba.app.notifications.IncomingCallNotifier
import javax.inject.Singleton

/**
 * Binds the narrow [IncomingCallNotifier] port to the concrete
 * [CallNotificationHelper] so presentation policy can be unit-tested with a
 * fake without touching Android notification machinery.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CallNotificationModule {
    @Binds
    @Singleton
    abstract fun bindIncomingCallNotifier(impl: CallNotificationHelper): IncomingCallNotifier
}
