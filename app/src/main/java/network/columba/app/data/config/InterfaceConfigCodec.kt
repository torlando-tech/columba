package network.columba.app.data.config

import network.columba.app.rns.api.model.InterfaceConfig
import network.columba.app.rns.api.model.InterfaceMode
import network.columba.app.rns.api.model.NetworkRestriction
import network.columba.app.util.validation.InputValidator
import network.columba.app.util.validation.ValidationConstants
import network.columba.app.util.validation.ValidationResult
import java.io.File

/**
 * Parses and renders Reticulum INI-style interface sections.
 *
 * This is the single source of truth for the config-text ↔ [InterfaceConfig]
 * mapping used by:
 *  - legacy config-file import during first-run database creation
 *    ([parseConfigFile], called from `ConfigFileParser`),
 *  - "paste interface config" from the clipboard ([parseSnippet]),
 *  - "copy/share interface config" ([renderSnippet]), and
 *  - detecting config text inside chat messages ([parseSnippet] on message body).
 *
 * The parser is deliberately lenient about surrounding text so a pasted
 * snippet may keep its Markdown fences, headings, or a sentence like
 * "here's my node:" without breaking the import. It only treats `[[Section]]`
 * blocks containing a recognised `type` as interfaces.
 *
 * Type mapping notes:
 *  - `BackboneInterface` is interchangeable with TCP client/server interfaces
 *    in RNS (`Reticulum.py` rewrites `remote` → `target_host`). A backbone
 *    remote becomes an app [InterfaceConfig.TCPClient]; a backbone listener
 *    becomes an [InterfaceConfig.TCPServer]. `transport_identity` is not
 *    consumed by RNS and is reported as an ignored setting.
 *  - `RNodeInterface` with `tcp_host` maps to [InterfaceConfig.RNode] in TCP
 *    mode, the only RNode mode that is portable across devices.
 *  - Columba-only interface types (`AndroidBLE`, Bluetooth/USB RNode) are
 *    importable from a full app config but not renderable as a portable
 *    snippet; [renderSnippet] returns null for them.
 */
@Suppress("TooManyFunctions") // One cohesive codec: snippet/config parsing, rendering, and import resolution.
object InterfaceConfigCodec {
    // Keeps parsing bounded for a clipboard/message payload. The same cap is
    // used by the legacy config-file import path.
    private val IGNORED_LINE_PREFIXES = listOf("#", ";", "```")
    private val QUOTE_TRIGGER_CHARS = setOf('#', ';', '[', ']', '"', '\'')

    private const val MAX_TEXT_BYTES = ValidationConstants.MAX_CONFIG_FILE_SIZE

    /** A parsed `[[Section]]` with the config it produced. */
    data class ParsedInterface(
        val sectionName: String,
        val config: InterfaceConfig,
        val warnings: List<String> = emptyList(),
    )

    /**
     * A parse problem. [isError] issues drop the section; warnings keep the
     * section and are surfaced in the import preview.
     */
    data class Issue(
        val sectionName: String?,
        val message: String,
        val isError: Boolean,
    )

    data class ParseResult(
        val interfaces: List<ParsedInterface>,
        val issues: List<Issue>,
    ) {
        val hasErrors: Boolean get() = issues.any { it.isError }
    }

    /**
     * Result of resolving parsed sections against interfaces already stored on
     * the device (and against earlier entries in the same paste).
     *
     * [config] carries the final, uniqueness-checked name so it can be inserted
     * directly. [duplicate] means the same endpoint is already configured; the
     * import UI offers it unchecked and skips it if the user does not opt in.
     */
    data class ResolvedImport(
        val config: InterfaceConfig,
        val originalName: String,
        val duplicate: Boolean,
        val warnings: List<String> = emptyList(),
    )

    // ==================== Parsing ====================

    /**
     * Parse a full Reticulum config file, preserving the historical import
     * contract: only interfaces inside `[interfaces]` and only those with
     * `enabled = yes` are returned.
     */
    fun parseConfigFile(configFile: File): List<InterfaceConfig> {
        if (!configFile.exists() || !configFile.isFile) return emptyList()
        if (configFile.length() > MAX_TEXT_BYTES) {
            require(configFile.length() <= MAX_TEXT_BYTES) {
                "Config file exceeds maximum size of $MAX_TEXT_BYTES bytes"
            }
        }
        val text = configFile.readText()
        return parse(text, requireInterfacesSection = true, includeDisabled = false)
            .interfaces
            .map { it.config }
    }

