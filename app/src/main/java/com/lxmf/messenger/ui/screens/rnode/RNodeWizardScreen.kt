package com.lxmf.messenger.ui.screens.rnode

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.ui.components.WizardBottomBar
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import com.lxmf.messenger.viewmodel.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RNodeWizardScreen(
    editingInterfaceId: Long? = null,
    preselectedConnectionType: String? = null,
    preselectedUsbDeviceId: Int? = null,
    preselectedUsbVendorId: Int? = null,
    preselectedUsbProductId: Int? = null,
    preselectedUsbDeviceName: String? = null,
    preselectedLoraFrequency: Long? = null,
    preselectedLoraBandwidth: Int? = null,
    preselectedLoraSf: Int? = null,
    preselectedLoraCr: Int? = null,
    onNavigateBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: RNodeWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Handle system back button - go to previous step or exit wizard
    BackHandler {
        if (state.currentStep == WizardStep.DEVICE_DISCOVERY) {
            onNavigateBack()
        } else {
            viewModel.goToPreviousStep()
        }
    }

    // Load existing config if editing
    LaunchedEffect(editingInterfaceId) {
        if (editingInterfaceId != null && editingInterfaceId >= 0) {
            viewModel.loadExistingConfig(editingInterfaceId)
        }
    }

    // Apply USB pre-selection if provided
    LaunchedEffect(preselectedConnectionType, preselectedUsbDeviceId) {
        @Suppress("ComplexCondition") // All 5 parameters must be present for USB pre-selection
        if (preselectedConnectionType == "usb" &&
            preselectedUsbDeviceId != null &&
            preselectedUsbVendorId != null &&
            preselectedUsbProductId != null &&
            preselectedUsbDeviceName != null
        ) {
            viewModel.preselectUsbDevice(
                deviceId = preselectedUsbDeviceId,
                vendorId = preselectedUsbVendorId,
                productId = preselectedUsbProductId,
                deviceName = preselectedUsbDeviceName,
            )
        }
    }

    // Apply LoRa params pre-selection if provided (from discovered interfaces)
    LaunchedEffect(preselectedLoraFrequency, preselectedLoraBandwidth, preselectedLoraSf, preselectedLoraCr) {
        val hasLoraParams = listOfNotNull(
            preselectedLoraFrequency,
            preselectedLoraBandwidth,
            preselectedLoraSf,
            preselectedLoraCr,
        ).isNotEmpty()
        if (hasLoraParams) {
            viewModel.setInitialRadioParams(
                frequency = preselectedLoraFrequency,
                bandwidth = preselectedLoraBandwidth,
                spreadingFactor = preselectedLoraSf,
                codingRate = preselectedLoraCr,
            )
        }
    }

    // Handle save success
    LaunchedEffect(state.saveSuccess) {
        if (state.saveSuccess) {
            onComplete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (state.currentStep) {
                            WizardStep.DEVICE_DISCOVERY ->
                                if (state.isEditMode) "Change RNode Device" else "Select RNode Device"
                            WizardStep.REGION_SELECTION -> "Choose Region"
                            WizardStep.MODEM_PRESET -> "Select Modem Preset"
                            WizardStep.FREQUENCY_SLOT -> "Select Frequency Slot"
                            WizardStep.REVIEW_CONFIGURE -> "Review Settings"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.currentStep == WizardStep.DEVICE_DISCOVERY) {
                                onNavigateBack()
                            } else {
                                viewModel.goToPreviousStep()
                            }
                        },
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
            )
        },
        bottomBar = {
            WizardBottomBar(
                currentStepIndex = state.currentStep.ordinal,
                totalSteps = WizardStep.entries.size,
                buttonText =
                    when (state.currentStep) {
                        WizardStep.REVIEW_CONFIGURE -> if (state.isEditMode) "Update" else "Save"
                        else -> "Next"
                    },
                canProceed = viewModel.canProceed(),
                isSaving = state.isSaving,
                onButtonClick = {
                    if (state.currentStep == WizardStep.REVIEW_CONFIGURE) {
                        viewModel.saveConfiguration()
                    } else {
                        viewModel.goToNextStep()
                    }
                },
                modifier = Modifier.navigationBarsPadding(),
            )
        },
    ) { paddingValues ->
        AnimatedContent(
            targetState = state.currentStep,
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            transitionSpec = {
                if (targetState.ordinal > initialState.ordinal) {
                    slideInHorizontally { it } togetherWith slideOutHorizontally { -it }
                } else {
                    slideInHorizontally { -it } togetherWith slideOutHorizontally { it }
                }
            },
            label = "wizard_step",
        ) { step ->
            when (step) {
                WizardStep.DEVICE_DISCOVERY -> DeviceDiscoveryStep(viewModel)
                WizardStep.REGION_SELECTION -> RegionSelectionStep(viewModel)
                WizardStep.MODEM_PRESET -> ModemPresetStep(viewModel)
                WizardStep.FREQUENCY_SLOT -> FrequencySlotStep(viewModel)
                WizardStep.REVIEW_CONFIGURE -> ReviewConfigStep(viewModel)
            }
        }
    }

    // Error dialog
    state.saveError?.let { error ->
        AlertDialog(
            onDismissRequest = { viewModel.clearSaveError() },
            title = { Text("Error") },
            text = { Text(error) },
            confirmButton = {
                TextButton(onClick = { viewModel.clearSaveError() }) {
                    Text("OK")
                }
            },
        )
    }
}
