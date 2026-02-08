package com.lxmf.messenger.ui.screens.flasher

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.screens.flasher.components.FlasherStepIndicator
import com.lxmf.messenger.ui.screens.flasher.steps.DeviceDetectionStep
import com.lxmf.messenger.ui.screens.flasher.steps.DeviceSelectionStep
import com.lxmf.messenger.ui.screens.flasher.steps.FirmwareSelectionStep
import com.lxmf.messenger.ui.screens.flasher.steps.FlashCompleteStep
import com.lxmf.messenger.ui.screens.flasher.steps.FlashProgressStep
import com.lxmf.messenger.viewmodel.FlasherStep
import com.lxmf.messenger.viewmodel.FlasherViewModel

/**
 * Main RNode Flasher screen with multi-step wizard.
 *
 * Workflow:
 * 1. Device Selection - Select USB device to flash
 * 2. Device Detection - Identify board type and current firmware
 * 3. Firmware Selection - Choose firmware version and frequency band
 * 4. Flash Progress - Monitor flashing with cancel option
 * 5. Complete - Show result and next actions
 *
 * @param onNavigateBack Called when user exits the flasher
 * @param onComplete Called when user completes flashing
 * @param onNavigateToRNodeWizard Called to configure the RNode after flashing
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RNodeFlasherScreen(
    onNavigateBack: () -> Unit,
    onComplete: () -> Unit,
    onNavigateToRNodeWizard: () -> Unit = {},
    skipDetection: Boolean = false,
    preselectedUsbDeviceId: Int? = null,
    viewModel: FlasherViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()
    var showExitConfirmation by remember { mutableStateOf(false) }

    // Handle skip detection mode (for bootloader flashing)
    androidx.compose.runtime.LaunchedEffect(skipDetection) {
        if (skipDetection) {
            viewModel.enableSkipDetectionMode()
        }
    }

    // Auto-select USB device if provided
    androidx.compose.runtime.LaunchedEffect(preselectedUsbDeviceId, state.connectedDevices) {
        if (preselectedUsbDeviceId != null && state.selectedDevice == null) {
            val device = state.connectedDevices.find { it.deviceId == preselectedUsbDeviceId }
            if (device != null) {
                viewModel.selectDevice(device)
            }
        }
    }

    // Handle back navigation
    BackHandler {
        when (state.currentStep) {
            FlasherStep.DEVICE_SELECTION -> onNavigateBack()
            FlasherStep.FLASH_PROGRESS -> {
                // Can't back out during flashing - show cancel confirmation
                viewModel.showCancelConfirmation()
            }
            FlasherStep.COMPLETE -> {
                // Can exit from complete screen
                onNavigateBack()
            }
            else -> {
                if (viewModel.canGoBack()) {
                    viewModel.goToPreviousStep()
                } else {
                    onNavigateBack()
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.currentStep) {
                            FlasherStep.DEVICE_SELECTION -> "RNode Flasher"
                            FlasherStep.DEVICE_DETECTION -> "Detecting Device"
                            FlasherStep.FIRMWARE_SELECTION -> "Select Firmware"
                            FlasherStep.FLASH_PROGRESS -> "Flashing..."
                            FlasherStep.COMPLETE -> "Complete"
                        },
                    )
                },
                navigationIcon = {
                    when (state.currentStep) {
                        FlasherStep.FLASH_PROGRESS -> {
                            // Don't show back during flashing
                        }
                        FlasherStep.COMPLETE -> {
                            IconButton(onClick = onNavigateBack) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = "Close",
                                )
                            }
                        }
                        else -> {
                            IconButton(
                                onClick = {
                                    if (state.currentStep == FlasherStep.DEVICE_SELECTION) {
                                        onNavigateBack()
                                    } else if (viewModel.canGoBack()) {
                                        viewModel.goToPreviousStep()
                                    } else {
                                        onNavigateBack()
                                    }
                                },
                            ) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "Back",
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            // Only show bottom bar for steps that need Next/Continue
            if (state.currentStep != FlasherStep.FLASH_PROGRESS &&
                state.currentStep != FlasherStep.COMPLETE
            ) {
                FlasherBottomBar(
                    currentStep = state.currentStep,
                    canProceed =
                        when (state.currentStep) {
                            FlasherStep.DEVICE_SELECTION -> viewModel.canProceedFromDeviceSelection()
                            FlasherStep.DEVICE_DETECTION -> viewModel.canProceedFromDetection()
                            FlasherStep.FIRMWARE_SELECTION -> viewModel.canProceedFromFirmwareSelection()
                            else -> false
                        },
                    isLoading = state.isLoading || state.isDetecting || state.isDownloadingFirmware,
                    onNext = { viewModel.goToNextStep() },
                    modifier = Modifier.navigationBarsPadding(),
                )
            }
        },
    ) { paddingValues ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
        ) {
            // Step indicator (hide during flashing and complete)
            if (state.currentStep != FlasherStep.FLASH_PROGRESS &&
                state.currentStep != FlasherStep.COMPLETE
            ) {
                FlasherStepIndicator(
                    currentStep = state.currentStep,
                    modifier = Modifier.fillMaxWidth(),
                )
                HorizontalDivider()
            }

            // Animated step content
            AnimatedContent(
                targetState = state.currentStep,
                modifier =
                    Modifier
                        .fillMaxSize()
                        .weight(1f),
                transitionSpec = {
                    if (targetState.ordinal > initialState.ordinal) {
                        slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                    } else {
                        slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                    }
                },
                label = "flasher_step",
            ) { step ->
                when (step) {
                    FlasherStep.DEVICE_SELECTION ->
                        DeviceSelectionStep(
                            devices = state.connectedDevices,
                            selectedDevice = state.selectedDevice,
                            isRefreshing = state.isRefreshingDevices,
                            permissionPending = state.permissionPending,
                            permissionError = state.permissionError,
                            onDeviceSelected = { viewModel.selectDevice(it) },
                            onRefresh = { viewModel.refreshDevices() },
                        )

                    FlasherStep.DEVICE_DETECTION ->
                        DeviceDetectionStep(
                            isDetecting = state.isDetecting,
                            detectedInfo = state.detectedInfo,
                            detectionError = state.detectionError,
                            detectionMessage = state.flashMessage,
                            onManualSelection = { viewModel.enableManualBoardSelection() },
                        )

                    FlasherStep.FIRMWARE_SELECTION ->
                        FirmwareSelectionStep(
                            selectedBoard = state.selectedBoard,
                            selectedBand = state.selectedBand,
                            bandExplicitlySelected = state.bandExplicitlySelected,
                            availableFirmware = state.availableFirmware,
                            selectedFirmware = state.selectedFirmware,
                            availableVersions = state.availableVersions,
                            selectedVersion = state.selectedVersion,
                            isDownloading = state.isDownloadingFirmware,
                            downloadProgress = state.downloadProgress,
                            downloadError = state.downloadError,
                            useManualSelection = state.useManualBoardSelection,
                            onBoardSelected = { viewModel.selectBoard(it) },
                            onBandSelected = { viewModel.selectFrequencyBand(it) },
                            onFirmwareSelected = { viewModel.selectFirmware(it) },
                            onDownloadFirmware = { viewModel.downloadFirmware(it) },
                            onProvisionOnly = { viewModel.provisionOnly() },
                        )

                    FlasherStep.FLASH_PROGRESS ->
                        FlashProgressStep(
                            progress = state.flashProgress,
                            message = state.flashMessage,
                            showCancelConfirmation = state.showCancelConfirmation,
                            onShowCancelConfirmation = { viewModel.showCancelConfirmation() },
                            onHideCancelConfirmation = { viewModel.hideCancelConfirmation() },
                            onCancelFlash = { viewModel.cancelFlash() },
                            needsManualReset = state.needsManualReset,
                            isProvisioning = state.isProvisioning,
                            onDeviceReset = { viewModel.onDeviceReset() },
                        )

                    FlasherStep.COMPLETE ->
                        FlashCompleteStep(
                            result = state.flashResult,
                            onFlashAnother = { viewModel.flashAnother() },
                            onConfigureRNode = onNavigateToRNodeWizard,
                            onDone = onComplete,
                        )
                }
            }
        }
    }

    // Error dialog
    state.error?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearError() }) {
                    Text("OK")
                }
            },
        )
    }

    // Exit confirmation dialog
    if (showExitConfirmation) {
        AlertDialog(
            onDismissRequest = { showExitConfirmation = false },
            title = { Text("Exit Flasher?") },
            text = { Text("Are you sure you want to exit? Any unsaved progress will be lost.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showExitConfirmation = false
                        onNavigateBack()
                    },
                ) {
                    Text("Exit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showExitConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun FlasherBottomBar(
    currentStep: FlasherStep,
    canProceed: Boolean,
    isLoading: Boolean,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(16.dp),
    ) {
        HorizontalDivider(
            modifier = Modifier.padding(bottom = 16.dp),
            color = MaterialTheme.colorScheme.outlineVariant,
        )

        Button(
            onClick = onNext,
            enabled = canProceed && !isLoading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                when (currentStep) {
                    FlasherStep.DEVICE_SELECTION -> "Continue"
                    FlasherStep.DEVICE_DETECTION -> "Continue"
                    FlasherStep.FIRMWARE_SELECTION -> "Start Flashing"
                    else -> "Next"
                },
            )
        }
    }
}
