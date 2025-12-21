package com.lxmf.messenger.startup

import com.lxmf.messenger.data.db.entity.LocalIdentityEntity
import com.lxmf.messenger.data.repository.IdentityRepository
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
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

                StartupConfig(
                    interfaces = interfacesDeferred.await(),
                    identity = identityDeferred.await(),
                    preferOwn = preferOwnDeferred.await(),
                    rpcKey = rpcKeyDeferred.await(),
                    transport = transportDeferred.await(),
                )
            }
    }
