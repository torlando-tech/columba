package network.columba.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.columba.app.reticulum.protocol.NativeReticulumProtocol
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTelephony
import network.columba.app.rns.backend.kt.NativeRnsBackend
import javax.inject.Singleton

/**
 * Hilt module for providing Reticulum protocol implementation.
 *
 * Uses the native Kotlin stack (reticulum-kt + lxmf-kt).
 *
 * Phase A.8 additions:
 * - [provideReticulumProtocol] wraps the singleton [NativeRnsBackend]
 *   provided by `:rns-host`'s `HostBackendModule` (kotlinBackend flavor)
 *   in the legacy facade. The 32 UI-side `ReticulumProtocol`-injecting
 *   call sites and the new `RnsBackend`/`RnsTelephony`-injecting call
 *   sites (A.9+) now resolve to the SAME backend instance — earlier
 *   drafts that gave the facade its own internal `NativeRnsBackend`
 *   created two parallel RNS stacks in the UI process.
 * - [provideRnsTelephony] surfaces the new sub-interface contract so A.9
 *   (CallCoordinator regression fix) and A.10 (UI sub-interface injection)
 *   can land without further touching this module.
 * - A.10 deletes [provideReticulumProtocol] and the facade once the 32
 *   UI-side call sites switch to injecting specific sub-interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {
    @Provides
    @Singleton
    fun provideReticulumProtocol(backend: NativeRnsBackend): ReticulumProtocol = NativeReticulumProtocol(backend)

    @Provides
    @Singleton
    fun provideRnsTelephony(rnsBackend: RnsBackend): RnsTelephony = rnsBackend.telephony
}
