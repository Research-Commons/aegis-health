package com.aegis.health.db.history

import com.aegis.health.models.AegisResponse
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Map a max flag severity (0–5) to one of the four UI bucket keys. */
fun severityKeyFor(maxSeverity: Int): String = when {
    maxSeverity >= 5 -> HistoryEntity.SEV_CRIT
    maxSeverity in 3..4 -> HistoryEntity.SEV_MOD
    maxSeverity in 1..2 -> HistoryEntity.SEV_LOW
    else -> HistoryEntity.SEV_INFO
}

fun severityKeyFor(resp: AegisResponse): String =
    severityKeyFor(resp.flags.maxOfOrNull { it.severity } ?: 0)

/** Sample-style relative timestamp ("Yesterday, 9:14 AM" / "3 days ago" / "Apr 28"). */
fun formatRelative(createdAt: Long, now: Long = System.currentTimeMillis()): String {
    val diffMs = now - createdAt
    val days = TimeUnit.MILLISECONDS.toDays(diffMs)
    val hours = TimeUnit.MILLISECONDS.toHours(diffMs)
    val timeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
    val dateFmt = SimpleDateFormat("MMM d", Locale.getDefault())
    return when {
        hours < 1 -> "Just now"
        days < 1 -> "Today, ${timeFmt.format(Date(createdAt))}"
        days < 2 -> "Yesterday, ${timeFmt.format(Date(createdAt))}"
        days < 7 -> "$days days ago"
        else -> dateFmt.format(Date(createdAt))
    }
}

/** Per the design samples, "This week" buckets entries < 7 days old; rest go to "Earlier". */
fun isThisWeek(createdAt: Long, now: Long = System.currentTimeMillis()): Boolean =
    TimeUnit.MILLISECONDS.toDays(now - createdAt) < 7
