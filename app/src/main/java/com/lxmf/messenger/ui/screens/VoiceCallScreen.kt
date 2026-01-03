package com.lxmf.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxmf.messenger.reticulum.call.bridge.CallState
import com.lxmf.messenger.viewmodel.CallViewModel

/**
 * Voice call screen for active/outgoing calls.
 *
 * Material 3 full-screen UI with:
 * - Avatar and peer name
 * - Call status/duration
 * - Mute and speaker controls
 * - End call button
 */
@Composable
fun VoiceCallScreen(
    destinationHash: String,
    onEndCall: () -> Unit,
    autoAnswer: Boolean = false,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val isMuted by viewModel.isMuted.collectAsStateWithLifecycle()
    val isSpeakerOn by viewModel.isSpeakerOn.collectAsStateWithLifecycle()
    val callDuration by viewModel.callDuration.collectAsStateWithLifecycle()
    val peerName by viewModel.peerName.collectAsStateWithLifecycle()

    // Permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }
    var permissionRequested by remember { mutableStateOf(false) }

    // Permission launcher
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasAudioPermission = isGranted
            if (isGranted && callState is CallState.Idle) {
                android.util.Log.w("VoiceCallScreen", "📞 Permission granted, initiating call...")
                viewModel.initiateCall(destinationHash)
            } else if (!isGranted) {
                android.util.Log.w("VoiceCallScreen", "📞 Permission denied, cannot make call")
            }
        }

    // Request permission and initiate/answer call when screen opens
    LaunchedEffect(destinationHash, autoAnswer) {
        android.util.Log.w("VoiceCallScreen", "📞 VoiceCallScreen opened! destHash=${destinationHash.take(16)}..., autoAnswer=$autoAnswer")
        android.util.Log.w("VoiceCallScreen", "📞 Current callState=$callState, hasAudioPermission=$hasAudioPermission")

        // Auto-answer incoming call from notification
        if (autoAnswer && (callState is CallState.Incoming || callState is CallState.Ringing)) {
            android.util.Log.w("VoiceCallScreen", "📞 Auto-answering incoming call...")
            viewModel.answerCall()
            return@LaunchedEffect
        }

        if (callState is CallState.Idle) {
            if (hasAudioPermission) {
                android.util.Log.w("VoiceCallScreen", "📞 Permission already granted, calling initiateCall()...")
                viewModel.initiateCall(destinationHash)
            } else if (!permissionRequested) {
                android.util.Log.w("VoiceCallScreen", "📞 Requesting microphone permission...")
                permissionRequested = true
                permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        } else {
            android.util.Log.w("VoiceCallScreen", "📞 CallState is NOT Idle, NOT calling initiateCall()")
        }
    }

    // Handle call ended - navigate back
    LaunchedEffect(callState) {
        android.util.Log.w("VoiceCallScreen", "📞 callState changed to: $callState")
        when (callState) {
            is CallState.Ended,
            is CallState.Rejected,
            is CallState.Busy,
            -> {
                android.util.Log.w("VoiceCallScreen", "📞 Call ended/rejected/busy, navigating back in 1.5s...")
                // Small delay to show the end state
                kotlinx.coroutines.delay(1500)
                android.util.Log.w("VoiceCallScreen", "📞 Calling onEndCall()...")
                onEndCall()
            }
            else -> {}
        }
    }

    // Full-screen call UI
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // Top: Call Status
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 48.dp),
            ) {
                // Avatar placeholder
                Box(
                    modifier =
                        Modifier
                            .size(120.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Peer name
                Text(
                    text = peerName ?: formatHash(destinationHash),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Call status or duration
                Text(
                    text =
                        when (callState) {
                            is CallState.Connecting -> "Connecting..."
                            is CallState.Ringing -> "Ringing..."
                            is CallState.Active -> viewModel.formatDuration(callDuration)
                            is CallState.Busy -> "Line Busy"
                            is CallState.Rejected -> "Call Rejected"
                            is CallState.Ended -> "Call Ended"
                            else -> "Calling..."
                        },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bottom: Control buttons
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(bottom = 48.dp),
            ) {
                // Secondary controls row (mute + speaker)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(32.dp),
                    modifier = Modifier.padding(bottom = 32.dp),
                ) {
                    // Mute button
                    CallControlButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        label = if (isMuted) "Unmute" else "Mute",
                        isActive = isMuted,
                        onClick = { viewModel.toggleMute() },
                        enabled = callState is CallState.Active,
                    )

                    // Speaker button
                    CallControlButton(
                        icon = if (isSpeakerOn) Icons.Default.VolumeUp else Icons.Default.VolumeDown,
                        label = if (isSpeakerOn) "Earpiece" else "Speaker",
                        isActive = isSpeakerOn,
                        onClick = { viewModel.toggleSpeaker() },
                        enabled = callState is CallState.Active,
                    )
                }

                // End call button
                FilledIconButton(
                    onClick = { viewModel.endCall() },
                    modifier = Modifier.size(72.dp),
                    colors =
                        IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.error,
                        ),
                    shape = CircleShape,
                ) {
                    Icon(
                        imageVector = Icons.Default.CallEnd,
                        contentDescription = "End call",
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.onError,
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "End Call",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CallControlButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    isActive: Boolean,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    val backgroundColor by animateColorAsState(
        targetValue =
            if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        animationSpec = tween(durationMillis = 200),
        label = "button_bg",
    )

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            enabled = enabled,
            modifier = Modifier.size(56.dp),
            colors =
                IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = backgroundColor,
                    disabledContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                ),
            shape = CircleShape,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                modifier = Modifier.size(24.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color =
                if (enabled) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                },
        )
    }
}

private fun formatHash(hash: String): String {
    return if (hash.length > 12) {
        "${hash.take(6)}...${hash.takeLast(6)}"
    } else {
        hash
    }
}