    /**
     * Parse a pasted/messaged snippet. Surrounding prose and Markdown fences are
     * ignored. Interfaces without an `enabled` key default to enabled because a
     * user who pastes a section explicitly intends to add it.
     */
    fun parseSnippet(text: String): ParseResult = parse(text, requireInterfacesSection = false, includeDisabled = true)

    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    private fun parse(
        text: String,
        requireInterfacesSection: Boolean,
        includeDisabled: Boolean,
    ): ParseResult {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_TEXT_BYTES) {
            return ParseResult(
                interfaces = emptyList(),
                issues = listOf(Issue(null, "Config text is too large", isError = true)),
            )
        }

        val interfaces = mutableListOf<ParsedInterface>()
        val issues = mutableListOf<Issue>()
        var inInterfacesSection = !requireInterfacesSection
        var current: RawSection? = null

        fun flushCurrent() {
            val section = current ?: return
            current = null
            // The snippet default applies only when neither key is present. An
            // explicit but unparseable value keeps that default; `no` stays off.
            val enabled =
                when {
                    "enabled" in section.params -> section.boolean("enabled", defaultValue = includeDisabled)
                    "interface_enabled" in section.params ->
                        section.boolean("interface_enabled", defaultValue = includeDisabled)
                    else -> includeDisabled
                }
            if (!enabled && !includeDisabled) return
            try {
                val parsed = buildInterface(section, enabled = enabled)
                if (interfaces.size >= ValidationConstants.MAX_INTERFACE_COUNT) {
                    issues +=
                        Issue(
                            section.name,
                            "Too many interfaces (max ${ValidationConstants.MAX_INTERFACE_COUNT})",
                            isError = true,
                        )
                    return
                }
                interfaces += parsed
            } catch (e: IllegalArgumentException) {
                issues += Issue(section.name, e.message ?: "Invalid interface", isError = true)
            }
        }

        for (line in text.lineSequence()) {
            val trimmed = line.trim()
            // Blank lines, INI comments, and Markdown fence markers are noise.
            if (trimmed.isEmpty() || IGNORED_LINE_PREFIXES.any(trimmed::startsWith)) continue

            if (trimmed.startsWith("[[") && trimmed.endsWith("]]")) {
                flushCurrent()
                val name = unquote(trimmed.substring(2, trimmed.length - 2).trim())
                if (name.isEmpty()) {
                    issues += Issue(null, "Interface section name cannot be empty", isError = true)
                    continue
                }
                current = RawSection(name)
                continue
            }

            if (trimmed.startsWith("[")) {
                flushCurrent()
                inInterfacesSection = !requireInterfacesSection || trimmed == "[interfaces]"
                continue
            }

            val section = current ?: continue
            if (!inInterfacesSection) continue

            val separator = trimmed.indexOf('=')
            if (separator <= 0) continue
            val key = normalizeKey(trimmed.substring(0, separator))
            val value = unquote(trimmed.substring(separator + 1).trim())
            if (section.params.containsKey(key)) {
                section.warnings += "Duplicate setting '$key'; the last value wins"
            }
            section.params[key] = value
        }
        flushCurrent()

