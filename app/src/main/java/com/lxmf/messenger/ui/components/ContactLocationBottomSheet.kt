package com.lxmf.messenger.ui.components

import android.content.Context
import android.content.Intent
import android.location.Location
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.lxmf.messenger.viewmodel.ContactMarker
import com.lxmf.messenger.viewmodel.MarkerState

/**
 * Bottom sheet displayed when tapping a contact's location marker on the map.
 *
 * Shows:
 * - Contact's identicon avatar and display name
 * - Last updated time ("Updated 30s ago")
 * - Distance and direction from user ("450m southeast")
 * - Directions button → opens external maps app
 * - Message button → navigates to conversation
 *
 * @param marker The contact marker that was tapped
 * @param userLocation The user's current location (for distance calculation)
 * @param onDismiss Callback when the bottom sheet is dismissed
 * @param onSendMessage Callback to navigate to the conversation with this contact
 * @param sheetState The state of the bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactLocationBottomSheet(
    marker: ContactMarker,
    userLocation: Location?,
    onDismiss: () -> Unit,
    onSendMessage: () -> Unit,
    onRemoveMarker: () -> Unit = {},
    sheetState: SheetState,
) {
    val context = LocalContext.current
    val distanceText = formatDistanceAndDirection(userLocation, marker.latitude, marker.longitude)
    val updatedText = formatUpdatedTime(marker.timestamp)
    val isStale = marker.state != MarkerState.FRESH

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        contentWindowInsets = { WindowInsets(0) },
        modifier = Modifier.systemBarsPadding(),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
        ) {
            // Avatar, name, and last updated
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Profile icon (dimmed for stale locations)
                Box {
                    ProfileIcon(
                        iconName = marker.iconName,
                        foregroundColor = marker.iconForegroundColor,
                        backgroundColor = marker.iconBackgroundColor,
                        size = 48.dp,
                        fallbackHash = marker.publicKey ?: hexStringToByteArray(marker.destinationHash),
                        modifier = if (isStale) Modifier.alpha(0.6f) else Modifier,
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = marker.displayName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        // Stale badge
                        if (isStale) {
                            Spacer(modifier = Modifier.width(8.dp))
                            StaleLocationBadge(marker.state)
                        }
                    }
                    Text(
                        text = updatedText,
                        style = MaterialTheme.typography.bodyMedium,
                        color =
                            if (isStale) {
                                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Distance and direction
            Text(
                text = distanceText,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedButton(
                    onClick = { openDirectionsInMaps(context, marker.latitude, marker.longitude) },
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.Default.Directions,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Directions")
                }

                Button(
                    onClick = onSendMessage,
                    modifier = Modifier.weight(1f),
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Message,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Message")
                }
            }

            // Remove marker button (only for stale/expired markers)
            if (isStale) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(
                    onClick = onRemoveMarker,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.error,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "Remove from map",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * Calculate and format the distance and direction from user to marker.
 *
 * @param userLocation The user's current location, or null if unavailable
 * @param markerLat Marker latitude
 * @param markerLng Marker longitude
 * @return Formatted string like "450m southeast" or "Location unknown"
 */
internal fun formatDistanceAndDirection(
    userLocation: Location?,
    markerLat: Double,
    markerLng: Double,
): String {
    if (userLocation == null) return "Location unknown"

    val results = FloatArray(2)
    Location.distanceBetween(
        userLocation.latitude,
        userLocation.longitude,
        markerLat,
        markerLng,
        results,
    )
    val distance = results[0]
    val bearing = results[1]

    val distanceText =
        when {
            distance < 1000 -> "${distance.toInt()}m"
            else -> "%.1fkm".format(distance / 1000)
        }

    val direction = bearingToDirection(bearing)
    return "$distanceText $direction"
}

/**
 * Convert a bearing angle to a cardinal/intercardinal direction.
 *
 * @param bearing Bearing in degrees (0-360)
 * @return Direction string like "north", "southeast", etc.
 */
internal fun bearingToDirection(bearing: Float): String {
    val normalized = (bearing + 360) % 360
    return when {
        normalized < 22.5 || normalized >= 337.5 -> "north"
        normalized < 67.5 -> "northeast"
        normalized < 112.5 -> "east"
        normalized < 157.5 -> "southeast"
        normalized < 202.5 -> "south"
        normalized < 247.5 -> "southwest"
        normalized < 292.5 -> "west"
        else -> "northwest"
    }
}

/**
 * Format the timestamp as a relative time string.
 *
 * @param timestamp Timestamp in milliseconds since epoch
 * @return Formatted string like "Updated just now", "Updated 30s ago", "Updated 5m ago"
 */
internal fun formatUpdatedTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < 10_000 -> "Updated just now"
        diff < 60_000 -> "Updated ${diff / 1000}s ago"
        diff < 3600_000 -> "Updated ${diff / 60_000}m ago"
        diff < 86400_000 -> "Updated ${diff / 3600_000}h ago"
        else -> "Updated ${diff / 86400_000}d ago"
    }
}

/**
 * Open directions to a location in an external maps app.
 *
 * Tries Google Maps navigation first, falls back to generic geo URI.
 *
 * @param context Android context
 * @param lat Destination latitude
 * @param lng Destination longitude
 */
internal fun openDirectionsInMaps(
    context: Context,
    lat: Double,
    lng: Double,
) {
    try {
        // Try Google Maps navigation first (walking mode)
        val googleMapsUri = Uri.parse("google.navigation:q=$lat,$lng&mode=w")
        val googleIntent = Intent(Intent.ACTION_VIEW, googleMapsUri)

        if (googleIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(googleIntent)
        } else {
            // Fallback to generic geo URI
            val geoUri = Uri.parse("geo:$lat,$lng?q=$lat,$lng")
            val geoIntent = Intent(Intent.ACTION_VIEW, geoUri)
            context.startActivity(geoIntent)
        }
    } catch (_: android.content.ActivityNotFoundException) {
        android.widget.Toast
            .makeText(context, "No maps application found", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}

/**
 * Badge indicating the location is stale or expired.
 *
 * @param state The marker state (STALE or EXPIRED_GRACE_PERIOD)
 */
@Composable
private fun StaleLocationBadge(state: MarkerState) {
    if (state == MarkerState.FRESH) return

    val (text, color) =
        when (state) {
            MarkerState.STALE -> "Stale" to MaterialTheme.colorScheme.outline
            MarkerState.EXPIRED_GRACE_PERIOD -> "Last known" to MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
            else -> return
        }

    Surface(
        color = color.copy(alpha = 0.15f),
        shape = RoundedCornerShape(4.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/**
 * Convert a hex string to a byte array.
 *
 * @param hex Hex string (with or without spaces/colons)
 * @return ByteArray
 */
private fun hexStringToByteArray(hex: String): ByteArray {
    val cleanHex = hex.replace(" ", "").replace(":", "")
    val len = cleanHex.length
    if (len == 0 || len % 2 != 0) return ByteArray(0)

    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(cleanHex[i], 16) shl 4) + Character.digit(cleanHex[i + 1], 16)).toByte()
        i += 2
    }
    return data
}
