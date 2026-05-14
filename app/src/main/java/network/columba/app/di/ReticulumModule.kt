package network.columba.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import network.columba.app.reticulum.protocol.NativeReticulumProtocol
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.rns.api.RnsBackend
import network.columba.app.rns.api.RnsTelephony
import javax.inject.Singleton

/**
 * Hilt module for providing Reticulum protocol implementation.
 *
 * Uses the native Kotlin stack (reticulum-kt + lxmf-kt).
 *
 * Phase A.8 additions:
 * - [provideRnsTelephony] surfaces the new sub-interface contract so A.9
 *   (CallCoordinator regression fix) and A.10 (UI sub-interface injection)
 *   can land without further touching this module.
 * - `RnsBackend` itself is provided by `:rns-host`'s `HostBackendModule`
 *   (kotlinBackend flavor source set). The legacy facade
 *   [NativeReticulumProtocol] gets its own [NativeReticulumProtocol.backend]
 *   internally; this Hilt module does not double-bind it.
 * - A.10 deletes [provideReticulumProtocol] and the facade once the 32
 *   UI-side call sites switch to injecting specific sub-interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {
    @Provides
    @Singleton
    fun provideReticulumProtocol(
        @ApplicationContext context: Context,
    ): ReticulumProtocol = NativeReticulumProtocol(appContext = context)

    @Provides
    @Singleton
    fun provideRnsTelephony(rnsBackend: RnsBackend): RnsTelephony = rnsBackend.telephony
}
