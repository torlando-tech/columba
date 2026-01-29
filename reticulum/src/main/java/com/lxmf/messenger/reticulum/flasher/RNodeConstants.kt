package com.lxmf.messenger.reticulum.flasher

/**
 * Constants for RNode device identification and communication.
 * Based on the RNode protocol and RNode Flasher web application.
 */
@Suppress("MagicNumber")
object RNodeConstants {
    // KISS Protocol Constants
    const val KISS_FEND: Byte = 0xC0.toByte()
    const val KISS_FESC: Byte = 0xDB.toByte()
    const val KISS_TFEND: Byte = 0xDC.toByte()
    const val KISS_TFESC: Byte = 0xDD.toByte()

    // RNode Commands
    const val CMD_FREQUENCY: Byte = 0x01
    const val CMD_BANDWIDTH: Byte = 0x02
    const val CMD_TXPOWER: Byte = 0x03
    const val CMD_SF: Byte = 0x04
    const val CMD_CR: Byte = 0x05
    const val CMD_RADIO_STATE: Byte = 0x06

    const val CMD_STAT_RX: Byte = 0x21
    const val CMD_STAT_TX: Byte = 0x22
    const val CMD_STAT_RSSI: Byte = 0x23
    const val CMD_STAT_SNR: Byte = 0x24

    const val CMD_BOARD: Byte = 0x47
    const val CMD_PLATFORM: Byte = 0x48
    const val CMD_MCU: Byte = 0x49
    const val CMD_RESET: Byte = 0x55
    const val CMD_RESET_BYTE: Byte = 0xF8.toByte()
    const val CMD_DEV_HASH: Byte = 0x56
    const val CMD_FW_VERSION: Byte = 0x50
    const val CMD_ROM_READ: Byte = 0x51
    const val CMD_ROM_WRITE: Byte = 0x52
    const val CMD_CONF_SAVE: Byte = 0x53
    const val CMD_CONF_DELETE: Byte = 0x54
    const val CMD_FW_HASH: Byte = 0x58
    const val CMD_UNLOCK_ROM: Byte = 0x59
    const val ROM_UNLOCK_BYTE: Byte = 0xF8.toByte()
    const val CMD_HASHES: Byte = 0x60
    const val CMD_FW_UPD: Byte = 0x61
    const val CMD_DISP_ROT: Byte = 0x67
    const val CMD_DISP_RCND: Byte = 0x68

    const val CMD_BT_CTRL: Byte = 0x46
    const val CMD_BT_PIN: Byte = 0x62

    const val CMD_DISP_READ: Byte = 0x66

    const val CMD_DETECT: Byte = 0x08
    const val DETECT_REQ: Byte = 0x73
    const val DETECT_RESP: Byte = 0x46

    const val RADIO_STATE_OFF: Byte = 0x00
    const val RADIO_STATE_ON: Byte = 0x01
    const val RADIO_STATE_ASK: Byte = 0xFF.toByte()

    const val CMD_ERROR: Byte = 0x90.toByte()
    const val ERROR_INITRADIO: Byte = 0x01
    const val ERROR_TXFAILED: Byte = 0x02
    const val ERROR_EEPROM_LOCKED: Byte = 0x03

    // Platform Types
    const val PLATFORM_AVR: Byte = 0x90.toByte()
    const val PLATFORM_ESP32: Byte = 0x80.toByte()
    const val PLATFORM_NRF52: Byte = 0x70

    // MCU Types
    const val MCU_1284P: Byte = 0x91.toByte()
    const val MCU_2560: Byte = 0x92.toByte()
    const val MCU_ESP32: Byte = 0x81.toByte()
    const val MCU_NRF52: Byte = 0x71

    // Board Types
    const val BOARD_RNODE: Byte = 0x31
    const val BOARD_HMBRW: Byte = 0x32
    const val BOARD_TBEAM: Byte = 0x33
    const val BOARD_HUZZAH32: Byte = 0x34
    const val BOARD_GENERIC_ESP32: Byte = 0x35
    const val BOARD_LORA32_V2_0: Byte = 0x36
    const val BOARD_LORA32_V2_1: Byte = 0x37
    const val BOARD_RAK4631: Byte = 0x51

