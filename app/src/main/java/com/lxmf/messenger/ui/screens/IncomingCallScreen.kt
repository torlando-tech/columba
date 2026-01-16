package com.lxmf.messenger.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.FilledIconButton
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
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.lxmf.messenger.reticulum.call.bridge.CallState
import com.lxmf.messenger.viewmodel.CallViewModel

/**
 * Incoming call screen with answer/decline options.
 *
 * Material 3 full-screen UI with:
 * - Pulsing avatar animation
 * - Caller name/identity
 * - Answer (green) and Decline (red) buttons
 */
@Composable
fun IncomingCallScreen(
    identityHash: String,
    onCallAnswered: () -> Unit,
    onCallDeclined: () -> Unit,
    viewModel: CallViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val callState by viewModel.callState.collectAsStateWithLifecycle()
    val peerName by viewModel.peerName.collectAsStateWithLifecycle()

    // Permission state
    var hasAudioPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED,
        )
    }

    // Permission launcher - answers call after permission granted
    val permissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestPermission(),
        ) { isGranted ->
            hasAudioPermission = isGranted
            if (isGranted) {
                android.util.Log.i("IncomingCallScreen", "📞 Permission granted, answering call...")
                viewModel.answerCall()
            } else {
                android.util.Log.w("IncomingCallScreen", "📞 Permission denied, cannot answer call")
            }
        }

    // Function to handle answer with permission check
    val handleAnswer: () -> Unit = {
        if (hasAudioPermission) {
            viewModel.answerCall()
        } else {
            android.util.Log.i("IncomingCallScreen", "📞 Requesting microphone permission to answer...")
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Handle call state changes
    LaunchedEffect(callState) {
        when (callState) {
            is CallState.Active -> {
                // Call was answered, navigate to active call screen
                onCallAnswered()
            }
            is CallState.Ended,
            is CallState.Rejected,
            is CallState.Idle,
            -> {
                // Call ended or was declined
                kotlinx.coroutines.delay(500)
                onCallDeclined()
            }
            else -> {}
        }
    }

    // Pulsing animation for avatar
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "scale",
    )

    val ringColor by infiniteTransition.animateColor(
        initialValue = MaterialTheme.colorScheme.primaryContainer,
        targetValue = MaterialTheme.colorScheme.primary.copy(alpha = 0.3f),
        animationSpec =
            infiniteRepeatable(
                animation = tween(durationMillis = 800),
                repeatMode = RepeatMode.Reverse,
            ),
        label = "ringColor",
    )

    // Full-screen incoming call UI
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
            // Top: Caller info with pulsing avatar
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(top = 80.dp),
            ) {
                // Pulsing ring behind avatar
                Box(
                    contentAlignment = Alignment.Center,
                ) {
                    // Outer pulsing ring
                    Box(
                        modifier =
                            Modifier
                                .size(160.dp)
                                .scale(scale)
                                .clip(CircleShape)
                                .background(ringColor),
                    )

                    // Avatar
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
                }

                Spacer(modifier = Modifier.height(32.dp))

                // Caller name
                Text(
                    text = peerName ?: formatIncomingHash(identityHash),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.height(12.dp))

                // "Incoming Voice Call" label
                Text(
                    text = "Incoming Voice Call",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Bottom: Answer/Decline buttons
            Row(
                horizontalArrangement = Arrangement.Center,
                modifier = Modifier.padding(bottom = 80.dp),
            ) {
                // Decline button (red)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FilledIconButton(
                        onClick = { viewModel.declineCall() },
                        modifier = Modifier.size(72.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.error,
                            ),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = Icons.Default.CallEnd,
                            contentDescription = "Decline call",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onError,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Decline",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Spacer(modifier = Modifier.width(80.dp))

                // Answer button (green)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FilledIconButton(
                        onClick = handleAnswer,
                        modifier = Modifier.size(72.dp),
                        colors =
                            IconButtonDefaults.filledIconButtonColors(
                                // Green answer button
                                containerColor = Color(0xFF4CAF50),
                            ),
                        shape = CircleShape,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Call,
                            contentDescription = "Answer call",
                            modifier = Modifier.size(32.dp),
                            tint = Color.White,
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Answer",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private fun formatIncomingHash(hash: String): String {
    return if (hash.length > 12) {
        "${hash.take(6)}...${hash.takeLast(6)}"
    } else {
        hash
    }
}
