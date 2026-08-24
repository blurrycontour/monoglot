package io.blurrycontour.monoglot.player

import io.blurrycontour.monoglot.data.Segment
import io.blurrycontour.monoglot.data.Token

/**
 * Time-sorted lookup over an item's tokens and segments.
 *
 * The player asks "which word is playing?" ten times a second. Scanning the
 * array linearly, or asking the server, would both be wrong: this binary
 * searches an in-memory array that was already sorted by the API.
 */
class TokenIndex(val tokens: List<Token>, val segments: List<Segment>) {

    private val tokenStarts: IntArray = IntArray(tokens.size) { tokens[it].startMs }
    private val segmentStarts: IntArray = IntArray(segments.size) { segments[it].startMs }

    /** Index of the token playing at [positionMs], or -1 between words. */
    fun tokenAt(positionMs: Int): Int {
        val i = floorIndex(tokenStarts, positionMs)
        if (i < 0) return -1
        // floorIndex found the last token starting at or before the position;
        // it is only the active one if the position is still inside it.
        return if (positionMs <= tokens[i].endMs) i else -1
    }

    /**
     * Index of the segment containing [positionMs]. Unlike tokens, this never
     * returns -1 once playback has started: the reveal control needs a
     * sentence to reveal even in the silence between two of them.
     */
    fun segmentAt(positionMs: Int): Int {
        if (segments.isEmpty()) return -1
        val i = floorIndex(segmentStarts, positionMs)
        return if (i < 0) 0 else i
    }

    fun segmentForToken(tokenIdx: Int): Int {
        if (tokenIdx < 0 || tokenIdx >= tokens.size) return -1
        val segId = tokens[tokenIdx].segmentId
        return segments.indexOfFirst { it.id == segId }
    }

    /** Tokens belonging to segment [segmentIdx], for rendering one sentence. */
    fun tokensInSegment(segmentIdx: Int): List<Token> {
        if (segmentIdx < 0 || segmentIdx >= segments.size) return emptyList()
        val segId = segments[segmentIdx].id
        return tokens.filter { it.segmentId == segId }
    }

    /** Largest i with values[i] <= target, or -1 if target precedes them all. */
    private fun floorIndex(values: IntArray, target: Int): Int {
        var lo = 0
        var hi = values.size - 1
        var result = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (values[mid] <= target) {
                result = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return result
    }
}
