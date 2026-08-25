package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
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
    // How many weeks or months back from the current one. Zero is now; the
    // forward arrow is disabled there rather than hidden, so the control does
    // not move under the thumb as you step around.
    var back by remember { mutableIntStateOf(0) }
    // Tapping a bar or a day names it, rather than printing a number on every
    // mark and turning the chart into a table.
    var picked by remember { mutableStateOf<LocalDate?>(null) }

    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0; back = 0; picked = null },
                text = { Text("Week") })
            Tab(selected = tab == 1, onClick = { tab = 1; back = 0; picked = null },
                text = { Text("Month") })
        }
        Spacer(Modifier.height(14.dp))

        val today = LocalDate.now()
        val weekEnd = today.minusWeeks(back.toLong())
        val month = YearMonth.from(today).minusMonths(back.toLong())
        val range = if (tab == 0) sevenDaysEnding(weekEnd) else monthDays(month)
        val total = range.sumOf { byDay[it] ?: 0L }

        Header(
            picked = picked,
            pickedMs = picked?.let { byDay[it] ?: 0L },
            total = total,
            label = if (tab == 0) weekLabel(range, back) else monthLabel(month, today),
            canGoForward = back > 0,
            onBack = { back++; picked = null },
            onForward = { if (back > 0) back--; picked = null },
        )
        Spacer(Modifier.height(12.dp))

        if (tab == 0) {
            WeekBars(days = range, byDay = byDay, picked = picked,
                onPick = { picked = if (picked == it) null else it })
        } else {
            MonthGrid(month = month, byDay = byDay, today = today,
                picked = picked, onPick = { picked = if (picked == it) null else it })
        }
    }
}

/** The one number worth reading without touching anything, and the two
 *  controls for moving between periods. */
@Composable
private fun Header(
    picked: LocalDate?,
    pickedMs: Long?,
    total: Long,
    label: String,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                formatListened(if (picked != null) pickedMs ?: 0L else total),
                fontSize = 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                picked?.format(DateTimeFormatter.ofPattern("EEEE d MMM")) ?: label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onBack) {
            Icon(Icons.Default.ChevronLeft, "Earlier")
        }
        IconButton(onClick = onForward, enabled = canGoForward) {
            Icon(
                Icons.Default.ChevronRight,
                "Later",
                tint = if (canGoForward) LocalContentColor.current
                       else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
            )
        }
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
        Modifier.fillMaxWidth().height(BAR_AREA_DP.dp + 38.dp),
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
                Spacer(Modifier.height(4.dp))
                // Seven bars is few enough to label each one, and without a
                // value there is no way to tell a busy day from a quiet one:
                // the tallest bar is always full height, whatever it stands
                // for, because the scale follows the peak in view.
                Text(
                    if (ms > 0) formatCompact(ms) else "",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                Spacer(Modifier.height(2.dp))
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

/**
 * "1h 20m", "35 min", "under a minute", "—".
 *
 * Sub-minute listening reads as "—" only when there was none at all: a first
 * session of forty seconds showing a dash beside a full-height bar is the
 * chart calling itself a liar.
 */
fun formatListened(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        ms <= 0L -> "—"
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** The same figure, short enough to sit under a bar. */
private fun formatCompact(ms: Long): String {
    val minutes = ms / 60_000
    return when {
        minutes < 1 -> "<1m"
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h${minutes % 60}"
    }
}

private fun sevenDaysEnding(last: LocalDate): List<LocalDate> =
    (6 downTo 0).map { last.minusDays(it.toLong()) }

private fun monthDays(month: YearMonth): List<LocalDate> =
    (1..month.lengthOfMonth()).map { month.atDay(it) }

private fun weekLabel(days: List<LocalDate>, back: Int): String {
    if (back == 0) return "Last 7 days"
    val first = days.first()
    val last = days.last()
    val fmt = DateTimeFormatter.ofPattern("d MMM")
    return if (first.month == last.month) {
        "${first.dayOfMonth}–${last.format(fmt)}"
    } else {
        "${first.format(fmt)} – ${last.format(fmt)}"
    }
}

private fun monthLabel(month: YearMonth, today: LocalDate): String {
    val name = month.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    return when {
        month == YearMonth.from(today) -> "This month"
        month.year == today.year -> name
        else -> "$name ${month.year}"
    }
}
