package com.lxmf.messenger.ui.screens.rnode

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.viewmodel.RNodeWizardViewModel
import com.lxmf.messenger.viewmodel.WizardStep

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RNodeWizardScreen(
    editingInterfaceId: Long? = null,
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
                currentStep = state.currentStep,
                canProceed = viewModel.canProceed(),
                isSaving = state.isSaving,
                isEditMode = state.isEditMode,
                onNext = {
                    if (state.currentStep == WizardStep.REVIEW_CONFIGURE) {
                        viewModel.saveConfiguration()
                    } else {
                        viewModel.goToNextStep()
                    }
                },
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

@Composable
private fun WizardBottomBar(
    currentStep: WizardStep,
    canProceed: Boolean,
    isSaving: Boolean,
    isEditMode: Boolean,
    onNext: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Step indicator
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                WizardStep.entries.forEachIndexed { _, step ->
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        step == currentStep -> MaterialTheme.colorScheme.primary
                                        step.ordinal < currentStep.ordinal -> MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                    )
                }
            }

            // Next button
            Button(
                onClick = onNext,
                enabled = canProceed && !isSaving,
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    when (currentStep) {
                        WizardStep.DEVICE_DISCOVERY -> "Next"
                        WizardStep.REGION_SELECTION -> "Next"
                        WizardStep.MODEM_PRESET -> "Next"
                        WizardStep.FREQUENCY_SLOT -> "Next"
                        WizardStep.REVIEW_CONFIGURE -> if (isEditMode) "Update" else "Save"
                    },
                )
            }
        }
    }
}
