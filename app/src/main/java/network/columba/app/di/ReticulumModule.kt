package network.columba.app.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import network.columba.app.reticulum.protocol.NativeReticulumProtocol
import network.columba.app.reticulum.protocol.ReticulumProtocol
import network.columba.app.rns.backend.kt.NativeRnsBackend
import javax.inject.Singleton

/**
 * Legacy [ReticulumProtocol] binding for UI call sites still injecting the
 * old facade. The facade wraps the Hilt-singleton [NativeRnsBackend] from
 * `:rns-host`'s `HostBackendModule` (kotlinBackend flavor) so callers
 * resolve to the same backend instance as the new sub-interface injection
 * paths (`RnsCore` / `RnsLxmf` / `RnsTelephony` / `RnsTelemetry` /
 * `RnsNomadnet` / `RnsTransportAdmin` — all provided by `HostBackendModule`
 * per A.8 deviation #15's single-source-binding rule).
 *
 * Phase A.10 removed `provideRnsTelephony` from here; the sub-interface
 * providers all live in `:rns-host`'s `HostBackendModule` now. This module
 * is reduced to the strangler-fig [ReticulumProtocol] binding only, and
 * gets deleted entirely once the remaining UI call sites are migrated to
 * specific sub-interfaces.
 */
@Module
@InstallIn(SingletonComponent::class)
object ReticulumModule {
    @Provides
    @Singleton
    fun provideReticulumProtocol(backend: NativeRnsBackend): ReticulumProtocol = NativeReticulumProtocol(backend)
}
