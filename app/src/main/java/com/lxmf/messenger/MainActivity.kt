package com.lxmf.messenger

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.lxmf.messenger.notifications.NotificationHelper
import com.lxmf.messenger.reticulum.ble.util.BlePermissionManager
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
import com.lxmf.messenger.ui.screens.MessagingScreen
import com.lxmf.messenger.ui.screens.MyIdentityScreen
import com.lxmf.messenger.ui.screens.NotificationSettingsScreen
import com.lxmf.messenger.ui.screens.QrScannerScreen
import com.lxmf.messenger.ui.screens.SettingsScreen
import com.lxmf.messenger.ui.screens.ThemeEditorScreen
import com.lxmf.messenger.ui.screens.ThemeManagementScreen
import com.lxmf.messenger.ui.screens.WelcomeScreen
import com.lxmf.messenger.ui.theme.ColumbaTheme
import com.lxmf.messenger.viewmodel.ContactsViewModel
import com.lxmf.messenger.viewmodel.OnboardingViewModel
import dagger.hilt.android.AndroidEntryPoint

/**
 * Main activity for the Columba LXMF Messenger application.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        private const val TAG = "MainActivity"
    }

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

            ColumbaNavigation(pendingNavigation = pendingNavigation)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        processIntent(intent)
    }

    private fun processIntent(intent: Intent?) {
        if (intent == null) return

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
}

sealed class Screen(val route: String, val title: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    object Welcome : Screen("welcome", "Welcome", Icons.Default.Sensors)

    object Chats : Screen("chats", "Chats", Icons.Default.Chat)

    object Announces : Screen("announce_stream", "Announces", Icons.Default.Sensors)

    object Contacts : Screen("contacts", "Contacts", Icons.Default.People)

    object Identity : Screen("identity", "Network Status", Icons.Default.Info)

    object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColumbaNavigation(pendingNavigation: MutableState<PendingNavigation?>) {
    val context = LocalContext.current
    val navController = rememberNavController()
    var selectedTab by remember { mutableIntStateOf(0) }

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

    // Access NotificationSettingsViewModel to check permission request status
    val notificationSettingsViewModel: com.lxmf.messenger.viewmodel.NotificationSettingsViewModel =
        androidx.hilt.navigation.compose.hiltViewModel()

    // Collect notification settings state
    val notificationState by notificationSettingsViewModel.state.collectAsState()

    // Track whether we've already shown the permission request dialog in this session
    var hasShownPermissionRequest by remember { mutableStateOf(false) }

    // Notification permission launcher for first-launch request
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            if (isGranted) {
                Log.d("ColumbaNavigation", "Notification permission granted")
            } else {
                Log.d("ColumbaNavigation", "Notification permission denied")
                // Disable notifications in settings since permission was denied
                notificationSettingsViewModel.toggleNotificationsEnabled(false)
            }
        }

    // Check if we should request notification permission on first launch
    LaunchedEffect(notificationState.isLoading) {
        // Only run this check once when the state is loaded
        if (!notificationState.isLoading &&
            !hasShownPermissionRequest &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        ) {
            // Check if we haven't requested before and don't have permission
            val hasPermission =
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS,
                ) == PermissionChecker.PERMISSION_GRANTED

            if (!notificationState.hasRequestedNotificationPermission && !hasPermission) {
                // This is first launch and permission not granted - request it
                hasShownPermissionRequest = true
                notificationSettingsViewModel.markNotificationPermissionRequested()
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                Log.d("ColumbaNavigation", "Requesting notification permission on first launch")
            }
        }
    }

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
                    selectedTab = 2 // Contacts tab
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
            }
            // Clear the pending navigation after handling
            pendingNavigation.value = null
        }
    }

    // Bluetooth permission state
    var showPermissionBottomSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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
            }
        }

    // Check permissions on first launch
    LaunchedEffect(Unit) {
        if (!BlePermissionManager.hasAllPermissions(context)) {
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
                Screen.Announces.route -> 1
                Screen.Contacts.route -> 2
                Screen.Settings.route -> 3
                else -> selectedTab // Keep current selection for nested screens
            }
    }

    // Check if we're on the messaging screen, announce detail screen, interface management screen, BLE connection status screen, theme screens, or welcome screen
    val isOnWelcomeScreen = currentRoute == Screen.Welcome.route
    val isOnMessagingScreen = currentRoute?.startsWith("messaging/") ?: false
    val isOnAnnounceDetailScreen = currentRoute?.startsWith("announce_detail/") ?: false
    val isOnInterfaceManagementScreen = currentRoute == "interface_management"
    val isOnBleConnectionStatusScreen = currentRoute == "ble_connection_status"
    val isOnThemeManagementScreen = currentRoute == "theme_management"
    val isOnThemeEditorScreen = currentRoute == "theme_editor" || currentRoute?.startsWith("theme_editor/") == true

    val screens =
        listOf(
            Screen.Chats,
            Screen.Announces,
            Screen.Contacts,
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
                    // Only show NavigationBar when NOT on messaging screen, announce detail screen, interface management screen, BLE connection status screen, theme screens, or welcome screen
                    if (!isOnWelcomeScreen && !isOnMessagingScreen && !isOnAnnounceDetailScreen && !isOnInterfaceManagementScreen && !isOnBleConnectionStatusScreen && !isOnThemeManagementScreen && !isOnThemeEditorScreen) {
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
                        WelcomeScreen(
                            onOnboardingComplete = {
                                navController.navigate(Screen.Chats.route) {
                                    popUpTo(Screen.Welcome.route) { inclusive = true }
                                }
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

                    composable(Screen.Announces.route) {
                        AnnounceStreamScreen(
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

                    composable(Screen.Identity.route) {
                        IdentityScreen(
                            onBackClick = { navController.popBackStack() },
                            onNavigateToBleStatus = {
                                navController.navigate("ble_connection_status")
                            },
                        )
                    }

                    composable(Screen.Settings.route) {
                        SettingsScreen(
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
                        )
                    }

                    composable("interface_management") {
                        InterfaceManagementScreen(
                            onNavigateBack = { navController.popBackStack() },
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

                    composable("my_identity") {
                        MyIdentityScreen(
                            onNavigateBack = { navController.popBackStack() },
                            onNavigateToIdentityManager = {
                                navController.navigate("identity_manager")
                            },
                        )
                    }

                    composable("network_status") {
                        IdentityScreen(
                            onBackClick = { navController.popBackStack() },
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
                        val destinationHash = backStackEntry.arguments?.getString("destinationHash") ?: ""
                        val peerName = backStackEntry.arguments?.getString("peerName") ?: ""

                        MessagingScreen(
                            destinationHash = destinationHash,
                            peerName = peerName,
                            onBackClick = { navController.popBackStack() },
                            onPeerClick = {
                                val encodedHash = Uri.encode(destinationHash)
                                navController.navigate("announce_detail/$encodedHash")
                            },
                        )
                    }

                    composable(
                        route = "announce_detail/{destinationHash}",
                        arguments =
                            listOf(
                                navArgument("destinationHash") { type = NavType.StringType },
                            ),
                    ) { backStackEntry ->
                        val destinationHash = backStackEntry.arguments?.getString("destinationHash") ?: ""

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
                }

                // Bluetooth permission bottom sheet
                if (showPermissionBottomSheet) {
                    BlePermissionBottomSheet(
                        onDismiss = { showPermissionBottomSheet = false },
                        onRequestPermissions = {
                            showPermissionBottomSheet = false
                            val permissions = BlePermissionManager.getRequiredPermissions()
                            permissionLauncher.launch(permissions.toTypedArray())
                        },
                        sheetState = sheetState,
                    )
                }
            }
        }
    }
}
