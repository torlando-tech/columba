package com.lxmf.messenger.util

/**
 * Formats a timestamp into a human-readable relative time string.
 *
 * @param timestamp The timestamp in milliseconds since epoch
 * @return A string like "Just now", "5 minutes ago", "2 hours ago", etc.
 */
fun formatRelativeTime(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    return when {
        seconds < 60 -> "Just now"
        minutes < 60 -> "$minutes ${if (minutes == 1L) "minute" else "minutes"} ago"
        hours < 24 -> "$hours ${if (hours == 1L) "hour" else "hours"} ago"
        days < 7 -> "$days ${if (days == 1L) "day" else "days"} ago"
        else -> "${days / 7} ${if (days / 7 == 1L) "week" else "weeks"} ago"
    }
}

/**
 * Format a timestamp as a human-readable "time since" string.
 * Used by PeerCard and AnnounceDetailScreen for announce timestamps.
 *
 * @param timestamp The timestamp to format (milliseconds since epoch)
 * @param now The current time for comparison (defaults to System.currentTimeMillis())
 * @return A human-readable string like "just now", "5 minutes ago", "2 hours ago", or "3 days ago"
 */
fun formatTimeSince(
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val diffMillis = now - timestamp
    val diffMinutes = diffMillis / (60 * 1000)
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    return when {
        diffMinutes < 1 -> "just now"
        diffMinutes < 60 -> "$diffMinutes ${if (diffMinutes == 1L) "minute" else "minutes"} ago"
        diffHours < 24 -> "$diffHours ${if (diffHours == 1L) "hour" else "hours"} ago"
        else -> "$diffDays ${if (diffDays == 1L) "day" else "days"} ago"
    }
}
