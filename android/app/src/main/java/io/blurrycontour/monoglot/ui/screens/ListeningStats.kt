package io.blurrycontour.monoglot.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.IntSize
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
    var tip by remember { mutableStateOf<Tip?>(null) }

    Column(modifier) {
        PrimaryTabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            Tab(selected = tab == 0, onClick = { tab = 0; back = 0; tip = null },
                text = { Text("Week") })
            Tab(selected = tab == 1, onClick = { tab = 1; back = 0; tip = null },
                text = { Text("Month") })
        }
        Spacer(Modifier.height(14.dp))

        val today = LocalDate.now()
        val weekEnd = today.minusWeeks(back.toLong())
        val month = YearMonth.from(today).minusMonths(back.toLong())
        val range = if (tab == 0) sevenDaysEnding(weekEnd) else monthDays(month)
        val total = range.sumOf { byDay[it] ?: 0L }

        Header(
            total = total,
            label = if (tab == 0) weekLabel(range, back) else monthLabel(month, today),
            canGoForward = back > 0,
            onBack = { back++; tip = null },
            onForward = { if (back > 0) back--; tip = null },
        )
        Spacer(Modifier.height(12.dp))

        // The charts and their tooltip share one coordinate space, so a mark
        // can say where it is and the bubble can be placed over it.
        var boxCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { boxCoords = it }
        ) {
            val onPick: (LocalDate, Long, LayoutCoordinates) -> Unit = { day, ms, coords ->
                val parent = boxCoords
                tip = if (tip?.date == day || parent == null) {
                    null
                } else {
                    val origin = parent.localPositionOf(coords, Offset.Zero)
                    Tip(
                        date = day,
                        ms = ms,
                        centreX = origin.x + coords.size.width / 2f,
                        topY = origin.y,
                    )
                }
            }

            if (tab == 0) {
                WeekBars(days = range, byDay = byDay, picked = tip?.date, onPick = onPick)
            } else {
                MonthGrid(month = month, byDay = byDay, today = today,
                    picked = tip?.date, onPick = onPick)
            }

            tip?.let { t ->
                // A Popup, not an overlay inside the chart: it takes part in no
                // layout, so it needs no room reserved for it and is free to
                // sit over the header when the mark it belongs to is near the
                // top. Reserving that room inside the plot instead pushed every
                // chart down by the height of a bubble that is usually absent —
                // and made the calendar, whose marks are small, mostly gap.
                Popup(
                    popupPositionProvider = remember(t) {
                        object : PopupPositionProvider {
                            override fun calculatePosition(
                                anchorBounds: IntRect,
                                windowSize: IntSize,
                                layoutDirection: LayoutDirection,
                                popupContentSize: IntSize,
                            ): IntOffset {
                                // Anchored on the chart, offset to the mark:
                                // centred over it, and sitting entirely above
                                // it so the tail lands on its top edge.
                                val x = anchorBounds.left + t.centreX.toInt() -
                                    popupContentSize.width / 2
                                val y = anchorBounds.top + t.topY.toInt() -
                                    popupContentSize.height
                                return IntOffset(
                                    x.coerceIn(
                                        0,
                                        (windowSize.width - popupContentSize.width)
                                            .coerceAtLeast(0),
                                    ),
                                    y.coerceAtLeast(0),
                                )
                            }
                        }
                    },
                    properties = PopupProperties(focusable = false),
                    onDismissRequest = { tip = null },
                ) {
                    TipBubble(t)
                }
            }
        }
    }
}

/**
 * The period's total and the controls for moving between periods.
 *
 * The total stays put when a day is tapped. It used to be replaced by whatever
 * you touched, so the one figure you were tracking vanished the moment you
 * asked about a day within it — and there was no way back to it but to tap the
 * same mark again.
 */
