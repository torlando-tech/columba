package com.lxmf.messenger.service

import com.lxmf.messenger.data.model.ImageCompressionPreset
import com.lxmf.messenger.data.model.InterfaceBandwidth
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.reticulum.model.InterfaceConfig
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Service for detecting network interface characteristics to determine
 * appropriate image compression settings.
 *
 * The detection strategy is to identify the "slowest" enabled interface
 * as the bottleneck, since images may be routed through any active interface.
 */
@Singleton
class InterfaceDetector
    @Inject
    constructor(
        private val interfaceRepository: InterfaceRepository,
    ) {
        /**
         * Detect the optimal compression preset based on currently enabled interfaces.
         *
         * Returns the preset appropriate for the slowest interface:
         * - RNode (LoRa) or BLE → LOW
         * - TCP/UDP/AutoInterface only → ORIGINAL
         * - No interfaces enabled → HIGH (conservative default)
         */
        suspend fun detectOptimalPreset(): ImageCompressionPreset {
            val interfaces = interfaceRepository.enabledInterfaces.first()

            if (interfaces.isEmpty()) {
                return ImageCompressionPreset.HIGH // Conservative default
            }

            val hasSlowInterface =
                interfaces.any {
                    it is InterfaceConfig.RNode || it is InterfaceConfig.AndroidBLE
                }

            return if (hasSlowInterface) {
                ImageCompressionPreset.LOW
            } else {
                ImageCompressionPreset.ORIGINAL
            }
        }

        /**
         * Get the estimated bandwidth of the slowest enabled interface.
         *
         * @return Bandwidth in bits per second
         */
        suspend fun getSlowestInterfaceBandwidth(): Int {
            val interfaces = interfaceRepository.enabledInterfaces.first()

            if (interfaces.isEmpty()) {
                return InterfaceBandwidth.TCP_CLIENT_BPS // Default to TCP
            }

            return interfaces.minOfOrNull { getInterfaceBandwidth(it) }
                ?: InterfaceBandwidth.TCP_CLIENT_BPS
        }

        /**
         * Get a user-friendly description of the slowest enabled interface.
         *
         * @return Description like "RNode LoRa (SF11)" or "BLE Mesh"
         */
        suspend fun getSlowestInterfaceDescription(): String {
            val interfaces = interfaceRepository.enabledInterfaces.first()

            if (interfaces.isEmpty()) {
                return "No interfaces"
            }

            val slowestInterface = interfaces.minByOrNull { getInterfaceBandwidth(it) }
                ?: return "Unknown"

            return getInterfaceDescription(slowestInterface)
        }

        /**
         * Check if any slow interface (RNode/BLE) is currently enabled.
         */
        suspend fun hasSlowInterfaceEnabled(): Boolean {
            val interfaces = interfaceRepository.enabledInterfaces.first()
            return interfaces.any {
                it is InterfaceConfig.RNode || it is InterfaceConfig.AndroidBLE
            }
        }

        /**
         * Get bandwidth estimate for a specific interface configuration.
         */
        private fun getInterfaceBandwidth(config: InterfaceConfig): Int =
            when (config) {
                is InterfaceConfig.RNode -> {
                    InterfaceBandwidth.calculateRNodeBandwidth(
                        spreadingFactor = config.spreadingFactor,
                        bandwidthHz = config.bandwidth,
                        codingRate = config.codingRate,
                    )
                }
                is InterfaceConfig.AndroidBLE -> InterfaceBandwidth.ANDROID_BLE_BPS
                is InterfaceConfig.TCPClient -> InterfaceBandwidth.TCP_CLIENT_BPS
                is InterfaceConfig.AutoInterface -> InterfaceBandwidth.AUTO_INTERFACE_BPS
                is InterfaceConfig.UDP -> InterfaceBandwidth.UDP_INTERFACE_BPS
            }

        /**
         * Get a user-friendly description for an interface.
         */
        private fun getInterfaceDescription(config: InterfaceConfig): String =
            when (config) {
                is InterfaceConfig.RNode -> {
                    val sfDesc = "SF${config.spreadingFactor}"
                    val bwDesc =
                        when (config.bandwidth) {
                            125000 -> "125kHz"
                            250000 -> "250kHz"
                            500000 -> "500kHz"
                            else -> "${config.bandwidth / 1000}kHz"
                        }
                    "RNode LoRa ($sfDesc/$bwDesc)"
                }
                is InterfaceConfig.AndroidBLE -> "BLE Mesh"
                is InterfaceConfig.TCPClient -> "TCP (${config.targetHost})"
                is InterfaceConfig.AutoInterface -> "Auto Discovery"
                is InterfaceConfig.UDP -> "UDP"
            }
    }
