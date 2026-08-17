package network.columba.app.navigation

/**
 * Canonical registry for every destination in Columba's application NavHost.
 *
 * [sampleRoute] is a concrete, navigable route used by the global Back contract
 * tests. New destinations must be registered here and through `appComposable`
 * so they automatically participate in those tests. New completion flows must
 * also declare [completionContract] and finish through `completeCurrentFlow`.
 * Event-driven entity destinations declare [externalIdentityArguments] and use
 * `navigateToEntity`, which enrolls them in repeated-delivery tests.
 */
enum class AppDestination(
    val routePattern: String,
    val sampleRoute: String = routePattern,
    val backContract: BackContract = BackContract.POP_TO_CALLER,
    val completionContract: CompletionContract = CompletionContract.NONE,
    val completionTargetRoute: String? = null,
    val externalIdentityArguments: Map<String, Any> = emptyMap(),
    val externalNavigationPolicy: ExternalNavigationPolicy = ExternalNavigationPolicy.NONE,
) {
    WELCOME("welcome", backContract = BackContract.ENTRY_GATE),
    IDENTITY_UNLOCK("identity_unlock", backContract = BackContract.ENTRY_GATE),
    CHATS("chats", backContract = BackContract.TOP_LEVEL),
    CALL_DETAILS("call_details/{callAttemptId}", "call_details/test-attempt"),

    ANNOUNCES("announce_stream?filterType={filterType}", "announce_stream?filterType=all"),
    CONTACTS("contacts", backContract = BackContract.TOP_LEVEL),
    MAP("map", backContract = BackContract.TOP_LEVEL),
    IDENTITY("identity"),
    SETTINGS("settings", backContract = BackContract.TOP_LEVEL),
    MAP_FOCUS(
        routePattern = "map_focus?lat={lat}&lon={lon}&label={label}&type={type}&height={height}" +
            "&reachableOn={reachableOn}&port={port}&frequency={frequency}&bandwidth={bandwidth}" +
            "&sf={sf}&cr={cr}&modulation={modulation}&status={status}&lastHeard={lastHeard}&hops={hops}",
        sampleRoute = "map_focus?lat=45.5&lon=-122.6&label=Test&type=RNode&height=10.0" +
            "&reachableOn=&port=-1&frequency=915000000&bandwidth=125000" +
            "&sf=10&cr=5&modulation=LoRa&status=online&lastHeard=1&hops=1",
    ),
    USB_DEVICE_ACTION(
        routePattern = "usb_device_action?usbDeviceId={usbDeviceId}&usbVendorId={usbVendorId}" +
            "&usbProductId={usbProductId}&usbDeviceName={usbDeviceName}&pyxisVersion={pyxisVersion}",
        sampleRoute = "usb_device_action?usbDeviceId=1&usbVendorId=2&usbProductId=3" +
            "&usbDeviceName=Test&pyxisVersion=1.0",
        externalIdentityArguments = mapOf("usbDeviceId" to 1),
        externalNavigationPolicy = ExternalNavigationPolicy.REUSE_SAME_ENTITY,
    ),
    PYXIS_UPDATER(
        "pyxis_updater?packageUri={packageUri}&usbDeviceId={usbDeviceId}",
        "pyxis_updater?packageUri=&usbDeviceId=-1",
    ),
    RNODE_FLASHER(
        routePattern = "rnode_flasher?skipDetection={skipDetection}&tncConfigOnly={tncConfigOnly}" +
            "&usbDeviceId={usbDeviceId}&usbVendorId={usbVendorId}" +
            "&usbProductId={usbProductId}&usbDeviceName={usbDeviceName}",
        sampleRoute = "rnode_flasher?skipDetection=false&tncConfigOnly=false&usbDeviceId=-1" +
            "&usbVendorId=-1&usbProductId=-1&usbDeviceName=",
        externalIdentityArguments = mapOf("usbDeviceId" to -1),
        externalNavigationPolicy = ExternalNavigationPolicy.REUSE_SAME_ENTITY,
    ),
    INTERFACE_MANAGEMENT("interface_management"),
    DISCOVERED_INTERFACES("discovered_interfaces"),
    TCP_CLIENT_WIZARD(
        routePattern = "tcp_client_wizard?interfaceId={interfaceId}&host={host}&port={port}&name={name}" +
            "&ifacNetname={ifacNetname}&ifacNetkey={ifacNetkey}",
        sampleRoute = "tcp_client_wizard?interfaceId=-1&host=&port=0&name=&ifacNetname=&ifacNetkey=",
        completionContract = CompletionContract.SHOW_RESULT,
        completionTargetRoute = "interface_management",
    ),
    RNODE_WIZARD(
        routePattern = "rnode_wizard?interfaceId={interfaceId}&repairPairing={repairPairing}&connectionType={connectionType}" +
            "&transportMode={transportMode}&usbDeviceId={usbDeviceId}&usbVendorId={usbVendorId}" +
            "&usbProductId={usbProductId}&usbDeviceName={usbDeviceName}&loraFrequency={loraFrequency}" +
            "&loraBandwidth={loraBandwidth}&loraSf={loraSf}&loraCr={loraCr}",
        sampleRoute = "rnode_wizard?interfaceId=-1&repairPairing=false&connectionType=&transportMode=false&usbDeviceId=-1" +
            "&usbVendorId=-1&usbProductId=-1&usbDeviceName=&loraFrequency=-1" +
            "&loraBandwidth=-1&loraSf=-1&loraCr=-1",
        completionContract = CompletionContract.SHOW_RESULT,
        completionTargetRoute = "interface_management",
        externalIdentityArguments = mapOf("usbDeviceId" to -1),
        externalNavigationPolicy = ExternalNavigationPolicy.REUSE_SAME_ENTITY,
    ),
    INTERFACE_STATS("interface_stats/{interfaceId}", "interface_stats/1"),
    NOTIFICATION_SETTINGS("notification_settings"),
    BLOCKED_USERS("blocked_users"),
    THEME_MANAGEMENT("theme_management"),
    THEME_EDITOR_NEW("theme_editor"),
    THEME_EDITOR_EXISTING("theme_editor/{themeId}", "theme_editor/1"),
    BLE_CONNECTION_STATUS("ble_connection_status"),
    IDENTITY_MANAGER(
        "identity_manager?base32Key={base32Key}",
        "identity_manager?base32Key=test",
        externalIdentityArguments = mapOf("base32Key" to "test"),
        externalNavigationPolicy = ExternalNavigationPolicy.REPLACE_DESTINATION,
    ),
    MIGRATION("migration"),
    APK_SHARING("apk_sharing"),
    MY_IDENTITY("my_identity"),
    NETWORK_STATUS("network_status"),
    QR_SCANNER("qr_scanner"),
    MESSAGING(
        routePattern = "messaging/{destinationHash}/{peerName}" +
            "?fromNotification={fromNotification}&notificationEventId={notificationEventId}",
        sampleRoute = "messaging/0123456789abcdef/Test?fromNotification=false&notificationEventId=0",
    ),
    MESSAGE_DETAIL("message_detail/{messageId}", "message_detail/1"),
    ANNOUNCE_DETAIL(
        "announce_detail/{destinationHash}",
        "announce_detail/0123456789abcdef",
        externalIdentityArguments = mapOf("destinationHash" to "0123456789abcdef"),
        externalNavigationPolicy = ExternalNavigationPolicy.REUSE_SAME_ENTITY,
    ),
    NOMADNET_BROWSER(
        "nomadnet_browser/{destinationHash}?path={path}",
        "nomadnet_browser/0123456789abcdef?path=%2Fpage%2Findex.mu",
        externalIdentityArguments =
            mapOf(
                "destinationHash" to "0123456789abcdef",
                "path" to "/page/index.mu",
            ),
        externalNavigationPolicy = ExternalNavigationPolicy.REUSE_SAME_ENTITY,
    ),
    OFFLINE_MAPS("offline_maps"),
    OFFLINE_MAP_DOWNLOAD(
        "offline_map_download?updateRegionId={updateRegionId}",
        "offline_map_download?updateRegionId=-1",
        completionContract = CompletionContract.RETURN_TO_CALLER,
    ),
    VOICE_CALL(
        "voice_call/{destinationHash}?autoAnswer={autoAnswer}&profileCode={profileCode}",
        "voice_call/0123456789abcdef?autoAnswer=false&profileCode=-1",
    ),
    INCOMING_CALL("incoming_call/{identityHash}", "incoming_call/0123456789abcdef"),
}

enum class BackContract {
    /** Root destinations own app-exit behavior rather than popping another app destination. */
    TOP_LEVEL,

    /** Entry gates are selected as start destinations and are removed when completed. */
    ENTRY_GATE,

    /** One Android Back press at the destination root returns to its caller. */
    POP_TO_CALLER,
}

enum class CompletionContract {
    NONE,

    /** Successful completion removes the flow and reveals its caller. */
    RETURN_TO_CALLER,

    /** Successful completion removes the flow and presents [AppDestination.completionTargetRoute]. */
    SHOW_RESULT,
}

enum class ExternalNavigationPolicy {
    NONE,
    REUSE_SAME_ENTITY,
    REPLACE_DESTINATION,
}
