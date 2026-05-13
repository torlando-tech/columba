package network.columba.app.rns.host

/**
 * Kotlin-flavor backend wiring for `:rns-host`.
 *
 * Active when the `:app` build variant resolves the `rnsImpl=kotlinBackend`
 * flavor. Phase A.8 will populate this with a Hilt `@Module @InstallIn`
 * that provides `NativeRnsBackend` (from `:rns-backend-kt`) to the host's
 * `RnsBackendServer`. For now this file exists so the `rnsImpl` flavor
 * dimension resolves at configuration time and the source-set layout is
 * recognized by Gradle and the IDE.
 *
 * Do not move backend-agnostic peripherals here — they live in `src/main/`.
 */
internal object HostBackendModule
