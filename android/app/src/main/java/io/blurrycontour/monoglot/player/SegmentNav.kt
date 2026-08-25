package io.blurrycontour.monoglot.player

/**
 * Sentence navigation over a bare array of segment start times.
 *
 * The playback service holds a player, not a transcript: it has no TokenIndex
 * and no bundle to build one from. Rather than teach it about either, the
 * sentence boundaries travel with the media item as an array of milliseconds,
 * and this is the arithmetic both sides agree on.
 *
 * Milliseconds as integers, as everywhere else in this app.
 */
object SegmentNav {

    /**
     * How far into a sentence "previous" means restarting it rather than
     * stepping back one.
     *
     * The same rule the in-app transport uses: a moment in, you meant to hear
     * this line again; well in, you meant the line before.
     */
    const val RESTART_WINDOW_MS = 1_500

    /** Nothing to move to. */
    const val NONE = -1

    /** Index of the sentence playing at [positionMs], or -1 before the first. */
    fun indexAt(starts: IntArray, positionMs: Int): Int {
        var lo = 0
        var hi = starts.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (starts[mid] <= positionMs) {
                found = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return found
    }

    /** Where "previous sentence" should seek to, or [NONE]. */
    fun previous(starts: IntArray, positionMs: Int): Int {
        if (starts.isEmpty()) return NONE
        val i = indexAt(starts, positionMs)
        if (i < 0) return NONE
        // Well into this sentence: restart it rather than skipping back.
        if (positionMs - starts[i] > RESTART_WINDOW_MS) return starts[i]
        return if (i == 0) starts[0] else starts[i - 1]
    }

    /** Where "next sentence" should seek to, or [NONE] at the last one. */
    fun next(starts: IntArray, positionMs: Int): Int {
        if (starts.isEmpty()) return NONE
        val i = indexAt(starts, positionMs)
        val target = i + 1
        return if (target in starts.indices) starts[target] else NONE
    }
}
