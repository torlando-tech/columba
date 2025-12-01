package com.lxmf.messenger.util.validation

/**
 * Constants and regex patterns for input validation across the application.
 *
 * These constants define maximum lengths, valid formats, and validation patterns
 * to ensure data integrity, security, and consistency.
 */
object ValidationConstants {
    // ========== LENGTH LIMITS ==========

    /**
     * Maximum message content length in characters (10KB)
     *
     * This is a UX-focused limit, not a protocol limitation.
     * LXMF protocol supports:
     * - Single-packet messages: ~295-368 bytes (opportunistic delivery)
     * - Multi-packet messages: Up to 1MB efficiently via resource-based delivery
     *
     * Our 10KB limit:
     * - Ensures messages are delivered efficiently over slow links (LoRa, packet radio)
     * - Prevents users from sending excessively long messages
     * - Fits well within LXMF's 1MB efficient resource limit
     * - Will use LXMF resource-based delivery with automatic segmentation
     */
    const val MAX_MESSAGE_LENGTH = 10_000

    /**
     * Maximum nickname length for contacts
     */
    const val MAX_NICKNAME_LENGTH = 100

    /**
     * Maximum interface name length
     */
    const val MAX_INTERFACE_NAME_LENGTH = 50

    /**
     * Maximum BLE device name length
     */
    const val MAX_DEVICE_NAME_LENGTH = 30

    /**
     * Maximum search query length
     */
    const val MAX_SEARCH_QUERY_LENGTH = 200

    // ========== CRYPTOGRAPHIC LENGTHS ==========

    /**
     * Destination hash length in bytes (32 hex characters)
     */
    const val DESTINATION_HASH_LENGTH = 16

    /**
     * Public key length in bytes (64 hex characters)
     */
    const val PUBLIC_KEY_LENGTH = 32

    // ========== NETWORK LIMITS ==========

    /**
     * Minimum valid port number
     */
    const val MIN_PORT = 1

    /**
     * Maximum valid port number
     */
    const val MAX_PORT = 65535

    /**
     * Maximum BLE packet size in bytes
     */
    const val MAX_BLE_PACKET_SIZE = 512

    // ========== REGEX PATTERNS ==========

    /**
     * Regex pattern for hexadecimal strings (0-9, a-f, A-F)
     */
    val HEX_REGEX = Regex("^[0-9a-fA-F]+$")

    /**
     * Regex pattern for valid hostnames (DNS names)
     *
     * Matches:
     * - Single labels: "localhost", "server1"
     * - FQDNs: "example.com", "sub.example.com"
     * - Hyphens allowed (but not at start/end of labels)
     */
    val HOSTNAME_REGEX =
        Regex(
            "^([a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?\\.)*[a-zA-Z0-9]([a-zA-Z0-9\\-]{0,61}[a-zA-Z0-9])?$",
        )

    /**
     * Regex pattern for IPv4 addresses
     *
     * Matches valid IPv4 addresses: 192.168.1.1, 10.0.0.1, etc.
     */
    val IPV4_REGEX =
        Regex(
            "^(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\." +
                "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\." +
                "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])\\." +
                "(25[0-5]|2[0-4][0-9]|1[0-9]{2}|[1-9]?[0-9])$",
        )

    /**
     * Regex pattern for IPv6 addresses
     *
     * Matches full IPv6 addresses and compressed forms
     */
    val IPV6_REGEX =
        Regex(
            "^(([0-9a-fA-F]{1,4}:){7}[0-9a-fA-F]{1,4}|([0-9a-fA-F]{1,4}:){1,7}:|([0-9a-fA-F]{1,4}:){1,6}:[0-9a-fA-F]{1,4})$",
        )

    // ========== IDENTITY STRING CONSTANTS ==========

    /**
     * Expected prefix for LXMF identity QR codes
     */
    const val LXMF_IDENTITY_PREFIX = "lxma://"

    /**
     * Expected number of parts in an identity string (hash:pubkey)
     */
    const val IDENTITY_PARTS_COUNT = 2

    // ========== CONFIG FILE LIMITS ==========

    /**
     * Maximum configuration file size in bytes (1MB)
     */
    const val MAX_CONFIG_FILE_SIZE = 1_048_576

    /**
     * Maximum number of interfaces allowed in a config file
     */
    const val MAX_INTERFACE_COUNT = 50

    /**
     * Allowed interface configuration parameter names (whitelist)
     */
    val ALLOWED_INTERFACE_PARAMS =
        setOf(
            // Common parameters
            "type",
            "enabled",
            "interface_enabled",
            "outgoing",
            "mode",
            // TCP/Network parameters
            "target_host",
            "target_port",
            "listen_ip",
            "listen_port",
            "forward_ip",
            "forward_port",
            "kiss_framing",
            // AutoInterface parameters
            "group_id",
            "discovery_scope",
            "discovery_port",
            "data_port",
            // Serial/RNode parameters
            "device",
            "port",
            "speed",
            "databits",
            "parity",
            "stopbits",
            "frequency",
            "bandwidth",
            "txpower",
            "tx_power",
            "spreadingfactor",
            "spreading_factor",
            "codingrate",
            "coding_rate",
            // BLE parameters
            "device_name",
            "service_uuid",
            "max_packet_size",
            "max_connections",
        )
}
