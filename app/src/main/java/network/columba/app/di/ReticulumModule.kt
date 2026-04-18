package network.columba.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.reticulum.protocol.NativeReticulumProtocol
import network.columba.app.reticulum.protocol.ReticulumProtocol
import javax.inject.Singleton

/**
 * Hilt module for providing Reticulum protocol implementation.
 *
 * Uses the native Kotlin stack (reticulum-kt + lxmf-kt).
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {
    @Provides
    @Singleton
    fun provideReticulumProtocol(
        @ApplicationContext context: Context,
    ): ReticulumProtocol = NativeReticulumProtocol(appContext = context)
}