    // Hash Types
    const val HASH_TYPE_TARGET_FIRMWARE: Byte = 0x01
    const val HASH_TYPE_FIRMWARE: Byte = 0x02

    // ROM Addresses (EEPROM layout)
    const val ADDR_PRODUCT = 0x00
    const val ADDR_MODEL = 0x01
    const val ADDR_HW_REV = 0x02
    const val ADDR_SERIAL = 0x03
    const val ADDR_MADE = 0x07
    const val ADDR_CHKSUM = 0x0B
    const val ADDR_SIGNATURE = 0x1B
    const val ADDR_INFO_LOCK = 0x9B
    const val ADDR_CONF_SF = 0x9C
    const val ADDR_CONF_CR = 0x9D
    const val ADDR_CONF_TXP = 0x9E
    const val ADDR_CONF_BW = 0x9F
    const val ADDR_CONF_FREQ = 0xA3
    const val ADDR_CONF_OK = 0xA7

    const val INFO_LOCK_BYTE: Byte = 0x73
    const val CONF_OK_BYTE: Byte = 0x73

    // Product Codes
    const val PRODUCT_RAK4631: Byte = 0x10
    const val PRODUCT_RNODE: Byte = 0x03
    const val PRODUCT_T32_10: Byte = 0xB2.toByte()
    const val PRODUCT_T32_20: Byte = 0xB0.toByte()
    const val PRODUCT_T32_21: Byte = 0xB1.toByte()
    const val PRODUCT_H32_V2: Byte = 0xC0.toByte()
    const val PRODUCT_H32_V3: Byte = 0xC1.toByte()
    const val PRODUCT_H32_V4: Byte = 0xC3.toByte()
    const val PRODUCT_HELTEC_T114: Byte = 0xC2.toByte()
    const val PRODUCT_TBEAM: Byte = 0xE0.toByte()
    const val PRODUCT_TBEAM_S_V1: Byte = 0xEA.toByte()
    const val PRODUCT_TDECK: Byte = 0xD0.toByte()
    const val PRODUCT_TECHO: Byte = 0x15
    const val PRODUCT_HMBRW: Byte = 0xF0.toByte()

    // Model Codes (frequency band variations)
    const val MODEL_11: Byte = 0x11 // RAK4631 868/915 MHz
    const val MODEL_12: Byte = 0x12 // RAK4631 433 MHz

    // Standard baud rates
    const val BAUD_RATE_DEFAULT = 115200
    const val BAUD_RATE_DFU_TOUCH = 1200
    const val BAUD_RATE_ESPTOOL = 921600
    const val BAUD_RATE_ESPTOOL_FALLBACK = 115200
}

/**
 * Represents the platform type of an RNode device.
 */
enum class RNodePlatform(val code: Byte) {
    AVR(RNodeConstants.PLATFORM_AVR),
    ESP32(RNodeConstants.PLATFORM_ESP32),
    NRF52(RNodeConstants.PLATFORM_NRF52),
    UNKNOWN(0x00),
    ;

    companion object {
        fun fromCode(code: Byte): RNodePlatform = entries.find { it.code == code } ?: UNKNOWN
    }
}

/**
 * Represents the MCU type of an RNode device.
 */
enum class RNodeMcu(val code: Byte) {
    ATmega1284P(RNodeConstants.MCU_1284P),
    ATmega2560(RNodeConstants.MCU_2560),
    ESP32(RNodeConstants.MCU_ESP32),
    NRF52(RNodeConstants.MCU_NRF52),
    UNKNOWN(0x00),
    ;

    companion object {
        fun fromCode(code: Byte): RNodeMcu = entries.find { it.code == code } ?: UNKNOWN
    }
}

