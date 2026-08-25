package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.blurrycontour.monoglot.data.DayTotal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * Listening time, by day.
 *
 * Deliberately not derived from how far into episodes you got: a sentence heard
 * five times counts five times here, and a re-listen counts again, because the
 * question this answers is how long you sat with the language rather than how
 * much of the catalogue you got through.
 *
 * Two views of one series, so both are a single hue — the theme's own accent —
 * and neither needs a legend to say what the colour means. Bars for the week,
 * where comparing seven magnitudes is the job; a calendar for the month, where
 * the shape of the habit matters more than any one day's figure.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ListeningSection(days: List<DayTotal>, modifier: Modifier = Modifier) {
    val byDay = remember(days) {
        days.mapNotNull { d ->
            runCatching { LocalDate.parse(d.day) to d.ms }.getOrNull()
        }.toMap()
    }
    var tab by remember { mutableIntStateOf(0) }
    // Tapping a bar or a day names it, rather than printing a number on every
    // mark and turning the chart into a table.
    var picked by remember { mutableStateOf<LocalDate?>(null) }

    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0; picked = null },
                text = { Text("Week") })
            Tab(selected = tab == 1, onClick = { tab = 1; picked = null },
                text = { Text("Month") })
        }
        Spacer(Modifier.height(14.dp))

        val today = LocalDate.now()
        val range = if (tab == 0) lastSevenDays(today) else monthDays(today)
        val total = range.sumOf { byDay[it] ?: 0L }

        Headline(picked = picked, pickedMs = picked?.let { byDay[it] ?: 0L },
            total = total, weekly = tab == 0)
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            WeekBars(days = range, byDay = byDay, picked = picked,
                onPick = { picked = if (picked == it) null else it })
        } else {
            MonthGrid(month = YearMonth.from(today), byDay = byDay, today = today,
                picked = picked, onPick = { picked = if (picked == it) null else it })
        }
    }
}

/** The one number worth reading without touching anything. */
@Composable
private fun Headline(
    picked: LocalDate?,
    pickedMs: Long?,
    total: Long,
    weekly: Boolean,
) {
    val label = when {
        picked != null -> picked.format(DateTimeFormatter.ofPattern("EEEE d MMM"))
        weekly -> "Last 7 days"
        else -> "This month"
    }
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            formatListened(if (picked != null) pickedMs ?: 0L else total),
            fontSize = 26.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.width(10.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

private const val BAR_AREA_DP = 108

@Composable
private fun WeekBars(
    days: List<LocalDate>,
    byDay: Map<LocalDate, Long>,
    picked: LocalDate?,
    onPick: (LocalDate) -> Unit,
) {
    // Scaled to the busiest day in view, with a floor so a single quiet day
    // does not render as one enormous bar and a week of zeroes.
    val peak = maxOf(days.maxOfOrNull { byDay[it] ?: 0L } ?: 0L, 1L)

    Row(
        Modifier.fillMaxWidth().height(BAR_AREA_DP.dp + 22.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        days.forEach { day ->
            val ms = byDay[day] ?: 0L
            val fraction by animateFloatAsState(
                (ms.toFloat() / peak).coerceIn(0f, 1f), tween(420), label = "bar")
            val selected = day == picked

            Column(
                Modifier.weight(1f).clickable { onPick(day) },
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Bottom,
            ) {
                Box(
                    Modifier.height(BAR_AREA_DP.dp).fillMaxWidth(),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    // A day with nothing still gets a mark, or the chart reads
                    // as missing data rather than a day off.
                    Box(
                        Modifier
                            .fillMaxWidth(0.62f)
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(MaterialTheme.colorScheme.outlineVariant)
                    )
                    if (ms > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                // Rounded at the data end only; the baseline
                                // end stays square and anchored.
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .background(
                                    if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.primary.copy(alpha = 0.78f)
                                )
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    day.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    // Axis ink, never the series colour.
                    color = if (selected) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                )
            }
        }
    }
}

@Composable
private fun MonthGrid(
    month: YearMonth,
    byDay: Map<LocalDate, Long>,
    today: LocalDate,
    picked: LocalDate?,
    onPick: (LocalDate) -> Unit,
) {
    val first = month.atDay(1)
    val peak = maxOf(
        (1..month.lengthOfMonth()).maxOfOrNull { byDay[month.atDay(it)] ?: 0L } ?: 0L, 1L)
    // Leading blanks so the first of the month lands under its weekday.
    val lead = (first.dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
    val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DayOfWeek.entries.forEach { dow ->
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        cells.chunked(7).forEach { week ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                week.forEach { day ->
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        if (day != null) DayCircle(day, byDay[day] ?: 0L, peak,
                            isToday = day == today, selected = day == picked,
                            onClick = { onPick(day) })
                    }
                }
                // A short final week must not stretch its days across the row.
                repeat(7 - week.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * One day. Magnitude is carried by depth of a single hue rather than by size:
 * circles of varying diameter are read by area, which the eye judges badly.
 */
@Composable
private fun DayCircle(
    day: LocalDate,
    ms: Long,
    peakMs: Long,
    isToday: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val share = (ms.toFloat() / peakMs).coerceIn(0f, 1f)
    // A floor well clear of the surface, so the lightest step that means
    // "something happened" cannot be mistaken for an empty day.
    val alpha = if (ms <= 0L) 0f else 0.22f + 0.78f * share

    Box(
        Modifier
            .aspectRatio(1f)
            .padding(1.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = alpha))
            .then(
                when {
                    selected -> Modifier.border(
                        2.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                    isToday -> Modifier.border(
                        1.5.dp, MaterialTheme.colorScheme.outline, CircleShape)
                    else -> Modifier
                }
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "${day.dayOfMonth}",
            style = MaterialTheme.typography.labelSmall,
            // Ink flips once the fill is dark enough to swallow the default.
            color = if (share > 0.55f && ms > 0) MaterialTheme.colorScheme.onPrimary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/** "1h 20m", "35 min", "—". Rounded: this is a record kept for pleasure. */
fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes <= 0 -> "—"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

private fun lastSevenDays(today: LocalDate): List<LocalDate> =
    (6 downTo 0).map { today.minusDays(it.toLong()) }

private fun monthDays(today: LocalDate): List<LocalDate> {
    val m = YearMonth.from(today)
    return (1..m.lengthOfMonth()).map { m.atDay(it) }
}
