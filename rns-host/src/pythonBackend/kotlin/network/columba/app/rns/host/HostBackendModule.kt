package network.columba.app.rns.host

/**
 * Python-flavor backend wiring for `:rns-host`.
 *
 * Active when the `:app` build variant resolves the `rnsImpl=pythonBackend`
 * flavor. Phase B will populate this with a Hilt `@Module @InstallIn`
 * that provides `ChaquopyRnsBackend` (from `:rns-backend-py`) to the
 * host's `RnsBackendServer`. For now this file exists so the `rnsImpl`
 * flavor dimension resolves at configuration time and the source-set
 * layout is recognized by Gradle and the IDE.
 *
 * Per the dual-build plan, Kotlin sub-impls in `:rns-backend-py` will
 * call upstream RNS/LXMF methods directly via `PyObject.callAttr(...)`.
 * No `rns_*.py` facade is introduced — re-adding one is a regression
 * caught by the `NoRnsFacadeInPythonBackend` Detekt rule.
 */
internal object HostBackendModule