/**
 * Represents a board type/product.
 */
enum class RNodeBoard(
    val productCode: Byte,
    val platform: RNodePlatform,
    val displayName: String,
    val firmwarePrefix: String,
) {
    RAK4631(
        RNodeConstants.PRODUCT_RAK4631,
        RNodePlatform.NRF52,
        "RAK4631",
        "rnode_firmware_rak4631",
    ),
    HELTEC_T114(
        RNodeConstants.PRODUCT_HELTEC_T114,
        RNodePlatform.NRF52,
        "Heltec T114",
        "rnode_firmware_heltec_t114",
    ),
    TECHO(
        RNodeConstants.PRODUCT_TECHO,
        RNodePlatform.NRF52,
        "LilyGO T-Echo",
        "rnode_firmware_techo",
    ),
    HELTEC_V2(
        RNodeConstants.PRODUCT_H32_V2,
        RNodePlatform.ESP32,
        "Heltec LoRa32 v2",
        "rnode_firmware_heltec32v2",
    ),
    HELTEC_V3(
        RNodeConstants.PRODUCT_H32_V3,
        RNodePlatform.ESP32,
        "Heltec LoRa32 v3",
        "rnode_firmware_heltec32v3",
    ),
    HELTEC_V4(
        RNodeConstants.PRODUCT_H32_V4,
        RNodePlatform.ESP32,
        "Heltec LoRa32 v4",
        "rnode_firmware_heltec32v4pa",
    ),
    TBEAM(
        RNodeConstants.PRODUCT_TBEAM,
        RNodePlatform.ESP32,
        "LilyGO T-Beam",
        "rnode_firmware_tbeam",
    ),
    TBEAM_S(
        RNodeConstants.PRODUCT_TBEAM_S_V1,
        RNodePlatform.ESP32,
        "LilyGO T-Beam Supreme",
        "rnode_firmware_tbeam_supreme",
    ),
    TDECK(
        RNodeConstants.PRODUCT_TDECK,
        RNodePlatform.ESP32,
        "LilyGO T-Deck",
        "rnode_firmware_tdeck",
    ),
    LORA32_V2_0(
        RNodeConstants.PRODUCT_T32_20,
        RNodePlatform.ESP32,
        "TTGO LoRa32 v2.0",
        "rnode_firmware_lora32v20",
    ),
    LORA32_V2_1(
        RNodeConstants.PRODUCT_T32_21,
        RNodePlatform.ESP32,
        "TTGO LoRa32 v2.1",
        "rnode_firmware_lora32v21",
    ),
    RNODE(
        RNodeConstants.PRODUCT_RNODE,
        RNodePlatform.AVR,
        "RNode (Original)",
        "rnode_firmware",
    ),
    HOMEBREW(
        RNodeConstants.PRODUCT_HMBRW,
        RNodePlatform.ESP32,
        "Homebrew ESP32",
        "rnode_firmware_esp32_generic",
    ),
    UNKNOWN(
        0x00,
        RNodePlatform.UNKNOWN,
        "Unknown",
        "unknown",
    ),
    ;

    companion object {
        fun fromProductCode(code: Byte): RNodeBoard = entries.find { it.productCode == code } ?: UNKNOWN
    }
}

/**
 * Information about a detected RNode device.
 */
data class RNodeDeviceInfo(
    val platform: RNodePlatform,
    val mcu: RNodeMcu,
    val board: RNodeBoard,
    val firmwareVersion: String?,
    val isProvisioned: Boolean,
    val isConfigured: Boolean,
    val serialNumber: Int?,
    val hardwareRevision: Int?,
    val product: Byte,
    val model: Byte,
) {
    val isFlashable: Boolean
        get() = platform != RNodePlatform.UNKNOWN && board != RNodeBoard.UNKNOWN

    val requiresDfuMode: Boolean
        get() = platform == RNodePlatform.NRF52

    val supportsEspTool: Boolean
        get() = platform == RNodePlatform.ESP32
}
