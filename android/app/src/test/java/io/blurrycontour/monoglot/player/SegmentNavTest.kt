package io.blurrycontour.monoglot.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SegmentNavTest {

    private val starts = intArrayOf(0, 4_000, 9_000, 15_000)

    @Test
    fun `previous restarts the sentence you are inside`() {
        // Well into the third sentence: you meant to hear it again.
        assertEquals(9_000, SegmentNav.previous(starts, 12_000))
    }

    @Test
    fun `previous steps back when barely into a sentence`() {
        // Just arrived: you meant the one before.
        assertEquals(4_000, SegmentNav.previous(starts, 9_200))
    }

    @Test
    fun `previous never falls off the front`() {
        assertEquals(0, SegmentNav.previous(starts, 200))
        assertEquals(0, SegmentNav.previous(starts, 0))
    }

    @Test
    fun `next advances one sentence`() {
        assertEquals(9_000, SegmentNav.next(starts, 5_000))
        assertEquals(4_000, SegmentNav.next(starts, 0))
    }

    @Test
    fun `next reports nothing at the last sentence`() {
        assertEquals(SegmentNav.NONE, SegmentNav.next(starts, 16_000))
    }

    @Test
    fun `an episode with no transcript navigates nowhere`() {
        assertEquals(SegmentNav.NONE, SegmentNav.previous(intArrayOf(), 5_000))
        assertEquals(SegmentNav.NONE, SegmentNav.next(intArrayOf(), 5_000))
    }

    // Audio can run a moment before the first segment starts.
    @Test
    fun `positions before the first sentence have no previous`() {
        assertEquals(SegmentNav.NONE, SegmentNav.previous(intArrayOf(500, 4_000), 100))
    }
}
