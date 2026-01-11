package com.lxmf.messenger.ui.screens.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.screens.onboarding.pages.CompletePage
import com.lxmf.messenger.ui.screens.onboarding.pages.ConnectivityPage
import com.lxmf.messenger.ui.screens.onboarding.pages.IdentityPage
import com.lxmf.messenger.ui.screens.onboarding.pages.PermissionsPage
import com.lxmf.messenger.ui.screens.onboarding.pages.WelcomePage
import com.lxmf.messenger.util.BatteryOptimizationManager
import com.lxmf.messenger.viewmodel.DebugViewModel
import com.lxmf.messenger.viewmodel.OnboardingViewModel
import kotlinx.coroutines.launch

/**
 * Main onboarding screen with horizontal pager for multi-step setup.
 * Guides users through identity setup, connectivity options, and permissions.
 *
 * @param onOnboardingComplete Callback when onboarding finishes. Parameter indicates whether to
 *                             navigate to RNode wizard (true if LoRa Radio was selected).
 */
@Composable
fun OnboardingPagerScreen(
    onOnboardingComplete: (navigateToRNodeWizard: Boolean) -> Unit,
    onImportData: () -> Unit,
    viewModel: OnboardingViewModel = hiltViewModel(),
    debugViewModel: DebugViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pagerState = rememberPagerState(pageCount = { ONBOARDING_PAGE_COUNT })

    // Identity data for QR code dialog
    val identityHash by debugViewModel.identityHash.collectAsState()
    val destinationHash by debugViewModel.destinationHash.collectAsState()
    val qrCodeData by debugViewModel.qrCodeData.collectAsState()

    // Notification permission launcher (Android 13+)
    val notificationPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) { granted ->
            viewModel.onNotificationPermissionResult(granted)
        }

    // BLE permissions launcher
    val blePermissionsLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions(),
        ) { permissions ->
            val allGranted = permissions.values.all { it }
            val anyDenied = permissions.values.any { !it }
            viewModel.onBlePermissionsResult(allGranted, anyDenied)
            // Toggle BLE on only if all permissions were granted
            if (allGranted) {
                viewModel.toggleInterface(OnboardingInterfaceType.BLE)
            }
        }

    // Battery optimization launcher
    val batteryOptimizationLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult(),
        ) {
            viewModel.checkBatteryOptimizationStatus(context)
        }

    // Check initial battery status
    LaunchedEffect(Unit) {
        viewModel.checkBatteryOptimizationStatus(context)
    }

    // Sync pager state with viewmodel state
    LaunchedEffect(state.currentPage) {
        if (pagerState.currentPage != state.currentPage) {
            pagerState.animateScrollToPage(state.currentPage)
        }
    }

    // Update viewmodel when user swipes
    LaunchedEffect(pagerState.currentPage) {
        viewModel.setCurrentPage(pagerState.currentPage)
        // Refresh identity data when entering the CompletePage (page 4)
        if (pagerState.currentPage == 4) {
            debugViewModel.refreshIdentityData()
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        if (state.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .windowInsetsPadding(WindowInsets.statusBars),
            ) {
                // Top bar with Skip button
                TopBar(
                    currentPage = pagerState.currentPage,
                    onSkip = {
                        viewModel.skipOnboarding { onOnboardingComplete(false) }
                    },
                    enabled = !state.isSaving,
                )

                // Main pager content
                // Note: Navigation controlled by buttons, not user swipes
                HorizontalPager(
                    state = pagerState,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    userScrollEnabled = false,
                ) { page ->
                    when (page) {
                        0 ->
                            WelcomePage(
                                onGetStarted = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(1)
                                    }
                                },
                                onRestoreFromBackup = onImportData,
                            )
                        1 ->
                            IdentityPage(
                                displayName = state.displayName,
                                onDisplayNameChange = viewModel::updateDisplayName,
                                onBack = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(0)
                                    }
                                },
                                onContinue = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(2)
                                    }
                                },
                            )
                        2 ->
                            ConnectivityPage(
                                selectedInterfaces = state.selectedInterfaces,
                                onInterfaceToggle = { interfaceType ->
                                    if (interfaceType == OnboardingInterfaceType.BLE &&
                                        !state.selectedInterfaces.contains(OnboardingInterfaceType.BLE)
                                    ) {
                                        // Request BLE permissions first - toggle happens in callback
                                        val permissions = getBlePermissions()
                                        blePermissionsLauncher.launch(permissions)
                                    } else {
                                        // For other interfaces or disabling BLE, toggle immediately
                                        viewModel.toggleInterface(interfaceType)
                                    }
                                },
                                blePermissionsGranted = state.blePermissionsGranted,
                                blePermissionsDenied = state.blePermissionsDenied,
                                onBack = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(1)
                                    }
                                },
                                onContinue = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(3)
                                    }
                                },
                            )
                        3 ->
                            PermissionsPage(
                                notificationsGranted = state.notificationsGranted,
                                batteryOptimizationExempt = state.batteryOptimizationExempt,
                                onEnableNotifications = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notificationPermissionLauncher.launch(
                                            Manifest.permission.POST_NOTIFICATIONS,
                                        )
                                    } else {
                                        // Pre-Android 13, notifications are enabled by default
                                        viewModel.onNotificationPermissionResult(true)
                                    }
                                },
                                onEnableBatteryOptimization = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        val intent =
                                            BatteryOptimizationManager
                                                .createRequestExemptionIntent(context)
                                        batteryOptimizationLauncher.launch(intent)
                                    }
                                },
                                onBack = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(2)
                                    }
                                },
                                onContinue = {
                                    scope.launch {
                                        pagerState.animateScrollToPage(4)
                                    }
                                },
                            )
                        4 ->
                            CompletePage(
                                displayName = state.displayName,
                                selectedInterfaces = state.selectedInterfaces,
                                notificationsEnabled = state.notificationsGranted,
                                batteryOptimizationExempt = state.batteryOptimizationExempt,
                                isSaving = state.isSaving,
                                onStartMessaging = {
                                    val hasLoRaSelected = state.selectedInterfaces.contains(OnboardingInterfaceType.RNODE)
                                    viewModel.completeOnboarding {
                                        onOnboardingComplete(hasLoRaSelected)
                                    }
                                },
                                identityHash = identityHash,
                                destinationHash = destinationHash,
                                qrCodeData = qrCodeData,
                            )
                    }
                }

                // Page indicators
                PageIndicator(
                    pageCount = ONBOARDING_PAGE_COUNT,
                    currentPage = pagerState.currentPage,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .padding(bottom = 24.dp)
                            .windowInsetsPadding(WindowInsets.navigationBars),
                )
            }
        }
    }
}

@Composable
private fun TopBar(
    currentPage: Int,
    onSkip: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
    ) {
        // Skip button (not shown on last page)
        if (currentPage < ONBOARDING_PAGE_COUNT - 1) {
            TextButton(
                onClick = onSkip,
                enabled = enabled,
                modifier = Modifier.align(Alignment.CenterEnd),
            ) {
                Text(
                    text = "Skip",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PageIndicator(
    pageCount: Int,
    currentPage: Int,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(pageCount) { index ->
            val isSelected = index == currentPage
            val color by animateColorAsState(
                targetValue =
                    if (isSelected) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                    },
                label = "pageIndicatorColor",
            )

            Box(
                modifier =
                    Modifier
                        .padding(horizontal = 4.dp)
                        .size(if (isSelected) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(color),
            )
        }
    }
}

/**
 * Get the required BLE permissions based on Android version.
 */
private fun getBlePermissions(): Array<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12+
        arrayOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
        )
    } else {
        // Android 11 and below
        arrayOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
    }
}
