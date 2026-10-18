package network.columba.app.util

import android.content.Context
import network.columba.app.R

/**
 * Formats a timestamp into a human-readable relative time string.
 *
 * @param context Context for resolving localized relative-time resources
 * @param timestamp The timestamp in milliseconds since epoch
 * @return A string like "Just now", "5 minutes ago", "2 hours ago", etc.
 */
fun formatRelativeTime(
    context: Context,
    timestamp: Long,
): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val seconds = diff / 1000
    val minutes = seconds / 60
    val hours = minutes / 60
    val days = hours / 24

    val res = context.resources
    return when {
        seconds < 60 -> context.getString(R.string.time_relative_just_now)
        minutes < 60 -> res.getQuantityString(R.plurals.time_minutes_ago, minutes.toInt(), minutes)
        hours < 24 -> res.getQuantityString(R.plurals.time_hours_ago, hours.toInt(), hours)
        days < 7 -> res.getQuantityString(R.plurals.time_days_ago, days.toInt(), days)
        else -> {
            val weeks = (days / 7).toInt()
            res.getQuantityString(R.plurals.time_weeks_ago, weeks, weeks)
        }
    }
}

/**
 * Format a timestamp as a human-readable "time since" string.
 * Used by PeerCard and AnnounceDetailScreen for announce timestamps.
 *
 * @param context Context for resolving localized relative-time resources
 * @param timestamp The timestamp to format (milliseconds since epoch)
 * @param now The current time for comparison (defaults to System.currentTimeMillis())
 * @return A human-readable string like "just now", "5 minutes ago", "2 hours ago", or "3 days ago"
 */
fun formatTimeSince(
    context: Context,
    timestamp: Long,
    now: Long = System.currentTimeMillis(),
): String {
    val diffMillis = now - timestamp
    val diffMinutes = diffMillis / (60 * 1000)
    val diffHours = diffMinutes / 60
    val diffDays = diffHours / 24

    val res = context.resources
    return when {
        diffMinutes < 1 -> context.getString(R.string.time_since_just_now)
        diffMinutes < 60 ->
            res.getQuantityString(R.plurals.time_minutes_ago, diffMinutes.toInt(), diffMinutes)
        diffHours < 24 ->
            res.getQuantityString(R.plurals.time_hours_ago, diffHours.toInt(), diffHours)
        else ->
            res.getQuantityString(R.plurals.time_days_ago, diffDays.toInt(), diffDays)
    }
}
