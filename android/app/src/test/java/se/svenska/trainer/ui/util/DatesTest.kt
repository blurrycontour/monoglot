package se.svenska.trainer.ui.util

import org.junit.Assert.assertEquals
import org.junit.Test
import se.svenska.trainer.data.ItemSummary
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class DatesTest {

    private val today = LocalDate.of(2026, 8, 23)

    private fun at(y: Int, m: Int, d: Int): OffsetDateTime =
        OffsetDateTime.of(y, m, d, 18, 55, 0, 0, ZoneOffset.UTC)

    @Test
    fun `groups by recency`() {
        assertEquals("Today", Dates.group(at(2026, 8, 23), today))
        assertEquals("Yesterday", Dates.group(at(2026, 8, 22), today))
        assertEquals("This week", Dates.group(at(2026, 8, 18), today))
        assertEquals("Last week", Dates.group(at(2026, 8, 12), today))
        assertEquals("This month", Dates.group(at(2026, 8, 2), today))
        assertEquals("Earlier", Dates.group(at(2026, 3, 1), today))
        assertEquals("2024", Dates.group(at(2024, 3, 1), today))
    }

    @Test
    fun `undated items still group`() {
        assertEquals("Undated", Dates.group(null, today))
    }

    // Ingestion can briefly hold an episode published later today in another
    // timezone; it must not fall through to "Earlier".
    @Test
    fun `future dates are labelled upcoming`() {
        assertEquals("Upcoming", Dates.group(at(2026, 8, 25), today))
    }

    @Test
    fun `labels prefer weekday within the last week`() {
        assertEquals("Today", Dates.label(at(2026, 8, 23), today))
        assertEquals("Yesterday", Dates.label(at(2026, 8, 22), today))
        assertEquals("Tuesday", Dates.label(at(2026, 8, 18), today))
        assertEquals("2 Aug", Dates.label(at(2026, 8, 2), today))
        assertEquals("1 Mar 2024", Dates.label(at(2024, 3, 1), today))
    }

    @Test
    fun `grouping preserves incoming order and covers every item`() {
        val items = listOf(
            item(1, "2026-08-23T18:55:00Z"),
            item(2, "2026-08-22T18:55:00Z"),
            item(3, "2026-08-21T18:55:00Z"),
            item(4, null),
        )
        val grouped = Dates.groupItems(items)
        assertEquals(listOf("Today", "Yesterday", "This week", "Undated"), grouped.map { it.first })
        assertEquals(items.size, grouped.sumOf { it.second.size })
    }

    @Test
    fun `remaining label reads as time left, not a percentage`() {
        assertEquals("5 min left", remainingLabel(positionMs = 300_000, durationMs = 600_000))
        assertEquals("1 min left", remainingLabel(positionMs = 540_000, durationMs = 600_000))
        assertEquals(null, remainingLabel(positionMs = 0, durationMs = 600_000))
        assertEquals(null, remainingLabel(positionMs = 100, durationMs = 0))
    }

    @Test
    fun `durations format for both short and long episodes`() {
        assertEquals("5:00", formatDuration(300_000))
        assertEquals("0:07", formatDuration(7_000))
        assertEquals("1 h 05 min", formatDuration(3_900_000))
    }

    private fun item(id: Int, published: String?) =
        ItemSummary(id = id, title = "t", publishedAt = published, durationMs = 300_000)
}
