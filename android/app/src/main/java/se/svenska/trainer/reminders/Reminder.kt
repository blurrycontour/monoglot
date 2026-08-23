package se.svenska.trainer.reminders

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/**
 * When a reminder repeats.
 *
 * Two shapes cover what people actually ask for: specific weekdays ("19:00 on
 * Mon, Tue and Fri") and a rolling interval ("15:00 every other day"). A full
 * cron expression would be more general and much worse to configure on a phone.
 */
@Serializable
sealed interface Repeat {
    @Serializable
    data class Weekdays(val days: Set<Int>) : Repeat   // ISO 1=Mon .. 7=Sun

    @Serializable
    data class EveryNDays(val n: Int, val anchorEpochDay: Long) : Repeat
}

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
        val horizon = when (repeat) {
            is Repeat.Weekdays -> if (repeat.days.isEmpty()) return null else 8
            is Repeat.EveryNDays -> repeat.n.coerceIn(1, 60) + 1
        }
        var date = from.toLocalDate()
        repeat(horizon) {
            val candidate = LocalDateTime.of(date, time)
            if (candidate.isAfter(from) && matches(date)) return candidate
            date = date.plusDays(1)
        }
        return null
    }

    private fun matches(date: LocalDate): Boolean = when (repeat) {
        is Repeat.Weekdays -> date.dayOfWeek.value in repeat.days
        is Repeat.EveryNDays -> {
            val n = repeat.n.coerceAtLeast(1)
            val delta = ChronoUnit.DAYS.between(LocalDate.ofEpochDay(repeat.anchorEpochDay), date)
            delta >= 0 && delta % n == 0L
        }
    }

    /** Human summary for the settings list. */
    fun describe(): String {
        val t = "%02d:%02d".format(hour, minute)
        return when (repeat) {
            is Repeat.Weekdays -> {
                val days = repeat.days.sorted()
                val label = when {
                    days.isEmpty() -> "never"
                    days.size == 7 -> "every day"
                    days == listOf(1, 2, 3, 4, 5) -> "weekdays"
                    days == listOf(6, 7) -> "weekends"
                    else -> days.joinToString(", ") {
                        DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() }.take(3)
                    }
                }
                "$t · $label"
            }
            is Repeat.EveryNDays -> when (repeat.n) {
                1 -> "$t · every day"
                2 -> "$t · every other day"
                else -> "$t · every ${repeat.n} days"
            }
        }
    }
}
