package com.lxmf.messenger.ui.screens.tcpclient

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
                currentStepIndex = state.currentStep.ordinal,
                totalSteps = TcpClientWizardStep.entries.size,
                buttonText =
                    when (state.currentStep) {
                        TcpClientWizardStep.SERVER_SELECTION -> "Next"
                        TcpClientWizardStep.REVIEW_CONFIGURE -> "Save"
                    },
                canProceed = viewModel.canProceed(),
                isSaving = state.isSaving,
                onButtonClick = {
                    if (state.currentStep == TcpClientWizardStep.REVIEW_CONFIGURE) {
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
