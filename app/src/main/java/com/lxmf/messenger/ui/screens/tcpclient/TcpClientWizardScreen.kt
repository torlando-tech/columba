package com.lxmf.messenger.ui.screens.tcpclient

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
import com.lxmf.messenger.viewmodel.TcpClientWizardStep
import com.lxmf.messenger.viewmodel.TcpClientWizardViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TcpClientWizardScreen(
    onNavigateBack: () -> Unit,
    onComplete: () -> Unit,
    viewModel: TcpClientWizardViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsState()

    // Handle system back button - go to previous step or exit wizard
    BackHandler {
        if (state.currentStep == TcpClientWizardStep.SERVER_SELECTION) {
            onNavigateBack()
        } else {
            viewModel.goToPreviousStep()
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
                            TcpClientWizardStep.SERVER_SELECTION -> "Choose Server"
                            TcpClientWizardStep.REVIEW_CONFIGURE -> "Review Settings"
                        },
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (state.currentStep == TcpClientWizardStep.SERVER_SELECTION) {
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
                onNext = {
                    if (state.currentStep == TcpClientWizardStep.REVIEW_CONFIGURE) {
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
            label = "tcp_wizard_step",
        ) { step ->
            when (step) {
                TcpClientWizardStep.SERVER_SELECTION -> ServerSelectionStep(viewModel)
                TcpClientWizardStep.REVIEW_CONFIGURE -> ReviewConfigureStep(viewModel)
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
    currentStep: TcpClientWizardStep,
    canProceed: Boolean,
    isSaving: Boolean,
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
            // Step indicator (2 dots)
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TcpClientWizardStep.entries.forEach { step ->
                    Box(
                        modifier =
                            Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(
                                    when {
                                        step == currentStep -> MaterialTheme.colorScheme.primary
                                        step.ordinal < currentStep.ordinal ->
                                            MaterialTheme.colorScheme.primaryContainer
                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                    },
                                ),
                    )
                }
            }

            // Next/Save button
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
                        TcpClientWizardStep.SERVER_SELECTION -> "Next"
                        TcpClientWizardStep.REVIEW_CONFIGURE -> "Save"
                    },
                )
            }
        }
    }
}
