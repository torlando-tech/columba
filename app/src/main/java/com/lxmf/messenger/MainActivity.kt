package com.lxmf.messenger

import android.Manifest
import android.content.Context
import android.content.Intent
import com.lxmf.messenger.notifications.CallNotificationHelper
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Contacts
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.repository.InterfaceRepository
import com.lxmf.messenger.repository.SettingsRepository
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager
import com.lxmf.messenger.reticulum.call.bridge.CallBridge
import com.lxmf.messenger.reticulum.call.bridge.CallState
import com.lxmf.messenger.service.ReticulumService
import com.lxmf.messenger.ui.components.BlePermissionBottomSheet
import com.lxmf.messenger.ui.screens.AnnounceDetailScreen
import com.lxmf.messenger.ui.screens.AnnounceStreamScreen
import com.lxmf.messenger.ui.screens.BleConnectionStatusScreen
import com.lxmf.messenger.ui.screens.ChatsScreen
import com.lxmf.messenger.ui.screens.ContactsScreen
import com.lxmf.messenger.ui.screens.IdentityManagerScreen
import com.lxmf.messenger.ui.screens.IdentityScreen
import com.lxmf.messenger.ui.screens.InterfaceManagementScreen
import com.lxmf.messenger.ui.screens.MapScreen
import com.lxmf.messenger.ui.screens.MessageDetailScreen
import com.lxmf.messenger.ui.screens.MessagingScreen
import com.lxmf.messenger.ui.screens.MigrationScreen
import com.lxmf.messenger.ui.screens.MyIdentityScreen
import com.lxmf.messenger.ui.screens.NotificationSettingsScreen
import com.lxmf.messenger.ui.screens.QrScannerScreen
import com.lxmf.messenger.ui.screens.SettingsScreen
import com.lxmf.messenger.ui.screens.ThemeEditorScreen
import com.lxmf.messenger.ui.screens.ThemeManagementScreen
import com.lxmf.messenger.ui.screens.VoiceCallScreen
import com.lxmf.messenger.ui.screens.IncomingCallScreen
import com.lxmf.messenger.ui.screens.offlinemaps.OfflineMapDownloadScreen
import com.lxmf.messenger.ui.screens.offlinemaps.OfflineMapsScreen
import com.lxmf.messenger.ui.screens.onboarding.OnboardingPagerScreen
import com.lxmf.messenger.ui.screens.tcpclient.TcpClientWizardScreen
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.viewmodel.ContactsViewModel
import com.lxmf.messenger.viewmodel.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Main activity for the Columba LXMF Messenger application.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var interfaceRepository: InterfaceRepository

    // State to hold pending navigation from intent
    private val pendingNavigation = mutableStateOf<PendingNavigation?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen before super.onCreate()
        // Splash screen will be displayed until theme is loaded, preventing flash
        val splashScreen = installSplashScreen()
        var isThemeReady = false
        splashScreen.setKeepOnScreenCondition { !isThemeReady }

        super.onCreate(savedInstanceState)

        // Enable edge-to-edge mode for proper IME insets handling
        enableEdgeToEdge()

        // Reset location permission sheet dismissal on fresh app launch
        // This ensures users see the permission prompt again when the app is relaunched
        if (savedInstanceState == null) {
            lifecycleScope.launch {
                settingsRepository.resetLocationPermissionSheetDismissal()
            }
        }

        // Process the intent that launched the activity
        processIntent(intent)

        setContent {
            // Signal splash screen dismissal once theme loads
            val settingsViewModel: com.lxmf.messenger.viewmodel.SettingsViewModel = hiltViewModel()
            val settingsState by settingsViewModel.state.collectAsState()

            // Dismiss splash screen once theme is loaded (non-default theme or after timeout)
            LaunchedEffect(settingsState.selectedTheme, settingsState.isLoading) {
                if (!settingsState.isLoading) {
                    isThemeReady = true
                    Log.d(TAG, "Theme loaded: ${settingsState.selectedTheme.displayName} - dismissing splash")
                }
            }

            ColumbaNavigation(
                pendingNavigation = pendingNavigation,
                interfaceRepository = interfaceRepository,
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        if (intent == null) return

        Log.w(TAG, "📞 processIntent() - action=${intent.action}, extras=${intent.extras?.keySet()}")

        when (intent.action) {
            NotificationHelper.ACTION_OPEN_ANNOUNCE -> {
                val destinationHash = intent.getStringExtra(NotificationHelper.EXTRA_DESTINATION_HASH)
                if (destinationHash != null) {
                    Log.d(TAG, "Opening announce detail for: $destinationHash")
                    pendingNavigation.value = PendingNavigation.AnnounceDetail(destinationHash)
                }
            }
            NotificationHelper.ACTION_OPEN_CONVERSATION -> {
                val destinationHash = intent.getStringExtra(NotificationHelper.EXTRA_DESTINATION_HASH)
                val peerName = intent.getStringExtra(NotificationHelper.EXTRA_PEER_NAME)
                if (destinationHash != null && peerName != null) {
                    Log.d(TAG, "Opening conversation with: $peerName ($destinationHash)")
                    pendingNavigation.value = PendingNavigation.Conversation(destinationHash, peerName)
                }
            }
            Intent.ACTION_VIEW -> {
                // Handle lxma:// deep links
                val data = intent.data
                if (data != null && data.scheme == "lxma") {
                    val lxmaUrl = data.toString()
                    Log.d(TAG, "Opening LXMF deep link: $lxmaUrl")
                    pendingNavigation.value = PendingNavigation.AddContact(lxmaUrl)
                }
            }
            CallNotificationHelper.ACTION_OPEN_CALL -> {
                // Handle incoming call notification tap
                val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
                if (identityHash != null) {
                    Log.d(TAG, "Opening incoming call screen for: ${identityHash.take(16)}...")
                    pendingNavigation.value = PendingNavigation.IncomingCall(identityHash)
                }
            }
            CallNotificationHelper.ACTION_ANSWER_CALL -> {
                // Handle answer from notification - go directly to voice call and auto-answer
                val identityHash = intent.getStringExtra(CallNotificationHelper.EXTRA_IDENTITY_HASH)
                Log.w(TAG, "📞 ACTION_ANSWER_CALL received! identityHash=$identityHash")
                if (identityHash != null) {
                    Log.w(TAG, "📞 Setting pendingNavigation to AnswerCall($identityHash)")
                    // Cancel the incoming call notification
                    CallNotificationHelper(this).cancelIncomingCallNotification()
                    pendingNavigation.value = PendingNavigation.AnswerCall(identityHash)
                    Log.w(TAG, "📞 pendingNavigation.value is now: ${pendingNavigation.value}")
                } else {
                    Log.e(TAG, "📞 identityHash is NULL! Cannot navigate to call")
                }
            }
        }
    }
}

