package network.columba.app.startup

import network.columba.app.data.db.entity.LocalIdentityEntity
import network.columba.app.data.repository.IdentityRepository
import network.columba.app.repository.InterfaceRepository
import network.columba.app.repository.SettingsRepository
import network.columba.app.reticulum.model.BatteryProfile
import network.columba.app.reticulum.model.InterfaceConfig
import network.columba.app.service.manager.InterfaceTransportObserver
import network.columba.app.service.manager.filterByTransport
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Loads startup configuration from repositories in parallel for faster app initialization.
 * Extracted from ColumbaApplication to enable unit testing.
 */
@Singleton
class StartupConfigLoader
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
        private val identityRepository: IdentityRepository,
        private val settingsRepository: SettingsRepository,
        private val transportObserver: InterfaceTransportObserver,
    ) {
        /**
         * Configuration data loaded during startup.
         */
        data class StartupConfig(
            val interfaces: List<InterfaceConfig>,
            val identity: LocalIdentityEntity?,
            val preferOwn: Boolean,
            val rpcKey: String?,
            val transport: Boolean,
            val batteryProfile: BatteryProfile,
            val discoverInterfaces: Boolean,
            val autoconnectDiscoveredCount: Int,
            val autoconnectIfacOnly: Boolean,
        )

        /**
         * Load all startup configuration from repositories in parallel.
         * This is faster than loading sequentially since database operations can overlap.
         *
         * @return StartupConfig containing all loaded configuration values
         */
        suspend fun loadConfig(): StartupConfig =
            coroutineScope {
                val interfacesDeferred = async { interfaceRepository.enabledInterfaces.first() }
                val identityDeferred = async { identityRepository.getActiveIdentitySync() }
                val preferOwnDeferred = async { settingsRepository.preferOwnInstanceFlow.first() }
                val rpcKeyDeferred = async { settingsRepository.rpcKeyFlow.first() }
                val transportDeferred = async { settingsRepository.getTransportNodeEnabled() }
                val batteryProfileDeferred = async { settingsRepository.getBatteryProfile() }
                val discoverInterfacesDeferred = async { settingsRepository.getDiscoverInterfacesEnabled() }
                val autoconnectCountDeferred = async { settingsRepository.getAutoconnectDiscoveredCount() }
                val autoconnectIfacOnlyDeferred = async { settingsRepository.getAutoconnectIfacOnly() }

                val savedAutoconnect = autoconnectCountDeferred.await()
                // Filter the enabled set against the device's current transport so the
                // very first config the native stack sees doesn't try to start e.g. a
                // wifi-only AutoInterface while the phone is on cellular at boot.
                val rawEnabled = interfacesDeferred.await()
                val transport = transportObserver.currentTransport()
                val filteredEnabled = filterByTransport(rawEnabled, transport)
                StartupConfig(
                    interfaces = filteredEnabled,
                    identity = identityDeferred.await(),
                    preferOwn = preferOwnDeferred.await(),
                    rpcKey = rpcKeyDeferred.await(),
                    transport = transportDeferred.await(),
                    batteryProfile = batteryProfileDeferred.await(),
                    discoverInterfaces = discoverInterfacesDeferred.await(),
                    // Coerce -1 (never configured sentinel) to 0 for the native stack
                    autoconnectDiscoveredCount = if (savedAutoconnect >= 0) savedAutoconnect else 0,
                    autoconnectIfacOnly = autoconnectIfacOnlyDeferred.await(),
                )
            }
    }
