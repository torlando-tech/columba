package com.lxmf.messenger.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.HourglassEmpty
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.lxmf.messenger.viewmodel.MessageDetailViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MessageDetailScreen(
    messageId: String,
    onBackClick: () -> Unit,
    viewModel: MessageDetailViewModel = hiltViewModel(),
) {
    val message by viewModel.message.collectAsState()

    LaunchedEffect(messageId) {
        viewModel.loadMessage(messageId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Message Details",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                colors =
                    TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    ),
            )
        },
    ) { paddingValues ->
        val msg = message
        if (msg == null) {
            // Loading or not found state
            Box(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            Column(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Timestamp card
                MessageInfoCard(
                    icon = Icons.Default.AccessTime,
                    title = "Sent",
                    content = formatFullTimestamp(msg.timestamp),
                )

                // Status card
                val statusInfo = getStatusInfo(msg.status)
                MessageInfoCard(
                    icon = statusInfo.icon,
                    iconTint = statusInfo.color,
                    title = "Status",
                    content = statusInfo.text,
                    subtitle = statusInfo.subtitle,
                )

                // Delivery method card (only if available)
                msg.deliveryMethod?.let { method ->
                    val methodInfo = getDeliveryMethodInfo(method)
                    MessageInfoCard(
                        icon = methodInfo.icon,
                        title = "Delivery Method",
                        content = methodInfo.text,
                        subtitle = methodInfo.subtitle,
                    )
                }

                // Error card (only if failed and has error message)
                if (msg.status == "failed" && !msg.errorMessage.isNullOrBlank()) {
                    MessageInfoCard(
                        icon = Icons.Default.Error,
                        iconTint = MaterialTheme.colorScheme.error,
                        title = "Error Details",
                        content = msg.errorMessage,
                        contentColor = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
}

@Composable
private fun MessageInfoCard(
    icon: ImageVector,
    title: String,
    content: String,
    subtitle: String? = null,
    iconTint: Color = MaterialTheme.colorScheme.onPrimaryContainer,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Icon
            Box(
                modifier =
                    Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = iconTint,
                    modifier = Modifier.size(24.dp),
                )
            }

            // Content
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Medium,
                )

                Text(
                    text = content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = contentColor,
                    fontWeight = FontWeight.Medium,
                )

                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

private data class StatusInfo(
    val icon: ImageVector,
    val color: Color,
    val text: String,
    val subtitle: String,
)

@Composable
private fun getStatusInfo(status: String): StatusInfo {
    return when (status) {
        "delivered" ->
            StatusInfo(
                icon = Icons.Default.CheckCircle,
                // Green
                color = Color(0xFF4CAF50),
                text = "Delivered",
                subtitle = "Message was successfully delivered to recipient",
            )
        "failed" ->
            StatusInfo(
                icon = Icons.Default.Error,
                color = MaterialTheme.colorScheme.error,
                text = "Failed",
                subtitle = "Message delivery failed",
            )
        "pending" ->
            StatusInfo(
                icon = Icons.Default.HourglassEmpty,
                color = MaterialTheme.colorScheme.tertiary,
                text = "Pending",
                subtitle = "Waiting for delivery confirmation",
            )
        else ->
            StatusInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                color = MaterialTheme.colorScheme.primary,
                text = "Sent",
                subtitle = "Message has been sent",
            )
    }
}

private data class DeliveryMethodInfo(
    val icon: ImageVector,
    val text: String,
    val subtitle: String,
)

private fun getDeliveryMethodInfo(method: String): DeliveryMethodInfo {
    return when (method) {
        "opportunistic" ->
            DeliveryMethodInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                text = "Opportunistic",
                subtitle = "Single packet delivery for small messages, no link required",
            )
        "direct" ->
            DeliveryMethodInfo(
                icon = Icons.Default.Link,
                text = "Direct",
                subtitle = "Link-based delivery with retries, supports large messages",
            )
        "propagated" ->
            DeliveryMethodInfo(
                icon = Icons.Default.Hub,
                text = "Propagated",
                subtitle = "Delivered via relay node for offline recipients",
            )
        else ->
            DeliveryMethodInfo(
                icon = Icons.AutoMirrored.Filled.Send,
                text = method.replaceFirstChar { it.uppercase() },
                subtitle = "Unknown delivery method",
            )
    }
}

private fun formatFullTimestamp(timestamp: Long): String {
    val date = Date(timestamp)
    val format = SimpleDateFormat("MMM dd, yyyy 'at' HH:mm:ss", Locale.getDefault())
    return format.format(date)
}