@Composable
private fun Header(
    total: Long,
    label: String,
    canGoForward: Boolean,
    onBack: () -> Unit,
    onForward: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                formatListened(total),
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
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

/** A tapped mark: what it says, and where on the chart it sits. */
private data class Tip(
    val date: LocalDate,
    val ms: Long,
    val centreX: Float,
    val topY: Float,
)

/**
 * The readout for one mark, floated above it.
 *
 * Seconds included: on a chart whose bars are scaled to each other, this is the
 * only place an exact figure appears, and rounding a first short session to
 * "under a minute" twice over would be no answer at all.
 */
/**
 * The readout for one mark: a bubble with a dotted tail beneath it.
 *
 * The tail belongs to the bubble rather than being drawn on the chart, because
 * the bubble is a Popup and the chart cannot paint outside its own bounds. It
 * also means the two can never drift apart.
 *
 * Seconds included: on a chart whose bars are scaled against each other, this
 * is the only exact figure anywhere.
 */
@Composable
private fun TipBubble(tip: Tip) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            // The theme's own accent container. inverseSurface was neither: on
            // a dark theme it is a white card with black text, which belongs to
            // no theme in this app and reads as a piece of another product.
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary),
            shape = RoundedCornerShape(8.dp),
            shadowElevation = 3.dp,
        ) {
            Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                Text(
                    tip.date.format(DateTimeFormatter.ofPattern("EEE d MMM")),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f),
                )
                Text(
                    formatExact(tip.ms),
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }

        val leader = MaterialTheme.colorScheme.primary
        Canvas(Modifier.width(2.dp).height(TIP_GAP + 8.dp)) {
            drawLine(
                color = leader,
                start = Offset(size.width / 2f, 0f),
                end = Offset(size.width / 2f, size.height),
                strokeWidth = size.width,
                pathEffect = PathEffect.dashPathEffect(
                    floatArrayOf(size.width * 2, size.width * 2)),
            )
        }
    }
}

/** Clear air between the bubble's tail and the mark it points at. */
private val TIP_GAP = 6.dp

private const val BAR_AREA_DP = 108

@Composable
private fun WeekBars(
    days: List<LocalDate>,
    byDay: Map<LocalDate, Long>,
    picked: LocalDate?,
    onPick: (LocalDate, Long, LayoutCoordinates) -> Unit,
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

            // The mark's own coordinates, not the column's: the column spans
            // the whole plot area, so a leader drawn to its top would start at
            // the ceiling regardless of how tall the bar actually is.
            var mark by remember { mutableStateOf<LayoutCoordinates?>(null) }
            Column(
                Modifier
                    .weight(1f)
                    .clickable { mark?.let { onPick(day, ms, it) } },
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
                            .onGloballyPositioned { if (ms <= 0L) mark = it }
                    )
                    if (ms > 0) {
                        Box(
                            Modifier
                                .fillMaxWidth(0.62f)
                                .fillMaxHeight(fraction.coerceAtLeast(0.03f))
                                // Rounded at the data end only; the baseline
                                // end stays square and anchored.
                                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                                .onGloballyPositioned { mark = it }
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
    onPick: (LocalDate, Long, LayoutCoordinates) -> Unit,
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
                        if (day != null) {
                            val ms = byDay[day] ?: 0L
                            var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
                            DayCircle(
                                day, ms, peak,
                                isToday = day == today,
                                selected = day == picked,
                                modifier = Modifier.onGloballyPositioned { coords = it },
                                onClick = { coords?.let { c -> onPick(day, ms, c) } },
                            )
                        }
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
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val share = (ms.toFloat() / peakMs).coerceIn(0f, 1f)
    // A floor well clear of the surface, so the lightest step that means
    // "something happened" cannot be mistaken for an empty day.
    val alpha = if (ms <= 0L) 0f else 0.22f + 0.78f * share

    Box(
        modifier
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
        minutes < 1 -> "<1 min"
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0L -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }
}

/** Down to the second, for the one mark being asked about. */
private fun formatExact(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val sec = total % 60
    return when {
        total <= 0L -> "nothing"
        h > 0 -> "${h}h ${m}m ${sec}s"
        m > 0 -> "${m}m ${sec}s"
        else -> "${sec}s"
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
