package io.blurrycontour.monoglot.ui.util

import io.blurrycontour.monoglot.data.ItemSummary
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Klartext episodes all share one title, so the date is the only thing that
 * distinguishes them. It has to be the most legible thing on the card, and the
 * list has to be grouped so scanning is a glance rather than a read.
 */
object Dates {

    private val dayMonth = DateTimeFormatter.ofPattern("d MMM")
    private val dayMonthYear = DateTimeFormatter.ofPattern("d MMM yyyy")
    private val weekday = DateTimeFormatter.ofPattern("EEEE")

    fun parse(iso: String?): OffsetDateTime? =
        iso?.let { runCatching { OffsetDateTime.parse(it) }.getOrNull() }

    /** Section header an item belongs under. */
    fun group(published: OffsetDateTime?, today: LocalDate = LocalDate.now()): String {
        val date = published?.toLocalDate() ?: return "Undated"
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days < 0L -> "Upcoming"
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days < 7L -> "This week"
            days < 14L -> "Last week"
            date.year == today.year && date.month == today.month -> "This month"
            days < 365L -> "Earlier"
            else -> "${date.year}"
        }
    }

    /**
     * Short label for the card. Within the last week the weekday reads faster
     * than a numeric date ("Tuesday" vs "19 Aug"); beyond that the date is
     * what actually disambiguates.
     */
    fun label(published: OffsetDateTime?, today: LocalDate = LocalDate.now()): String {
        val dt = published ?: return "—"
        val date = dt.toLocalDate()
        val days = ChronoUnit.DAYS.between(date, today)
        return when {
            days == 0L -> "Today"
            days == 1L -> "Yesterday"
            days in 2..6 -> date.format(weekday)
            date.year == today.year -> date.format(dayMonth)
            else -> date.format(dayMonthYear)
        }
    }

    /**
     * Groups items into ordered sections, preserving the incoming order.
     *
     * [today] is injectable so the grouping can be tested against fixed dates:
     * against the real clock the expected labels change overnight.
     */
    fun groupItems(
        items: List<ItemSummary>,
        today: LocalDate = LocalDate.now(),
    ): List<Pair<String, List<ItemSummary>>> {
        val order = LinkedHashMap<String, MutableList<ItemSummary>>()
        items.forEach { item ->
            val key = group(parse(item.publishedAt), today)
            order.getOrPut(key) { mutableListOf() }.add(item)
        }
        return order.map { (k, v) -> k to v }
    }
}

fun formatDuration(ms: Int): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return if (minutes >= 60) {
        "%d h %02d min".format(minutes / 60, minutes % 60)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

/** "12 min left" reads better than a progress percentage when deciding what to
 *  resume. */
fun remainingLabel(positionMs: Int, durationMs: Int): String? {
    if (durationMs <= 0 || positionMs <= 0) return null
    val left = Duration.ofMillis((durationMs - positionMs).toLong())
    val minutes = left.toMinutes()
    return when {
        minutes < 1 -> "less than a minute left"
        minutes == 1L -> "1 min left"
        else -> "$minutes min left"
    }
}

fun formatBytesShort(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> "%.1f GB".format(bytes / 1e9)
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1e6)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1e3)
    else -> "$bytes B"
}
