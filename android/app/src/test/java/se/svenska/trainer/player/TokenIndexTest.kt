package se.svenska.trainer.player

import org.junit.Assert.assertEquals
import org.junit.Test
import se.svenska.trainer.data.Segment
import se.svenska.trainer.data.Token

/**
 * The binary search runs ten times a second during playback; an off-by-one here
 * shows up as the highlight sitting on the wrong word.
 */
class TokenIndexTest {

    private fun token(id: Int, segmentId: Int, start: Int, end: Int) =
        Token(id = id, segmentId = segmentId, idx = id, surface = "w$id",
            normalized = "w$id", startMs = start, endMs = end)

    private val segments = listOf(
        Segment(id = 100, idx = 0, startMs = 0, endMs = 1000, text = "one"),
        Segment(id = 101, idx = 1, startMs = 2000, endMs = 3000, text = "two"),
    )

    // Note the gap between 300 and 500: silence between words is real, and the
    // index must report "no active word" there rather than the nearest one.
    private val tokens = listOf(
        token(1, 100, 0, 300),
        token(2, 100, 500, 1000),
        token(3, 101, 2000, 2400),
        token(4, 101, 2400, 3000),
    )

    private val index = TokenIndex(tokens, segments)

    @Test
    fun `finds the token at an exact start boundary`() {
        assertEquals(0, index.tokenAt(0))
        assertEquals(1, index.tokenAt(500))
        assertEquals(2, index.tokenAt(2000))
    }

    @Test
    fun `finds the token inside its span`() {
        assertEquals(0, index.tokenAt(150))
        assertEquals(3, index.tokenAt(2700))
    }

    @Test
    fun `returns no token in the silence between words`() {
        assertEquals(-1, index.tokenAt(400))
    }

    @Test
    fun `returns no token before the first word or after the last`() {
        assertEquals(-1, TokenIndex(tokens.drop(1), segments).tokenAt(10))
        assertEquals(-1, index.tokenAt(9_999))
    }

    @Test
    fun `end boundary is inclusive`() {
        assertEquals(0, index.tokenAt(300))
        assertEquals(3, index.tokenAt(3000))
    }

    @Test
    fun `segment lookup never falls back to none once playing`() {
        assertEquals(0, index.segmentAt(0))
        assertEquals(0, index.segmentAt(1500))   // gap between sentences
        assertEquals(1, index.segmentAt(2500))
        assertEquals(1, index.segmentAt(99_999)) // past the end
    }

    @Test
    fun `tokens are grouped by segment`() {
        assertEquals(listOf(1, 2), index.tokensInSegment(0).map { it.id })
        assertEquals(listOf(3, 4), index.tokensInSegment(1).map { it.id })
        assertEquals(emptyList<Int>(), index.tokensInSegment(9).map { it.id })
    }

    @Test
    fun `empty index degrades gracefully`() {
        val empty = TokenIndex(emptyList(), emptyList())
        assertEquals(-1, empty.tokenAt(0))
        assertEquals(-1, empty.segmentAt(0))
    }
}
