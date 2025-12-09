package com.lxmf.messenger.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lxmf.messenger.data.repository.Announce
import com.lxmf.messenger.ui.theme.MeshConnected
import com.lxmf.messenger.ui.theme.MeshLimited
import com.lxmf.messenger.ui.theme.MeshOffline
import com.lxmf.messenger.util.formatTimeSince

/**
 * Shared peer card component used by both AnnounceStreamScreen and SavedPeersScreen.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PeerCard(
    announce: Announce,
    onClick: () -> Unit = {},
    onFavoriteClick: () -> Unit = {},
    onLongPress: () -> Unit = {},
    badgeContent: @Composable () -> Unit = { NodeTypeBadge(nodeType = announce.nodeType) },
    showFavoriteToggle: Boolean = true,
) {
    val hapticFeedback = LocalHapticFeedback.current

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = {
                        hapticFeedback.performHapticFeedback(HapticFeedbackType.LongPress)
                        onLongPress()
                    },
                ),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors =
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
    ) {
        Box {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                        .padding(end = 32.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Identicon
                Identicon(
                    hash = announce.publicKey,
                    size = 56.dp,
                    modifier = Modifier.align(Alignment.CenterVertically),
                )

                // Peer information
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .align(Alignment.CenterVertically),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    // Peer name
                    Text(
                        text = announce.peerName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )

                    // Destination hash (abbreviated)
                    Text(
                        text = formatHashString(announce.destinationHash),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // Time since last seen
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.AccessTime,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = formatTimeSince(announce.lastSeenTimestamp),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Badge and signal strength indicator
                Column(
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.align(Alignment.CenterVertically),
                ) {
                    badgeContent()
                    SignalStrengthIndicator(hops = announce.hops)
                }
            }

            // Star button overlay
            IconButton(
                onClick = onFavoriteClick,
                modifier =
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp),
            ) {
                Box(
                    modifier =
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(
                                if (announce.isFavorite || !showFavoriteToggle) {
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
                                } else {
                                    Color.Transparent
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector =
                            if (announce.isFavorite || !showFavoriteToggle) {
                                Icons.Default.Star
                            } else {
                                Icons.Default.StarBorder
                            },
                        contentDescription = if (announce.isFavorite) "Remove from saved" else "Save peer",
                        tint =
                            if (announce.isFavorite || !showFavoriteToggle) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }
        }
    }
}

@Composable
fun SignalStrengthIndicator(
    hops: Int,
    modifier: Modifier = Modifier,
) {
    val (strength, color, _) =
        when {
            hops <= 1 -> Triple(3, MeshConnected, "Excellent")
            hops <= 3 -> Triple(2, MeshLimited, "Good")
            else -> Triple(1, MeshOffline, "Weak")
        }

    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // Signal bars
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            for (i in 1..3) {
                Box(
                    modifier =
                        Modifier
                            .width(6.dp)
                            .height((8 + i * 6).dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (i <= strength) color else Color.LightGray.copy(alpha = 0.3f)),
                )
            }
        }

        // Hop count
        Text(
            text = "$hops ${if (hops == 1) "hop" else "hops"}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
        )
    }
}

@Composable
fun NodeTypeBadge(nodeType: String) {
    val (text, color) =
        when (nodeType) {
            "NODE" -> "Node" to MaterialTheme.colorScheme.tertiary
            "PEER" -> "Peer" to MaterialTheme.colorScheme.primary
            "PROPAGATION_NODE" -> "Relay" to MaterialTheme.colorScheme.secondary
            else -> "Node" to MaterialTheme.colorScheme.tertiary
        }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun AudioBadge() {
    val color = MaterialTheme.colorScheme.error

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = "Audio",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

@Composable
fun OtherBadge() {
    val color = MaterialTheme.colorScheme.outline

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.15f),
    ) {
        Text(
            text = "Other",
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Format a destination hash string for display (first 16 characters).
 */
fun formatHashString(hashString: String): String {
    return hashString.take(16)
}