        return ParseResult(interfaces, issues)
    }

    private fun buildInterface(
        section: RawSection,
        enabled: Boolean,
    ): ParsedInterface {
        val nameResult = InputValidator.validateInterfaceName(section.name)
        val name =
            requireNotNull((nameResult as? ValidationResult.Success)?.value) {
                (nameResult as ValidationResult.Error).message
            }
        val rawType = section.params["type"]
            ?: throw IllegalArgumentException("Missing required setting 'type'")
        val type = normalizeType(rawType)
            ?: throw IllegalArgumentException("Unsupported interface type '$rawType'")

        val warnings = section.warnings.toMutableList()
        val config =
            when (type) {
                Type.TCP_CLIENT -> buildTcpClient(section, name, enabled, warnings)
                Type.TCP_SERVER -> buildTcpServer(section, name, enabled, warnings)
                Type.BACKBONE -> buildBackbone(section, name, enabled, warnings)
                Type.UDP -> buildUdp(section, name, enabled, warnings)
                Type.AUTO -> buildAuto(section, name, enabled, warnings)
                Type.RNODE -> buildRNode(section, name, enabled, warnings)
            }

        reportUnknownKeys(section, warnings)
        return ParsedInterface(section.name, config, warnings)
    }

    private fun buildTcpClient(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig.TCPClient {
        val host = section.param("target_host")
            ?: throw IllegalArgumentException("Missing required setting 'target_host'")
        val port = section.port("target_port")
            ?: throw IllegalArgumentException("Missing required setting 'target_port'")
        if (section.boolean("i2p_tunneled", defaultValue = false)) {
            warnings += "I2P tunneling is not supported; this interface will connect directly"
        }
        return InterfaceConfig.TCPClient(
            name = name,
            enabled = enabled,
            targetHost = validateHost(host, "target_host"),
            targetPort = port,
            kissFraming = section.boolean("kiss_framing", defaultValue = false),
            mode = section.mode(warnings),
            networkName = section.ifacNetworkName(),
            passphrase = section.ifacPassphrase(),
            networkRestriction = NetworkRestriction.ANY,
        )
    }

    private fun buildTcpServer(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig.TCPServer {
        val port = section.port("listen_port")
            ?: throw IllegalArgumentException("Missing required setting 'listen_port'")
        return InterfaceConfig.TCPServer(
            name = name,
            enabled = enabled,
            listenIp = section.param("listen_ip")?.let { validateHost(it, "listen_ip") } ?: "0.0.0.0",
            listenPort = port,
            mode = section.mode(warnings),
            networkName = section.ifacNetworkName(),
            passphrase = section.ifacPassphrase(),
            networkRestriction = NetworkRestriction.ANY,
        )
    }

    private fun buildBackbone(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig {
        // Reticulum rewrites `remote` to `target_host` for Backbone remotes.
        val remote = section.param("remote") ?: section.param("target_host")
        val listenIp = section.param("listen_on") ?: section.param("listen_ip")
        return when {
            remote != null -> {
                val port = section.port("target_port")
                    ?: section.port("port")
                    ?: throw IllegalArgumentException("Missing required setting 'target_port'")
                InterfaceConfig.TCPClient(
                    name = name,
                    enabled = enabled,
                    targetHost = validateHost(remote, "remote"),
                    targetPort = port,
                    mode = section.mode(warnings),
                    networkName = section.ifacNetworkName(),
                    passphrase = section.ifacPassphrase(),
                    networkRestriction = NetworkRestriction.ANY,
                )
            }

            listenIp != null -> {
                val port = section.port("listen_port")
                    ?: section.port("port")
                    ?: throw IllegalArgumentException("Missing required setting 'listen_port'")
                InterfaceConfig.TCPServer(
                    name = name,
                    enabled = enabled,
                    listenIp = validateHost(listenIp, "listen_ip"),
                    listenPort = port,
                    mode = section.mode(warnings),
                    networkName = section.ifacNetworkName(),
                    passphrase = section.ifacPassphrase(),
                    networkRestriction = NetworkRestriction.ANY,
                )
            }

            section.param("device") != null -> unsupportedBackboneDevice()

            else -> {
                val port =
                    requireNotNull(section.port("port")) {
                        "Backbone interface needs 'remote' + 'target_port' (client) or " +
                            "'listen_ip' + 'listen_port' (listener)"
                    }
                InterfaceConfig.TCPServer(
                    name = name,
                    enabled = enabled,
                    listenIp = "0.0.0.0",
                    listenPort = port,
                    mode = section.mode(warnings),
                    networkName = section.ifacNetworkName(),
                    passphrase = section.ifacPassphrase(),
                    networkRestriction = NetworkRestriction.ANY,
                )
            }
        }
    }

    private fun unsupportedBackboneDevice(): Nothing =
        throw IllegalArgumentException(
            "Backbone listeners bound to a kernel interface are not supported on Android",
        )

    private fun buildUdp(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig.UDP {
        val listenPort = section.port("listen_port")
            ?: throw IllegalArgumentException("Missing required setting 'listen_port'")
        return InterfaceConfig.UDP(
            name = name,
            enabled = enabled,
            listenIp = section.param("listen_ip")?.let { validateHost(it, "listen_ip") } ?: "0.0.0.0",
            listenPort = listenPort,
            forwardIp = section.param("forward_ip")?.let { validateHost(it, "forward_ip") } ?: "255.255.255.255",
            forwardPort = section.port("forward_port") ?: 4242,
            mode = section.mode(warnings),
            networkRestriction = NetworkRestriction.ANY,
        )
    }

    private fun buildAuto(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig.AutoInterface {
        val scope =
            section.param("discovery_scope")?.lowercase() ?: "link"
        if (scope !in DISCOVERY_SCOPES) {
            warnings += "Unknown discovery_scope '$scope'; using link"
        }
        return InterfaceConfig.AutoInterface(
            name = name,
            enabled = enabled,
            groupId = section.param("group_id").orEmpty(),
            discoveryScope = if (scope in DISCOVERY_SCOPES) scope else "link",
            discoveryPort = section.port("discovery_port"),
            dataPort = section.port("data_port"),
            mode = section.mode(warnings),
            networkRestriction = NetworkRestriction.WIFI_ONLY,
        )
    }

    @Suppress("CyclomaticComplexMethod")
    private fun buildRNode(
        section: RawSection,
        name: String,
        enabled: Boolean,
        warnings: MutableList<String>,
    ): InterfaceConfig.RNode {
        val tcpHost = section.param("tcp_host")
        val deviceName =
            section.param("target_device_name")
                ?: section.param("port")
                ?: section.param("device")
        require(!tcpHost.isNullOrBlank() || deviceName != null) {
            "RNode interface needs 'tcp_host' (WiFi) or 'port'/'target_device_name' (serial/Bluetooth)"
        }
        if (tcpHost.isNullOrBlank() && deviceName != null) {
            warnings += "RNode serial/Bluetooth interfaces are device-specific and may not work on this device"
        }
        return InterfaceConfig.RNode(
            name = name,
            enabled = enabled,
            targetDeviceName = if (tcpHost.isNullOrBlank()) deviceName.orEmpty() else "",
            connectionMode = if (!tcpHost.isNullOrBlank()) "tcp" else "classic",
            tcpHost = tcpHost?.let { validateHost(it, "tcp_host") },
            tcpPort = section.port("tcp_port") ?: 7633,
            frequency = section.long("frequency") ?: 915_000_000L,
            bandwidth = section.int("bandwidth") ?: 125_000,
            txPower = section.int("txpower") ?: section.int("tx_power") ?: 7,
            spreadingFactor = section.int("spreadingfactor") ?: section.int("spreading_factor") ?: 7,
            codingRate = section.int("codingrate") ?: section.int("coding_rate") ?: 5,
            stAlock = section.double("st_alock"),
            ltAlock = section.double("lt_alock"),
            mode = section.mode(warnings),
            networkName = section.ifacNetworkName(),
            passphrase = section.ifacPassphrase(),
            networkRestriction = NetworkRestriction.ANY,
        )
    }

    private fun reportUnknownKeys(
        section: RawSection,
        warnings: MutableList<String>,
    ) {
        val ignored = section.params.keys.filter { it !in RECOGNISED_KEYS && it !in COMMON_KEYS }
        if (ignored.isNotEmpty()) {
            warnings += "Ignored unsupported setting${if (ignored.size > 1) "s" else ""}: ${ignored.joinToString(", ")}"
        }
    }

    // ==================== Rendering ====================

    /**
     * Render one interface as a portable Reticulum config section.
     *
     * Returns null for interfaces that only exist inside Columba (AndroidBLE,
     * Bluetooth/USB RNode) — their hardware binding cannot travel to another
     * device.
     */
    @Suppress("CyclomaticComplexMethod")
    fun renderSnippet(config: InterfaceConfig): String? =
        when (config) {
            is InterfaceConfig.AutoInterface -> buildString {
                appendSectionHeader(config.name)
                line("type", "AutoInterface")
                line("enabled", yesNo(config.enabled))
                if (config.groupId.isNotBlank()) line("group_id", config.groupId)
                line("discovery_scope", config.discoveryScope)
                config.discoveryPort?.let { line("discovery_port", it.toString()) }
                config.dataPort?.let { line("data_port", it.toString()) }
                line("mode", config.mode)
            }

            is InterfaceConfig.TCPClient -> buildString {
                appendSectionHeader(config.name)
                line("type", "TCPClientInterface")
                line("enabled", yesNo(config.enabled))
                line("target_host", config.targetHost)
                line("target_port", config.targetPort.toString())
                if (config.kissFraming) line("kiss_framing", "yes")
                line("mode", config.mode)
                config.networkName?.let { line("network_name", it) }
                config.passphrase?.let { line("passphrase", it) }
                if (config.socksProxyEnabled) {
                    appendLine(
                        "# NOTE: Columba routes this connection through SOCKS5 " +
                            "${config.socksProxyHost}:${config.socksProxyPort} " +
                            "(e.g. Orbot). Stock Reticulum ignores this setting.",
                    )
                }
            }

            is InterfaceConfig.TCPServer -> buildString {
                appendSectionHeader(config.name)
                line("type", "TCPServerInterface")
                line("enabled", yesNo(config.enabled))
                line("listen_ip", config.listenIp)
                line("listen_port", config.listenPort.toString())
                line("mode", config.mode)
                config.networkName?.let { line("network_name", it) }
                config.passphrase?.let { line("passphrase", it) }
            }

            is InterfaceConfig.UDP -> buildString {
                appendSectionHeader(config.name)
                line("type", "UDPInterface")
                line("enabled", yesNo(config.enabled))
                line("listen_ip", config.listenIp)
                line("listen_port", config.listenPort.toString())
                line("forward_ip", config.forwardIp)
                line("forward_port", config.forwardPort.toString())
                line("mode", config.mode)
            }

            is InterfaceConfig.RNode -> {
                val host = config.tcpHost
                if (config.connectionMode != "tcp" || host.isNullOrBlank()) {
                    null
                } else {
                    buildString {
                        appendSectionHeader(config.name)
                        line("type", "RNodeInterface")
                        line("enabled", yesNo(config.enabled))
                        line("tcp_host", host)
                        line("tcp_port", config.tcpPort.toString())
                        line("frequency", config.frequency.toString())
                        line("bandwidth", config.bandwidth.toString())
                        line("txpower", config.txPower.toString())
                        line("spreadingfactor", config.spreadingFactor.toString())
                        line("codingrate", config.codingRate.toString())
                        config.stAlock?.let { line("st_alock", it.toString()) }
                        config.ltAlock?.let { line("lt_alock", it.toString()) }
                        line("mode", config.mode)
                        config.networkName?.let { line("network_name", it) }
                        config.passphrase?.let { line("passphrase", it) }
                    }
                }
            }

            is InterfaceConfig.AndroidBLE -> null
        }

    /** Render several interfaces, separated by a blank line. */
    fun renderSnippets(configs: List<InterfaceConfig>): String =
        configs.mapNotNull { renderSnippet(it) }.joinToString(separator = "\n\n")

    fun isPortable(config: InterfaceConfig): Boolean = renderSnippet(config) != null

    private fun StringBuilder.appendSectionHeader(name: String) {
        // Section headers are delimited by brackets and cannot contain newlines.
        // Collapse those characters so a hostile/odd name still produces a
        // parseable config on the receiving device.
        val safe =
            name
                .replace(Regex("[\\[\\]\\r\\n]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .ifEmpty { "Interface" }
                .take(ValidationConstants.MAX_INTERFACE_NAME_LENGTH)
        appendLine("[[$safe]]")
    }

    private fun StringBuilder.line(
        key: String,
        value: String,
    ) {
        appendLine("  $key = ${quoteIfNeeded(value)}")
    }

    // ==================== Import resolution ====================

    /**
     * Resolve parsed sections into insertable configs.
     *
     * - A section whose endpoint already exists in [existing] (or earlier in
     *   the same paste) is marked [ResolvedImport.duplicate].
     * - A non-duplicate whose section name collides with an existing name gets
     *   a " (2)", " (3)", … suffix. RNS crashes on duplicate section names, so
     *   the suffix is mandatory rather than cosmetic.
     */
    fun resolveImports(
        parsed: List<ParsedInterface>,
        existing: List<InterfaceConfig>,
    ): List<ResolvedImport> {
        val usedNames = existing.map { it.name.lowercase() }.toMutableSet()
        val usedIdentities = existing.map { identityKey(it) }.toMutableSet()
        val resolved = mutableListOf<ResolvedImport>()

        for (entry in parsed) {
            val identity = identityKey(entry.config)
            val duplicate = identity in usedIdentities
            val originalName = entry.config.name
            val uniqueName = uniqueName(originalName, usedNames)
            usedNames += uniqueName.lowercase()
            if (!duplicate) usedIdentities += identity
            resolved +=
                ResolvedImport(
                    config = entry.config.withName(uniqueName),
                    originalName = originalName,
                    duplicate = duplicate,
                    warnings = entry.warnings,
                )
        }
        return resolved
    }

    /**
     * Semantic endpoint key used for duplicate detection. Two configs with the
     * same key would connect to the same network endpoint (and RNS would not
     * benefit from both).
     */
    fun identityKey(config: InterfaceConfig): String =
        when (config) {
            is InterfaceConfig.TCPClient ->
                listOf(
                    "tcp",
                    config.targetHost.lowercase(),
                    config.targetPort.toString(),
                    config.networkName.orEmpty(),
                    config.passphrase.orEmpty(),
                ).joinToString("|")

            is InterfaceConfig.TCPServer ->
                listOf(
                    "tcpserver",
                    config.listenIp.lowercase(),
                    config.listenPort.toString(),
                    config.networkName.orEmpty(),
                ).joinToString("|")

            is InterfaceConfig.UDP ->
                listOf(
                    "udp",
                    config.listenIp.lowercase(),
                    config.listenPort.toString(),
                    config.forwardIp.lowercase(),
                    config.forwardPort.toString(),
                ).joinToString("|")

            is InterfaceConfig.AutoInterface ->
                listOf(
                    "auto",
                    config.groupId.lowercase(),
                    config.discoveryPort?.toString().orEmpty(),
                    config.dataPort?.toString().orEmpty(),
                ).joinToString("|")

            is InterfaceConfig.RNode ->
                listOf(
                    "rnode",
                    config.connectionMode,
                    config.targetDeviceName.lowercase(),
                    config.tcpHost.orEmpty().lowercase(),
                    config.tcpPort.toString(),
                    config.frequency.toString(),
                    config.bandwidth.toString(),
                    config.spreadingFactor.toString(),
                    config.codingRate.toString(),
                ).joinToString("|")

            is InterfaceConfig.AndroidBLE ->
                listOf("ble", config.deviceName.lowercase(), config.maxConnections.toString()).joinToString("|")
        }

    private fun uniqueName(
        base: String,
        usedNames: Set<String>,
    ): String {
        if (base.lowercase() !in usedNames) return base
        var counter = 2
        while (true) {
            val suffix = " ($counter)"
            val candidate = base.take((ValidationConstants.MAX_INTERFACE_NAME_LENGTH - suffix.length).coerceAtLeast(1)) + suffix
            if (candidate.lowercase() !in usedNames) return candidate
            counter++
        }
    }

    // ==================== Helpers ====================

    private enum class Type { AUTO, TCP_CLIENT, TCP_SERVER, BACKBONE, UDP, RNODE }

    private fun normalizeType(raw: String): Type? =
        when (raw.trim().lowercase().replace("-", "").replace("_", "")) {
            "autointerface", "auto" -> Type.AUTO
            "tcpclientinterface", "tcpclient" -> Type.TCP_CLIENT
            "tcpserverinterface", "tcpserver" -> Type.TCP_SERVER
            "backboneinterface", "backboneclientinterface", "backbone" -> Type.BACKBONE
            "udpinterface", "udpserverinterface", "udp" -> Type.UDP
            "rnodeinterface", "rnode" -> Type.RNODE
            else -> null
        }

    private fun normalizeKey(raw: String): String = raw.trim().lowercase().replace('-', '_')

    /** Strips matching surrounding quotes and unescapes double-quoted values. */
    private fun unquote(raw: String): String {
        if (raw.length >= 2) {
            val first = raw.first()
            val last = raw.last()
            if (first == last && (first == '"' || first == '\'')) {
                val inner = raw.substring(1, raw.length - 1)
                return if (first == '"') {
                    inner.replace("\\\"", "\"").replace("\\\\", "\\")
                } else {
                    inner
                }
            }
        }
        return raw
    }

    private fun quoteIfNeeded(value: String): String =
        if (value.any { it.isWhitespace() || it in QUOTE_TRIGGER_CHARS }) {
            "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        } else {
            value
        }

    private fun validateHost(
        raw: String,
        key: String,
    ): String =
        when (val result = InputValidator.validateHostname(raw)) {
            is ValidationResult.Success -> result.value
            is ValidationResult.Error -> throw IllegalArgumentException("Invalid $key '$raw': ${result.message}")
        }

    private fun yesNo(value: Boolean): String = if (value) "yes" else "no"

    private val DISCOVERY_SCOPES = setOf("link", "admin", "site", "organisation", "global")

    private val COMMON_KEYS =
        setOf(
            "type",
            "enabled",
            "interface_enabled",
            "mode",
            "network_name",
            "networkname",
            "passphrase",
            "pass_phrase",
            "ifac_size",
        )

    private val RECOGNISED_KEYS =
        setOf(
            "target_host",
            "target_port",
            "listen_ip",
            "listen_port",
            "listen_on",
            "forward_ip",
            "forward_port",
            "kiss_framing",
            "i2p_tunneled",
            "remote",
            "port",
            "device",
            "group_id",
            "discovery_scope",
            "discovery_port",
            "data_port",
            "tcp_host",
            "tcp_port",
            "target_device_name",
            "frequency",
            "bandwidth",
            "txpower",
            "tx_power",
            "spreadingfactor",
            "spreading_factor",
            "codingrate",
            "coding_rate",
            "st_alock",
            "lt_alock",
        )

    /** A `[[Section]]` while it is being accumulated. */
    private class RawSection(val name: String) {
        val params = mutableMapOf<String, String>()
        val warnings = mutableListOf<String>()

        fun param(key: String): String? = params[key]?.takeIf { it.isNotBlank() && it.lowercase() != "none" }

        /** Literal text for credentials, where `none` can be a real value. */
        fun textParam(key: String): String? = params[key]?.takeIf { it.isNotBlank() }

        fun boolean(
            key: String,
            defaultValue: Boolean,
        ): Boolean {
            val value = params[key] ?: return defaultValue
            return when (value.trim().lowercase()) {
                "yes", "true", "on", "1" -> true
                "no", "false", "off", "0" -> false
                else -> defaultValue
            }
        }

        fun port(key: String): Int? {
            val value = param(key) ?: return null
            return when (val result = InputValidator.validatePort(value)) {
                is ValidationResult.Success -> result.value
                is ValidationResult.Error -> throw IllegalArgumentException("Invalid $key '$value': ${result.message}")
            }
        }

        fun int(key: String): Int? = param(key)?.toIntOrNull()

        fun long(key: String): Long? = param(key)?.toLongOrNull()

        fun double(key: String): Double? = param(key)?.toDoubleOrNull()

        fun ifacNetworkName(): String? = textParam("network_name") ?: textParam("networkname")

        fun ifacPassphrase(): String? = textParam("passphrase") ?: textParam("pass_phrase")

        fun mode(warnings: MutableList<String>): String {
            val raw = param("mode") ?: return InterfaceMode.FULL.value
            val mode = raw.lowercase()
            if (mode !in InterfaceMode.entries.map { it.value }) {
                warnings += "Unknown mode '$raw'; using full"
                return InterfaceMode.FULL.value
            }
            return mode
        }
    }
}

/** Rename any interface type without losing its concrete fields. */
internal fun InterfaceConfig.withName(name: String): InterfaceConfig =
    when (this) {
        is InterfaceConfig.AutoInterface -> copy(name = name)
        is InterfaceConfig.TCPClient -> copy(name = name)
        is InterfaceConfig.TCPServer -> copy(name = name)
        is InterfaceConfig.UDP -> copy(name = name)
        is InterfaceConfig.RNode -> copy(name = name)
        is InterfaceConfig.AndroidBLE -> copy(name = name)
    }
