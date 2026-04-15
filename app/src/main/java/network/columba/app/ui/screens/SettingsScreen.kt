package network.columba.app.ui.screens

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import network.columba.app.ui.components.BackgroundLocationPermissionBottomSheet
import network.columba.app.ui.components.LocationPermissionBottomSheet
import network.columba.app.ui.components.ServiceRestartBanner
import network.columba.app.ui.screens.settings.cards.AboutCard
import network.columba.app.ui.screens.settings.cards.AutoAnnounceCard
import network.columba.app.ui.screens.settings.cards.BatteryOptimizationCard
import network.columba.app.ui.screens.settings.cards.DataMigrationCard
import network.columba.app.ui.screens.settings.cards.IdentityCard
import network.columba.app.ui.screens.settings.cards.ImageCompressionCard
import network.columba.app.ui.screens.settings.cards.LocationSharingCard
import network.columba.app.ui.screens.settings.cards.MapSourcesCard
import network.columba.app.ui.screens.settings.cards.MessageDeliveryRetrievalCard
import network.columba.app.ui.screens.settings.cards.NetworkCard
import network.columba.app.ui.screens.settings.cards.NotificationSettingsCard
import network.columba.app.ui.screens.settings.cards.PrivacyCard
import network.columba.app.ui.screens.settings.cards.RNodeFlasherCard
import network.columba.app.ui.screens.settings.cards.ShareColumbaCard
import network.columba.app.ui.screens.settings.cards.SharedInstanceBannerCard
import network.columba.app.ui.screens.settings.cards.ThemeSelectionCard
import network.columba.app.ui.screens.settings.cards.VoiceCallPermissionsCard
import network.columba.app.ui.screens.settings.cards.shouldShowSharedInstanceBanner
import network.columba.app.ui.screens.settings.dialogs.CrashReportDialog
import network.columba.app.ui.screens.settings.dialogs.IdentityQrCodeDialog
import network.columba.app.ui.util.LifecycleGuard
import network.columba.app.util.CrashReport
import network.columba.app.util.CrashReportManager
import network.columba.app.util.DeviceInfoUtil
import network.columba.app.util.LocationPermissionManager
import network.columba.app.viewmodel.BlockedUsersViewModel
import network.columba.app.viewmodel.DebugViewModel
import network.columba.app.viewmodel.SettingsCardId
import network.columba.app.viewmodel.SettingsViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    crashReportManager: CrashReportManager,
    debugViewModel: DebugViewModel = hiltViewModel(),
    onNavigateToInterfaces: () -> Unit = {},
    onNavigateToIdentity: () -> Unit = {},
    onNavigateToNetworkStatus: () -> Unit = {},
    onNavigateToIdentityManager: () -> Unit = {},
    onNavigateToNotifications: () -> Unit = {},
    onNavigateToCustomThemes: () -> Unit = {},
    onNavigateToMigration: () -> Unit = {},
    onNavigateToAnnounces: (filterType: String?) -> Unit = {},
    onNavigateToFlasher: () -> Unit = {},
    onNavigateToApkSharing: () -> Unit = {},
    onNavigateToBlockedUsers: () -> Unit = {},
) {
    val blockedUsersViewModel: BlockedUsersViewModel = hiltViewModel()
    val state by viewModel.state.collectAsState()
    val blockedPeerCount by blockedUsersViewModel.blockedPeerCount.collectAsState()
    val qrCodeData by debugViewModel.qrCodeData.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Crash report dialog state
    var showCrashDialog by remember { mutableStateOf(false) }
    var pendingCrashReport by remember { mutableStateOf<CrashReport?>(null) }

    // Location permission state (for indicator in LocationSharingCard)
    var hasForegroundPermission by remember {
        mutableStateOf(LocationPermissionManager.hasPermission(context))
    }
    var hasBackgroundPermission by remember {
        mutableStateOf(LocationPermissionManager.hasBackgroundLocationPermission(context))
    }

    // Location permission state for telemetry collector
    var showTelemetryPermissionSheet by remember { mutableStateOf(false) }
    var showBackgroundLocationSheet by remember { mutableStateOf(false) }
    val telemetryPermissionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val backgroundLocationSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // Track what action to take after permission is granted
    var pendingTelemetryAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    // Background permission launcher must be declared first (referenced by foreground launcher)
    val telemetryBackgroundPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { granted ->
            hasBackgroundPermission = granted
            if (granted) {
                pendingTelemetryAction?.invoke()
            }
            pendingTelemetryAction = null
        }

    // Permission launcher for telemetry collector (foreground location)
    val telemetryPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val granted = permissions.values.any { it }
            if (granted) {
                // Foreground granted; check if background is also needed
                if (LocationPermissionManager.hasTelemetryBackgroundPermission(context)) {
                    pendingTelemetryAction?.invoke()
                    pendingTelemetryAction = null
                } else {
                    // Show custom bottom sheet before requesting background
                    showBackgroundLocationSheet = true
                    // pendingTelemetryAction is preserved for the background launcher
                }
            } else {
                pendingTelemetryAction = null
            }
        }

    // Refresh background permission state on lifecycle resume (e.g. returning from system settings)
    DisposableEffect(lifecycleOwner) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    hasForegroundPermission = LocationPermissionManager.hasPermission(context)
                    hasBackgroundPermission = LocationPermissionManager.hasBackgroundLocationPermission(context)
                }
            }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Check for pending crash report on launch
    LaunchedEffect(Unit) {
        if (!LifecycleGuard.isActiveForWindows(lifecycleOwner)) return@LaunchedEffect
        if (crashReportManager.hasPendingCrashReport()) {
            pendingCrashReport = crashReportManager.getPendingCrashReport()
            showCrashDialog = true
        }
    }

    // Show Snackbar when shared instance becomes available (ephemeral notification)
    LaunchedEffect(state.sharedInstanceAvailable) {
        if (state.sharedInstanceAvailable && !state.preferOwnInstance) {
            val result =
                snackbarHostState.showSnackbar(
                    message = "Shared instance available",
                    actionLabel = "Switch",
                    duration = SnackbarDuration.Indefinite,
                )
            when (result) {
                SnackbarResult.ActionPerformed -> viewModel.switchToSharedInstance()
                SnackbarResult.Dismissed -> viewModel.dismissSharedInstanceAvailable()
            }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        if (state.isLoading) {
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .consumeWindowInsets(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val showSharedInstanceBanner =
                    shouldShowSharedInstanceBanner(
                        isSharedInstance = state.isSharedInstance,
                        sharedInstanceOnline = state.sharedInstanceOnline,
                        wasUsingSharedInstance = state.wasUsingSharedInstance,
                        isRestarting = state.isRestarting,
                    )
                if (showSharedInstanceBanner) {
                    SharedInstanceBannerCard(
                        isExpanded = state.isSharedInstanceBannerExpanded,
                        isUsingSharedInstance = state.isSharedInstance,
                        rpcKey = state.rpcKey,
                        wasUsingSharedInstance = state.wasUsingSharedInstance,
                        sharedInstanceOnline = state.sharedInstanceOnline,
                        onExpandToggle = { viewModel.toggleSharedInstanceBannerExpanded(it) },
                        onTogglePreferOwnInstance = { viewModel.togglePreferOwnInstance(it) },
                        onRpcKeyChange = { viewModel.saveRpcKey(it) },
                    )
                }

                if (state.isRestarting) {
                    ServiceRestartBanner()
                }

                NetworkCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.NETWORK.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.NETWORK, it) },
                    onViewStatus = onNavigateToNetworkStatus,
                    onManageInterfaces = onNavigateToInterfaces,
                    isSharedInstance = state.isSharedInstance,
                    sharedInstanceOnline = state.sharedInstanceOnline,
                    transportNodeEnabled = state.transportNodeEnabled,
                    onTransportNodeToggle = { viewModel.setTransportNodeEnabled(it) },
                )

                IdentityCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.IDENTITY.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.IDENTITY, it) },
                    onViewIdentity = onNavigateToIdentity,
                    onManageIdentities = onNavigateToIdentityManager,
                )

                PrivacyCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.PRIVACY.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.PRIVACY, it) },
                    blockUnknownSenders = state.blockUnknownSenders,
                    onBlockUnknownSendersChange = { viewModel.setBlockUnknownSenders(it) },
                    blockedPeerCount = blockedPeerCount,
                    onNavigateToBlockedUsers = onNavigateToBlockedUsers,
                )

                NotificationSettingsCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.NOTIFICATIONS.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.NOTIFICATIONS, it) },
                    notificationsEnabled = state.notificationsEnabled,
                    onNotificationsEnabledChange = { viewModel.setNotificationsEnabled(it) },
                    onManageClick = onNavigateToNotifications,
                )

                VoiceCallPermissionsCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.VOICE_CALL_PERMISSIONS.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.VOICE_CALL_PERMISSIONS, it) },
                )

                AutoAnnounceCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.AUTO_ANNOUNCE.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.AUTO_ANNOUNCE, it) },
                    enabled = state.autoAnnounceEnabled,
                    intervalHours = state.autoAnnounceIntervalHours,
                    lastAnnounceTime = state.lastAutoAnnounceTime,
                    nextAnnounceTime = state.nextAutoAnnounceTime,
                    isManualAnnouncing = state.isManualAnnouncing,
                    showManualAnnounceSuccess = state.showManualAnnounceSuccess,
                    manualAnnounceError = state.manualAnnounceError,
                    onToggle = { viewModel.toggleAutoAnnounce(it) },
                    onIntervalChange = { viewModel.setAnnounceInterval(it) },
                    onManualAnnounce = { viewModel.triggerManualAnnounce() },
                )

                LocationSharingCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.LOCATION_SHARING.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.LOCATION_SHARING, it) },
                    enabled = state.locationSharingEnabled,
                    onEnabledChange = { viewModel.setLocationSharingEnabled(it) },
                    activeSessions = state.activeSharingSessions,
                    onStopSharing = { viewModel.stopSharingWith(it) },
                    onStopAllSharing = { viewModel.stopAllSharing() },
                    defaultDuration = state.defaultSharingDuration,
                    onDefaultDurationChange = { viewModel.setDefaultSharingDuration(it) },
                    locationPrecisionRadius = state.locationPrecisionRadius,
                    onLocationPrecisionRadiusChange = { viewModel.setLocationPrecisionRadius(it) },
                    // Telemetry collector props
                    telemetryCollectorEnabled = state.telemetryCollectorEnabled,
                    telemetryCollectorAddress = state.telemetryCollectorAddress,
                    telemetrySendIntervalSeconds = state.telemetrySendIntervalSeconds,
                    lastTelemetrySendTime = state.lastTelemetrySendTime,
                    isSendingTelemetry = state.isSendingTelemetry,
                    onTelemetryEnabledChange = { enabled ->
                        if (enabled) {
                            if (LocationPermissionManager.hasTelemetryBackgroundPermission(context)) {
                                viewModel.setTelemetryCollectorEnabled(true)
                            } else if (LocationPermissionManager.hasPermission(context)) {
                                // Foreground location is granted, show background permission sheet.
                                pendingTelemetryAction = { viewModel.setTelemetryCollectorEnabled(true) }
                                showBackgroundLocationSheet = true
                            } else {
                                // Show permission sheet, then enable after permission granted
                                pendingTelemetryAction = { viewModel.setTelemetryCollectorEnabled(true) }
                                showTelemetryPermissionSheet = true
                            }
                        } else {
                            // Disabling doesn't need permission
                            viewModel.setTelemetryCollectorEnabled(false)
                        }
                    },
                    onTelemetryCollectorAddressChange = { viewModel.setTelemetryCollectorAddress(it) },
                    onTelemetrySendIntervalChange = { viewModel.setTelemetrySendInterval(it) },
                    onTelemetrySendNow = {
                        // Check permission before sending
                        if (LocationPermissionManager.hasPermission(context)) {
                            viewModel.sendTelemetryNow()
                        } else {
                            pendingTelemetryAction = { viewModel.sendTelemetryNow() }
                            showTelemetryPermissionSheet = true
                        }
                    },
                    // Telemetry request props
                    telemetryRequestEnabled = state.telemetryRequestEnabled,
                    telemetryRequestIntervalSeconds = state.telemetryRequestIntervalSeconds,
                    lastTelemetryRequestTime = state.lastTelemetryRequestTime,
                    isRequestingTelemetry = state.isRequestingTelemetry,
                    onTelemetryRequestEnabledChange = { viewModel.setTelemetryRequestEnabled(it) },
                    onTelemetryRequestIntervalChange = { viewModel.setTelemetryRequestInterval(it) },
                    onRequestTelemetryNow = { viewModel.requestTelemetryNow() },
                    // Telemetry host mode props
                    telemetryHostModeEnabled = state.telemetryHostModeEnabled,
                    onTelemetryHostModeEnabledChange = { viewModel.setTelemetryHostModeEnabled(it) },
                    // Allowed requesters props
                    telemetryAllowedRequesters = state.telemetryAllowedRequesters,
                    contacts = state.contacts,
                    onTelemetryAllowedRequestersChange = { viewModel.setTelemetryAllowedRequesters(it) },
                    // Local identity for "Myself" option in host picker
                    localDestinationHash = state.destinationHash,
                    localDisplayName = state.displayName.ifEmpty { state.defaultDisplayName },
                    localIconName = state.iconName,
                    localIconForegroundColor = state.iconForegroundColor,
                    localIconBackgroundColor = state.iconBackgroundColor,
                    // Background location permission indicator
                    hasForegroundLocationPermission = hasForegroundPermission,
                    hasBackgroundLocationPermission = hasBackgroundPermission,
                    onBackgroundPermissionClick = {
                        if (hasBackgroundPermission) {
                            // Already granted — open app info, guide user to Permissions > Location
                            Toast
                                .makeText(
                                    context,
                                    "Go to Permissions > Location to change",
                                    Toast.LENGTH_LONG,
                                ).show()
                            val intent =
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.fromParts("package", context.packageName, null),
                                )
                            context.startActivity(intent)
                        } else if (LocationPermissionManager.hasPermission(context)) {
                            // Foreground granted but not background — show our custom sheet
                            showBackgroundLocationSheet = true
                        } else {
                            // No foreground either — show foreground sheet first
                            pendingTelemetryAction = null
                            showTelemetryPermissionSheet = true
                        }
                    },
                )

                MapSourcesCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.MAP_SOURCES.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.MAP_SOURCES, it) },
                    httpEnabled = state.mapSourceHttpEnabled,
                    onHttpEnabledChange = { viewModel.setMapSourceHttpEnabled(it) },
                    rmspEnabled = state.mapSourceRmspEnabled,
                    onRmspEnabledChange = { viewModel.setMapSourceRmspEnabled(it) },
                    markerDeclutterEnabled = state.mapMarkerDeclutterEnabled,
                    onMarkerDeclutterEnabledChange = { viewModel.setMapMarkerDeclutterEnabled(it) },
                    rmspServerCount = state.rmspServerCount,
                    hasOfflineMaps = state.hasOfflineMaps,
                )

                MessageDeliveryRetrievalCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.MESSAGE_DELIVERY.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.MESSAGE_DELIVERY, it) },
                    defaultMethod = state.defaultDeliveryMethod,
                    tryPropagationOnFail = state.tryPropagationOnFail,
                    currentRelayName = state.currentRelayName,
                    currentRelayHops = state.currentRelayHops,
                    currentRelayHash = state.currentRelayHash,
                    isAutoSelect = state.autoSelectPropagationNode,
                    availableRelays = state.availableRelays,
                    onMethodChange = { viewModel.setDefaultDeliveryMethod(it) },
                    onTryPropagationToggle = { viewModel.setTryPropagationOnFail(it) },
                    onAutoSelectToggle = { viewModel.setAutoSelectPropagationNode(it) },
                    onAddManualRelay = { hash, nickname ->
                        viewModel.addManualPropagationNode(hash, nickname)
                    },
                    onSelectRelay = { hash, _ ->
                        viewModel.selectRelay(hash)
                    },
                    // Retrieval settings
                    autoRetrieveEnabled = state.autoRetrieveEnabled,
                    retrievalIntervalSeconds = state.retrievalIntervalSeconds,
                    lastSyncTimestamp = state.lastSyncTimestamp,
                    isSyncing = state.isSyncing,
                    onAutoRetrieveToggle = { viewModel.setAutoRetrieveEnabled(it) },
                    onIntervalChange = { viewModel.setRetrievalIntervalSeconds(it) },
                    onSyncNow = { viewModel.syncNow() },
                    onViewMoreRelays = { onNavigateToAnnounces("PROPAGATION_NODE") },
                    // Incoming message size limit
                    incomingMessageSizeLimitKb = state.incomingMessageSizeLimitKb,
                    onIncomingMessageSizeLimitChange = { viewModel.setIncomingMessageSizeLimit(it) },
                    // Message sorting
                    sortMessagesBySentTime = state.sortMessagesBySentTime,
                    onSortMessagesBySentTimeToggle = { viewModel.setSortMessagesBySentTime(it) },
                )

                ImageCompressionCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.IMAGE_COMPRESSION.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.IMAGE_COMPRESSION, it) },
                    selectedPreset = state.imageCompressionPreset,
                    detectedPreset = state.detectedCompressionPreset,
                    hasSlowInterface =
                        state.detectedCompressionPreset ==
                            network.columba.app.data.model.ImageCompressionPreset.LOW,
                    onPresetChange = { viewModel.setImageCompressionPreset(it) },
                )

                ThemeSelectionCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.THEME.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.THEME, it) },
                    selectedTheme = state.selectedTheme,
                    customThemes = state.customThemes,
                    onThemeChange = { viewModel.setTheme(it) },
                    onNavigateToCustomThemes = onNavigateToCustomThemes,
                )

                BatteryOptimizationCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.BATTERY.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.BATTERY, it) },
                    batteryProfile = state.batteryProfile,
                    onBatteryProfileChange = { viewModel.setBatteryProfile(it) },
                )

                DataMigrationCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.DATA_MIGRATION.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.DATA_MIGRATION, it) },
                    onNavigateToMigration = onNavigateToMigration,
                )

                ShareColumbaCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.SHARE_COLUMBA.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.SHARE_COLUMBA, it) },
                    onNavigateToApkSharing = onNavigateToApkSharing,
                )

                RNodeFlasherCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.RNODE_FLASHER.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.RNODE_FLASHER, it) },
                    onOpenFlasher = onNavigateToFlasher,
                )

                // About section
                val systemInfo =
                    remember(
                        state.identityHash,
                        state.reticulumVersion,
                        state.lxmfVersion,
                        state.bleReticulumVersion,
                        state.lxstVersion,
                    ) {
                        DeviceInfoUtil.getSystemInfo(
                            context = context,
                            identityHash = state.identityHash,
                            reticulumVersion = state.reticulumVersion,
                            lxmfVersion = state.lxmfVersion,
                            bleReticulumVersion = state.bleReticulumVersion,
                            lxstVersion = state.lxstVersion,
                        )
                    }

                AboutCard(
                    isExpanded = state.cardExpansionStates[SettingsCardId.ABOUT.name] ?: false,
                    onExpandedChange = { viewModel.toggleCardExpanded(SettingsCardId.ABOUT, it) },
                    systemInfo = systemInfo,
                    updateCheckResult = state.updateCheckResult,
                    includePrereleaseUpdates = state.includePrereleaseUpdates,
                    onCheckForUpdates = { viewModel.checkForUpdates() },
                    onSetIncludePrereleaseUpdates = { viewModel.setIncludePrereleaseUpdates(it) },
                    onCopySystemInfo = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("System Info", DeviceInfoUtil.formatForClipboard(systemInfo))
                        clipboard.setPrimaryClip(clip)

                        // Show snackbar confirmation
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                message = "System info copied to clipboard",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                    onReportBug = {
                        coroutineScope.launch {
                            val report = crashReportManager.generateBugReport(systemInfo)
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Bug Report", report)
                            clipboard.setPrimaryClip(clip)

                            // Open GitHub Issues in browser
                            val intent =
                                Intent(
                                    Intent.ACTION_VIEW,
                                    Uri.parse("https://github.com/torlando-tech/columba/issues/new"),
                                )
                            context.startActivity(intent)

                            snackbarHostState.showSnackbar(
                                message = "Bug report copied to clipboard",
                                duration = SnackbarDuration.Short,
                            )
                        }
                    },
                )

                // Bottom spacing for navigation bar
                Spacer(modifier = Modifier.height(100.dp))
            }
        }

        // QR Code Dialog
        if (state.showQrDialog && state.identityHash != null) {
            IdentityQrCodeDialog(
                displayName = state.displayName,
                identityHash = state.identityHash,
                destinationHash = state.destinationHash,
                qrCodeData = qrCodeData,
                onDismiss = { viewModel.toggleQrDialog(false) },
                onShareClick = {
                    val shareText = debugViewModel.generateShareText(state.displayName)
                    if (shareText != null) {
                        val sendIntent =
                            Intent().apply {
                                action = Intent.ACTION_SEND
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                type = "text/plain"
                            }
                        val shareIntent = Intent.createChooser(sendIntent, "Share Identity")
                        context.startActivity(shareIntent)
                    }
                },
            )
        }

        // (restart banner is shown inline in the Column above)

        // Crash Report Dialog
        // Only show if activity is at least STARTED to prevent BadTokenException
        val isLifecycleActive = LifecycleGuard.isActiveForWindows(lifecycleOwner)
        if (showCrashDialog && pendingCrashReport != null && isLifecycleActive) {
            CrashReportDialog(
                crashReport = pendingCrashReport!!,
                onDismiss = {
                    crashReportManager.clearPendingCrashReport()
                    showCrashDialog = false
                    pendingCrashReport = null
                },
                onReportBug = {
                    val systemInfo =
                        DeviceInfoUtil.getSystemInfo(
                            context = context,
                            identityHash = state.identityHash,
                            reticulumVersion = state.reticulumVersion,
                            lxmfVersion = state.lxmfVersion,
                            bleReticulumVersion = state.bleReticulumVersion,
                            lxstVersion = state.lxstVersion,
                        )
                    coroutineScope.launch {
                        val report = crashReportManager.generateBugReport(systemInfo, pendingCrashReport)
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("Bug Report", report)
                        clipboard.setPrimaryClip(clip)

                        // Open GitHub Issues in browser
                        val intent =
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/torlando-tech/columba/issues/new"),
                            )
                        context.startActivity(intent)

                        crashReportManager.clearPendingCrashReport()
                        showCrashDialog = false
                        pendingCrashReport = null

                        snackbarHostState.showSnackbar(
                            message = "Bug report copied to clipboard",
                            duration = SnackbarDuration.Short,
                        )
                    }
                },
            )
        }

        // Background Location Permission Bottom Sheet (shared for Telemetry Collector and Location Sharing flows)
        if (showBackgroundLocationSheet && isLifecycleActive) {
            BackgroundLocationPermissionBottomSheet(
                onDismiss = {
                    showBackgroundLocationSheet = false
                    pendingTelemetryAction = null
                },
                onRequestPermission = {
                    showBackgroundLocationSheet = false
                    telemetryBackgroundPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                },
                sheetState = backgroundLocationSheetState,
            )
        }

        // Location Permission Bottom Sheet for Telemetry Collector
        // Only show if activity is at least STARTED to prevent BadTokenException
        if (showTelemetryPermissionSheet && isLifecycleActive) {
            LocationPermissionBottomSheet(
                onDismiss = {
                    showTelemetryPermissionSheet = false
                    pendingTelemetryAction = null
                },
                onRequestPermissions = {
                    showTelemetryPermissionSheet = false
                    telemetryPermissionLauncher.launch(
                        LocationPermissionManager.getRequiredPermissions().toTypedArray(),
                    )
                },
                sheetState = telemetryPermissionSheetState,
                rationale =
                    "Group Tracker shares your location with a group host " +
                        "so everyone can see where each other is.\n\n" +
                        "Your location is encrypted and only readable by the group host you configure.",
                primaryActionLabel = "Grant Location Access",
            )
        }
    }
}
