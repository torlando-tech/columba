package com.lxmf.messenger.di

import android.content.Context
import com.lxmf.messenger.data.repository.RmspServerRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ServiceReticulumProtocol
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt module for providing Reticulum protocol implementation.
 * This allows easy swapping between Mock, Python, and Service implementations.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {
    @Provides
    @Singleton
    fun provideServiceReticulumProtocol(
        @ApplicationContext context: Context,
        settingsRepository: SettingsRepository,
        rmspServerRepository: RmspServerRepository,
    ): ServiceReticulumProtocol {
        // Use Service-based implementation for real Reticulum backend
        // This runs Python/RNS in a separate service process with proper threading
        return ServiceReticulumProtocol(context, settingsRepository, rmspServerRepository)
    }

    @Provides
    @Singleton
    fun provideReticulumProtocol(
        serviceProtocol: ServiceReticulumProtocol,
    ): ReticulumProtocol {
        // Return the same instance for interface injection
        // To use mock for testing, change to: return MockReticulumProtocol()
        return serviceProtocol
    }
}