/**
 * Represents a pending navigation action from an intent.
 */
sealed class PendingNavigation {
    data class AnnounceDetail(val destinationHash: String) : PendingNavigation()

    data class Conversation(val destinationHash: String, val peerName: String) : PendingNavigation()

    data class AddContact(val lxmaUrl: String) : PendingNavigation()

    data class IncomingCall(val identityHash: String) : PendingNavigation()

    data class AnswerCall(val identityHash: String) : PendingNavigation()
}

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Welcome : Screen("welcome", "Welcome", Icons.Default.Sensors)

    object Chats : Screen("chats", "Chats", Icons.Default.Chat)

    object Announces : Screen("announce_stream", "Announces", Icons.Default.Sensors)

    object Contacts : Screen("contacts", "Contacts", Icons.Default.People)

    object Map : Screen("map", "Map", Icons.Default.Map)

    object Identity : Screen("identity", "Network Status", Icons.Default.Info)

    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumbaNavigation(
    pendingNavigation: MutableState<PendingNavigation?>,
    interfaceRepository: InterfaceRepository,
) {
    val context = LocalContext.current
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }

    // Track if we're currently navigating to answer a call (prevents race with callState observer)
    var isAnsweringCall by remember { mutableStateOf(false) }

    // Access SettingsViewModel to get theme preference
    val settingsViewModel: com.lxmf.messenger.viewmodel.SettingsViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()

    // Collect settings state (includes theme preference)
    val settingsState by settingsViewModel.state.collectAsState()

    // Access OnboardingViewModel to check onboarding status
    val onboardingViewModel: OnboardingViewModel = hiltViewModel()
    val onboardingState by onboardingViewModel.state.collectAsState()

    // Determine start destination based on onboarding status
    val startDestination =
        if (onboardingState.hasCompletedOnboarding) {
            Screen.Chats.route
        } else {
            Screen.Welcome.route
        }

    // Notification permission is now handled in OnboardingPagerScreen

    // State for pending contact add from deep link
    var pendingContactAdd by remember { mutableStateOf<String?>(null) }

    // Handle pending navigation from intents
    LaunchedEffect(pendingNavigation.value) {
        pendingNavigation.value?.let { navigation ->
            when (navigation) {
                is PendingNavigation.AnnounceDetail -> {
                    val encodedHash = Uri.encode(navigation.destinationHash)
                    navController.navigate("announce_detail/$encodedHash")
                    Log.d("ColumbaNavigation", "Navigated to announce detail: ${navigation.destinationHash}")
                }
                is PendingNavigation.Conversation -> {
                    val encodedHash = Uri.encode(navigation.destinationHash)
                    val encodedName = Uri.encode(navigation.peerName)
                    navController.navigate("messaging/$encodedHash/$encodedName")
                    Log.d("ColumbaNavigation", "Navigated to conversation: ${navigation.peerName}")
                }
                is PendingNavigation.AddContact -> {
                    // Navigate to contacts tab and trigger add contact dialog
                    selectedTab = 1 // Contacts tab
                    navController.navigate(Screen.Contacts.route) {
                        popUpTo(navController.graph.startDestinationId) {
                            saveState = true
                        }
                        launchSingleTop = true
                        restoreState = true
                    }
                    pendingContactAdd = navigation.lxmaUrl
                    Log.d("ColumbaNavigation", "Navigated to contacts for deep link: ${navigation.lxmaUrl}")
                }
                is PendingNavigation.IncomingCall -> {
                    // Navigate to incoming call screen
                    val encodedHash = Uri.encode(navigation.identityHash)
                    navController.navigate("incoming_call/$encodedHash")
                    Log.d("ColumbaNavigation", "Navigated to incoming call: ${navigation.identityHash.take(16)}...")
                }
                is PendingNavigation.AnswerCall -> {
                    // Set flag to prevent callState observer from overriding navigation
                    isAnsweringCall = true
                    // Navigate to voice call screen with auto-answer flag
                    val encodedHash = Uri.encode(navigation.identityHash)
                    val route = "voice_call/$encodedHash?autoAnswer=true"
                    Log.w("ColumbaNavigation", "📞 AnswerCall handler - navigating to $route")
                    Log.w("ColumbaNavigation", "📞 Current backstack: ${navController.currentBackStackEntry?.destination?.route}")
                    navController.navigate(route) {
                        launchSingleTop = true
                    }
                    Log.w("ColumbaNavigation", "📞 After navigation, current: ${navController.currentBackStackEntry?.destination?.route}")
                }
            }
            // Clear the pending navigation after handling
            pendingNavigation.value = null
        }
    }

    // Bluetooth permission state
    var showPermissionBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // Shared flag with BluetoothPermissionController to know if we've already
    // triggered a runtime permission request in a previous run.
    val bluetoothPrefs =
        remember(context) {
            context.getSharedPreferences("bluetooth_permission_prefs", Context.MODE_PRIVATE)
        }
    var hasRequestedBluetoothOnce by remember {
        mutableStateOf(
            bluetoothPrefs.getBoolean("hasRequestedBluetoothPermissions", false),
        )
    }

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            // Check if any BLE permissions were granted
            val blePermissions =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    listOf(
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_ADVERTISE,
                    )
                } else {
                    emptyList()
                }

            val blePermissionsGranted =
                permissions.filter { (permission, granted) ->
                    permission in blePermissions && granted
                }

            // If any BLE permissions were just granted, restart BLE interface
            if (blePermissionsGranted.isNotEmpty()) {
                Log.d("ColumbaNavigation", "BLE permissions granted, restarting BLE interface")
                val restartIntent =
                    Intent(context, ReticulumService::class.java).apply {
                        action = "com.lxmf.messenger.RESTART_BLE"
                    }
                context.startService(restartIntent)
            }

            val allGranted = permissions.values.all { it }
            if (!allGranted) {
                // User denied some permissions - they can still use the app,
                // but BLE features won't work
                Log.d("ColumbaNavigation", "Some permissions denied")
                hasRequestedBluetoothOnce = true
                bluetoothPrefs.edit().putBoolean("hasRequestedBluetoothPermissions", true).apply()
            }
        }

    // Check BLE permissions after onboarding is completed.
    // Only show if a Bluetooth-requiring interface (AndroidBLE or RNode) is enabled.
    // Don't show during onboarding - it's handled in OnboardingPagerScreen.
    val hasEnabledBluetoothInterface by interfaceRepository.hasEnabledBluetoothInterface.collectAsState(
        initial = false,
    )
    LaunchedEffect(onboardingState.hasCompletedOnboarding, hasEnabledBluetoothInterface) {
        if (onboardingState.hasCompletedOnboarding &&
            hasEnabledBluetoothInterface &&
            !BlePermissionManager.hasAllPermissions(context)
        ) {
            showPermissionBottomSheet = true
        }
    }

    // Track current navigation state
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Synchronize selectedTab with current route when navigating back
    LaunchedEffect(currentRoute) {
        selectedTab =
            when (currentRoute) {
                Screen.Chats.route -> 0
                Screen.Contacts.route -> 1
                Screen.Map.route -> 2
                Screen.Settings.route -> 3
                else -> selectedTab // Keep current selection for nested screens
            }
    }

    // Observe CallBridge state for incoming calls and navigate to IncomingCallScreen
    val callBridge = remember { CallBridge.getInstance() }
    val callState by callBridge.callState.collectAsState()

    LaunchedEffect(callState) {
        when (val state = callState) {
            is CallState.Incoming -> {
                val identityHash = state.identityHash
                Log.i("MainActivity", "📞 Incoming call detected, currentRoute=$currentRoute, isAnsweringCall=$isAnsweringCall")
                val encodedHash = Uri.encode(identityHash)
                // Only navigate if not already on a call screen and not answering from notification
                val isOnCallScreen = currentRoute?.startsWith("incoming_call/") == true ||
                    currentRoute?.startsWith("voice_call/") == true
                if (!isOnCallScreen && !isAnsweringCall) {
                    Log.i("MainActivity", "📞 Navigating to IncomingCallScreen: $identityHash")
                    navController.navigate("incoming_call/$encodedHash")
                } else {
                    Log.i("MainActivity", "📞 Skipping navigation (onCallScreen=$isOnCallScreen, isAnsweringCall=$isAnsweringCall)")
                }
            }
            else -> {
                // Reset the answering flag when call state changes from Incoming
                if (isAnsweringCall) {
                    isAnsweringCall = false
                }
            }
        }
    }

    // Screens that should hide the bottom navigation bar
    val hideBottomNavScreens =
        listOf(
            Screen.Welcome.route,
            "interface_management",
            "ble_connection_status",
            "theme_management",
            "tcp_client_wizard",
            "offline_maps",
            "offline_map_download",
        )
    val hideBottomNavPrefixes =
        listOf(
            "messaging/",
            "announce_detail/",
            "message_detail/",
            "theme_editor",
            "rnode_wizard",
            "voice_call/",
            "incoming_call/",
        )
    val shouldShowBottomNav =
        currentRoute != null &&
            currentRoute !in hideBottomNavScreens &&
            hideBottomNavPrefixes.none { currentRoute.startsWith(it) }

    val screens =
        listOf(
            Screen.Chats,
            Screen.Contacts,
            Screen.Map,
            Screen.Settings,
        )

    ColumbaTheme(selectedTheme = settingsState.selectedTheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            @Suppress("UnusedMaterial3ScaffoldPaddingParameter")
            Scaffold(
                bottomBar = {
                    if (shouldShowBottomNav) {
                        NavigationBar {
                            screens.forEachIndexed { index, screen ->
                                NavigationBarItem(
                                    icon = { Icon(screen.icon, contentDescription = null) },
                                    label = { Text(screen.title) },
                                    selected = selectedTab == index,
                                    onClick = {
                                        selectedTab = index
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.startDestinationId) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    },
                                )
                            }
                        }
                    }
                },
            ) { _ ->
                // Inner screens have their own Scaffolds with TopAppBars that handle content padding
                NavHost(
                    navController = navController,
                    startDestination = startDestination,
                    enterTransition = { fadeIn(tween(150)) },
                    exitTransition = { fadeOut(tween(75)) },
                    popEnterTransition = { fadeIn(tween(150)) },
                    popExitTransition = { fadeOut(tween(75)) },
                ) {
                    composable(Screen.Welcome.route) {
                        OnboardingPagerScreen(
                            onOnboardingComplete = { navigateToRNodeWizard ->
                                navController.navigate(Screen.Chats.route) {
                                    popUpTo(Screen.Welcome.route) { inclusive = true }
                                }
                                // Navigate to RNode wizard if LoRa Radio was selected
                                if (navigateToRNodeWizard) {
                                    navController.navigate("rnode_wizard")
                                }
                            },
                            onImportData = {
                                navController.navigate("migration")
                            },
                        )
                    }

                    composable(Screen.Chats.route) {
                        ChatsScreen(
                            onChatClick = { destinationHash, peerName ->
                                val encodedHash = Uri.encode(destinationHash)
                                val encodedName = Uri.encode(peerName)
                                navController.navigate("messaging/$encodedHash/$encodedName")
                            },
                            onViewPeerDetails = { peerHash ->
                                val encodedHash = Uri.encode(peerHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                        )
                    }

                    composable(
                        route = "${Screen.Announces.route}?filterType={filterType}",
                        arguments =
                            listOf(
                                navArgument("filterType") {
                                    type = NavType.StringType
                                    nullable = true
                                    defaultValue = null
                                },
                            ),
                    ) { backStackEntry ->
                        val filterType = backStackEntry.arguments?.getString("filterType")
                        AnnounceStreamScreen(
                            initialFilterType = filterType,
                            onPeerClick = { destinationHash, _ ->
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                            onStartChat = { destinationHash, peerName ->
                                val encodedHash = Uri.encode(destinationHash)
                                val encodedName = Uri.encode(peerName)
                                navController.navigate("messaging/$encodedHash/$encodedName")
                            },
                        )
                    }

                    composable(Screen.Contacts.route) {
                        val contactsViewModel: ContactsViewModel = hiltViewModel()
                        ContactsScreen(
                            onContactClick = { destinationHash, displayName ->
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                            onViewPeerDetails = { destinationHash ->
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                            onNavigateToQrScanner = {
                                navController.navigate("qr_scanner")
                            },
                            pendingDeepLinkContact = pendingContactAdd,
                            onDeepLinkContactProcessed = {
                                pendingContactAdd = null
                            },
                            onNavigateToConversation = { destinationHash ->
                                val contacts = contactsViewModel.contacts.value
                                val contact = contacts.find { it.destinationHash == destinationHash }
                                val peerName = contact?.displayName ?: destinationHash.take(16)
                                val encodedHash = Uri.encode(destinationHash)
                                val encodedName = Uri.encode(peerName)
                                navController.navigate("messaging/$encodedHash/$encodedName")
                            },
                        )
                    }

                    composable(Screen.Map.route) {
                        MapScreen(
                            onNavigateToConversation = { destinationHash ->
                                // Navigate to messaging screen with the contact
                                val encodedHash = Uri.encode(destinationHash)
                                // Use a placeholder name - the messaging screen will fetch the actual name
                                navController.navigate("messaging/$encodedHash/Contact")
                            },
                            onNavigateToOfflineMaps = {
                                navController.navigate("offline_maps")
                            },
                        )
                    }

                    composable(Screen.Identity.route) {
                        IdentityScreen(
                            onBackClick = { navController.popBackStack() },
                            settingsViewModel = settingsViewModel,
                            onNavigateToBleStatus = {
                                navController.navigate("ble_connection_status")
                            },
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
                            viewModel = settingsViewModel,
                            onNavigateToInterfaces = {
                                navController.navigate("interface_management")
                            },
                            onNavigateToIdentity = {
                                navController.navigate("my_identity")
                            },
                            onNavigateToNetworkStatus = {
                                navController.navigate("network_status")
                            },
                            onNavigateToIdentityManager = {
                                navController.navigate("identity_manager")
                            },
                            onNavigateToNotifications = {
                                navController.navigate("notification_settings")
                            },
                            onNavigateToCustomThemes = {
                                navController.navigate("theme_management")
                            },
                            onNavigateToMigration = {
                                navController.navigate("migration")
                            },
                            onNavigateToAnnounces = { filterType ->
                                selectedTab = 1 // Announces tab
                                val route =
                                    if (filterType != null) {
                                        "${Screen.Announces.route}?filterType=$filterType"
                                    } else {
                                        Screen.Announces.route
                                    }
                                navController.navigate(route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = false // Don't restore state so filter applies
                                }
                            },
                        )
                    }

                    composable("interface_management") {
                        InterfaceManagementScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToRNodeWizard = { interfaceId ->
                                if (interfaceId != null) {
                                    navController.navigate("rnode_wizard?interfaceId=$interfaceId")
                                } else {
                                    navController.navigate("rnode_wizard")
                                }
                            },
                            onNavigateToTcpClientWizard = {
                                navController.navigate("tcp_client_wizard")
                            },
                        )
                    }

                    composable("tcp_client_wizard") {
                        TcpClientWizardScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onComplete = {
                                navController.navigate("interface_management") {
                                    popUpTo("interface_management") { inclusive = true }
                                }
                            },
                        )
                    }

                    composable(
                        route = "rnode_wizard?interfaceId={interfaceId}",
                        arguments =
                            listOf(
                                navArgument("interfaceId") {
                                    type = NavType.LongType
                                    defaultValue = -1L
                                },
                            ),
                    ) { backStackEntry ->
                        val interfaceId = backStackEntry.arguments?.getLong("interfaceId") ?: -1L
                        com.lxmf.messenger.ui.screens.rnode.RNodeWizardScreen(
                            editingInterfaceId = if (interfaceId >= 0) interfaceId else null,
                            onNavigateBack = { navController.popBackStack() },
                            onComplete = {
                                navController.navigate("interface_management") {
                                    popUpTo("interface_management") { inclusive = true }
                                }
                            },
                        )
                    }

                    composable("notification_settings") {
                        NotificationSettingsScreen(
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable("theme_management") {
                        ThemeManagementScreen(
                            onBackClick = { navController.popBackStack() },
                            onCreateTheme = {
                                navController.navigate("theme_editor")
                            },
                            onEditTheme = { themeId ->
                                navController.navigate("theme_editor/$themeId")
                            },
                            onApplyTheme = { themeId ->
                                settingsViewModel.applyCustomTheme(themeId)
                            },
                        )
                    }

                    composable("theme_editor") {
                        ThemeEditorScreen(
                            themeId = null,
                            onBackClick = { navController.popBackStack() },
                            onSave = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = "theme_editor/{themeId}",
                        arguments =
                            listOf(
                                navArgument("themeId") { type = NavType.LongType },
                            ),
                    ) { backStackEntry ->
                        val themeId = backStackEntry.arguments?.getLong("themeId")

                        ThemeEditorScreen(
                            themeId = themeId,
                            onBackClick = { navController.popBackStack() },
                            onSave = { navController.popBackStack() },
                        )
                    }

                    composable("ble_connection_status") {
                        BleConnectionStatusScreen(
                            onBackClick = { navController.popBackStack() },
                        )
                    }

                    composable("identity_manager") {
                        IdentityManagerScreen(
                            onNavigateBack = { navController.popBackStack() },
                        )
                    }

                    composable("migration") {
                        MigrationScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onImportComplete = {
                                // Service restart is handled by MigrationViewModel,
                                // just navigate to chats after import completes
                                navController.navigate("chats") {
                                    popUpTo(0) { inclusive = true }
                                }
                            },
                        )
                    }

                    composable("my_identity") {
                        MyIdentityScreen(
                            onNavigateBack = { navController.popBackStack() },
                            settingsViewModel = settingsViewModel,
                            onNavigateToIdentityManager = {
                                navController.navigate("identity_manager")
                            },
                        )
                    }

                    composable("network_status") {
                        IdentityScreen(
                            onBackClick = { navController.popBackStack() },
                            settingsViewModel = settingsViewModel,
                            onNavigateToBleStatus = {
                                navController.navigate("ble_connection_status")
                            },
                        )
                    }

                    composable("qr_scanner") {
                        val contactsViewModel: ContactsViewModel = hiltViewModel()
                        QrScannerScreen(
                            onBackClick = { navController.popBackStack() },
                            onQrScanned = { qrData ->
                                // Contact addition now handled by confirmation dialog
                            },
                            onNavigateToConversation = { destinationHash ->
                                // Contact already exists - navigate to conversation
                                val contacts = contactsViewModel.contacts.value
                                val contact = contacts.find { it.destinationHash == destinationHash }
                                val peerName = contact?.displayName ?: destinationHash.take(16)
                                val encodedHash = Uri.encode(destinationHash)
                                val encodedName = Uri.encode(peerName)
                                navController.navigate("messaging/$encodedHash/$encodedName")
                            },
                            contactsViewModel = contactsViewModel,
                        )
                    }

                    composable(
                        route = "messaging/{destinationHash}/{peerName}",
                        arguments =
                            listOf(
                                navArgument("destinationHash") { type = NavType.StringType },
                                navArgument("peerName") { type = NavType.StringType },
                            ),
                    ) { backStackEntry ->
                        val destinationHash = backStackEntry.arguments?.getString("destinationHash").orEmpty()
                        val peerName = backStackEntry.arguments?.getString("peerName").orEmpty()

                        MessagingScreen(
                            destinationHash = destinationHash,
                            peerName = peerName,
                            onBackClick = { navController.popBackStack() },
                            onPeerClick = {
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                            onViewMessageDetails = { messageId ->
                                val encodedId = Uri.encode(messageId)
                                navController.navigate("message_detail/$encodedId")
                            },
                            onVoiceCall = {
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("voice_call/$encodedHash")
                            },
                        )
                    }

                    composable(
                        route = "message_detail/{messageId}",
                        arguments =
                            listOf(
                                navArgument("messageId") { type = NavType.StringType },
                            ),
                    ) { backStackEntry ->
                        val messageId = backStackEntry.arguments?.getString("messageId").orEmpty()

                        MessageDetailScreen(
                            messageId = messageId,
                            onBackClick = { navController.popBackStack() },
                        )
                    }

                    composable(
                        route = "announce_detail/{destinationHash}",
                        arguments =
                            listOf(
                                navArgument("destinationHash") { type = NavType.StringType },
                            ),
                    ) { backStackEntry ->
                        val destinationHash = backStackEntry.arguments?.getString("destinationHash").orEmpty()

                        AnnounceDetailScreen(
                            destinationHash = destinationHash,
                            onBackClick = { navController.popBackStack() },
                            onStartChat = { destHash, peerName ->
                                // Navigate back to chats tab
                                selectedTab = 0
                                navController.navigate(Screen.Chats.route) {
                                    popUpTo(navController.graph.startDestinationId) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                                // Then navigate to the messaging screen
                                val encodedHash = Uri.encode(destHash)
                                val encodedName = Uri.encode(peerName)
                                navController.navigate("messaging/$encodedHash/$encodedName")
                            },
                        )
                    }

                    // Offline Maps management screen
                    composable("offline_maps") {
                        OfflineMapsScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToDownload = { navController.navigate("offline_map_download") },
                        )
                    }

                    // Offline Map download wizard
                    composable("offline_map_download") {
                        OfflineMapDownloadScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onDownloadComplete = { navController.popBackStack() },
                        )
                    }

                    // Voice Call Screen (outgoing/active call)
                    composable(
                        route = "voice_call/{destinationHash}?autoAnswer={autoAnswer}",
                        arguments =
                            listOf(
                                navArgument("destinationHash") { type = NavType.StringType },
                                navArgument("autoAnswer") {
                                    type = NavType.BoolType
                                    defaultValue = false
                                },
                            ),
                    ) { backStackEntry ->
                        val destinationHash = backStackEntry.arguments?.getString("destinationHash").orEmpty()
                        val autoAnswer = backStackEntry.arguments?.getBoolean("autoAnswer") ?: false

                        VoiceCallScreen(
                            destinationHash = destinationHash,
                            onEndCall = { navController.popBackStack() },
                            autoAnswer = autoAnswer,
                        )
                    }

                    // Incoming Call Screen
                    composable(
                        route = "incoming_call/{identityHash}",
                        arguments =
                            listOf(
                                navArgument("identityHash") { type = NavType.StringType },
                            ),
                    ) { backStackEntry ->
                        val identityHash = backStackEntry.arguments?.getString("identityHash").orEmpty()

                        IncomingCallScreen(
                            identityHash = identityHash,
                            onCallAnswered = {
                                // Navigate to voice call screen when answered
                                val encodedHash = Uri.encode(identityHash)
                                navController.navigate("voice_call/$encodedHash") {
                                    popUpTo("incoming_call/$identityHash") { inclusive = true }
                                }
                            },
                            onCallDeclined = { navController.popBackStack() },
                        )
                    }
                }

                // Bluetooth permission bottom sheet
                if (showPermissionBottomSheet) {
                    val currentStatus =
                        if (BlePermissionManager.hasAllPermissions(context)) {
                            BlePermissionManager.PermissionStatus.Granted
                        } else {
                            BlePermissionManager.checkPermissionStatus(context)
                        }

                    val useAppSettings =
                        hasRequestedBluetoothOnce &&
                            currentStatus is BlePermissionManager.PermissionStatus.Denied

                    BlePermissionBottomSheet(
                        onDismiss = { showPermissionBottomSheet = false },
                        onRequestPermissions = {
                            showPermissionBottomSheet = false
                            if (useAppSettings) {
                                // Open app settings so the user can manually enable permissions.
                                val intent =
                                    Intent(
                                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                        Uri.fromParts("package", context.packageName, null),
                                    ).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                context.startActivity(intent)
                            } else {
                                val permissions = BlePermissionManager.getRequiredPermissions()
                                permissionLauncher.launch(permissions.toTypedArray())
                            }
                        },
                        sheetState = sheetState,
                        primaryActionLabel = if (useAppSettings) "Open Settings" else "Grant Permissions",
                    )
                }
            }
        }
    }
}
