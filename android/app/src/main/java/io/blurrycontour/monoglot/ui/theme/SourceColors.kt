package io.blurrycontour.monoglot.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color

/**
 * Identity colours for sources.
 *
 * Deliberately not drawn from the theme. These stand for *which source*, and a
 * source has to keep its colour when the theme or the accent changes — the dot
 * used to take `primary` for Klartext, so it became whatever accent was set,
 * while three of the original sources shared the border grey and were not
 * distinguishable from each other at all.
 *
 * Eight hues, two steps each: one selected for a light surface and one for a
 * dark one, not an automatic flip. The set was validated against this app's
 * own surfaces (#FFFCF6 and #141419) for the lightness band, the chroma floor,
 * colour-vision-deficiency separation and normal-vision separation, in the
 * order given — the order is the safety mechanism, so slots must not be
 * reshuffled without re-validating. Worst adjacent pair: ΔE 9.1 protan light,
 * 8.4 dark, against a target of 8.
 *
 * Three of the light steps sit below 3:1 against the light surface, which is
 * allowed only where colour is not the sole carrier of identity. It is not:
 * every card names its source, in the headline under a day-named header and in
 * the meta line elsewhere, and every chip is labelled. The dot is a second
 * channel for the same fact, never the only one.
 */
private val LIGHT_STEPS = listOf(
    Color(0xFF2A78D6), // blue
    Color(0xFFEB6834), // orange
    Color(0xFF1BAF7A), // aqua
    Color(0xFFEDA100), // yellow
    Color(0xFFE87BA4), // magenta
    Color(0xFF008300), // green
    Color(0xFF4A3AA7), // violet
    Color(0xFFE34948), // red
)

private val DARK_STEPS = listOf(
    Color(0xFF3987E5),
    Color(0xFFD95926),
    Color(0xFF199E70),
    Color(0xFFC98500),
    Color(0xFFD55181),
    Color(0xFF008300),
    Color(0xFF9085E9),
    Color(0xFFE66767),
)

/**
 * Slot per source, fixed by slug.
 *
 * By slug rather than by position in the list: a source added or removed on the
 * server would otherwise shift every colour after it, and a source's colour is
 * only useful if it is the same one tomorrow.
 */
private val SLOTS = mapOf(
    "klartext" to 0,
    "8sidor" to 1,
    "vetenskap" to 2,
    "fof" to 3,
    "vetenskap_daglig" to 4,
    "radio_sweden_latt" to 6,
)

/**
 * Slot for a source this build has never heard of.
 *
 * Derived from the slug so it is at least stable across launches. It can
 * collide with a source that has an assigned slot; that is the price of not
 * renumbering everything else, and the name beside the dot still says which
 * source it is.
 */
private fun fallbackSlot(slug: String): Int {
    var h = 0
    for (c in slug) h = h * 31 + c.code
    return ((h % LIGHT_STEPS.size) + LIGHT_STEPS.size) % LIGHT_STEPS.size
}

@Composable
@ReadOnlyComposable
fun sourceColor(slug: String): Color {
    val slot = SLOTS[slug] ?: fallbackSlot(slug)
    return if (LocalAppTheme.current.dark) DARK_STEPS[slot] else LIGHT_STEPS[slot]
}
