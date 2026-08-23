package se.svenska.trainer.reminders

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * Which days a reminder fires on. ISO numbering, 1 = Monday .. 7 = Sunday.
 *
 * Any combination of days covers what people actually ask for, including
 * "every day" (all seven). An interval mode was tried and removed: it could
 * not express "Mon, Tue and Fri", which is the common case.
 */
@Serializable
data class Repeat(val days: Set<Int>)

@Serializable
data class Reminder(
    val id: String,
    val hour: Int,
    val minute: Int,
    val repeat: Repeat,
    val enabled: Boolean = true,
    val label: String = "",
) {
    val time: LocalTime get() = LocalTime.of(hour, minute)

    /** The next firing strictly after [from], or null if it can never fire. */
    fun nextAfter(from: LocalDateTime): LocalDateTime? {
        if (!enabled) return null
        // Look ahead a bounded window: 8 days covers any weekday set, and an
        // interval of n days needs at most n.
        if (repeat.days.isEmpty()) return null
        // Eight days covers any set of weekdays.
        val horizon = 8
        var date = from.toLocalDate()
        repeat(horizon) {
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(from) && matches(date)) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    private fun matches(date: LocalDate): Boolean = date.dayOfWeek.value in repeat.days

    /** Human summary for the settings list. */
    fun describe(): String {
        val t = "%02d:%02d".format(hour, minute)
        val days = repeat.days.sorted()
        val label = when {
            days.isEmpty() -> "never"
            days.size == 7 -> "every day"
            days == listOf(1, 2, 3, 4, 5) -> "weekdays"
            days == listOf(6, 7) -> "weekends"
            else -> days.joinToString(", ") { dayLabel(it) }
        }
        return "$t · $label"
    }

    companion object {
        /** Three-letter day name, e.g. "Mon". */
        fun dayLabel(iso: Int): String =
            DayOfWeek.of(iso).name.lowercase().replaceFirstChar { it.uppercase() }.take(3)

        /** Single letter for the day picker. Tue and Thu, Sat and Sun collide,
         *  so the picker relies on fixed Mon-first order for disambiguation. */
        fun dayInitial(iso: Int): String = when (iso) {
            1 -> "M"; 2 -> "T"; 3 -> "W"; 4 -> "T"; 5 -> "F"; 6 -> "S"; else -> "S"
        }
    }
}
