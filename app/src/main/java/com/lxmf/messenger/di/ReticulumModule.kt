package com.lxmf.messenger.di

import android.content.Context
import com.lxmf.messenger.reticulum.protocol.NativeReticulumProtocol
import com.lxmf.messenger.reticulum.protocol.ReticulumProtocol
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
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
