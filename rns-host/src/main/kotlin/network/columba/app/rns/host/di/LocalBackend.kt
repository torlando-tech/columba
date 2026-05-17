package network.columba.app.rns.host.di

import javax.inject.Qualifier

/**
 * Marks the flavor-local [network.columba.app.rns.api.RnsBackend] binding —
 * the concrete backend impl constructed in the `:reticulum` process
 * (`ChaquopyRnsBackend` for the python flavor, `NativeRnsBackend` for the
 * kotlin flavor).
 *
 * Disambiguates the local-construction provider in the flavor-specific
 * `HostBackendModule` from the unqualified, process-aware top-level
 * [network.columba.app.rns.api.RnsBackend] binding that
 * [network.columba.app.rns.host.di.ProcessAwareBackendModule] provides to
 * every Hilt consumer. The process-aware provider injects this
 * `@LocalBackend Lazy<RnsBackend>` so the heavy backend ctor only runs when
 * the process is `:reticulum` — in the UI process the lazy is never resolved
 * and the proxy is returned instead.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class LocalBackend
