package se.svenska.trainer.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Background ornament for a theme. Kept as an enum rather than arbitrary
 * drawing code so a theme stays data, and the renderer stays in one place.
 */
enum class Ornament { NONE, PAPER_GRAIN, SOFT_WASH, AURORA, BLOBS, HALFTONE }

/** Shape language. Cartoonish themes want visibly rounder, chunkier cards. */
enum class ShapeStyle { SHARP, NORMAL, ROUNDED }

data class AppTheme(
    val id: String,
    val name: String,
    val description: String,
    val dark: Boolean,
    val scheme: ColorScheme,
    val ornament: Ornament = Ornament.NONE,
    val shapes: ShapeStyle = ShapeStyle.NORMAL,
    /** Transcript body colour override; null uses onSurface. */
    val serifHeadings: Boolean = false,
)

// ---------------------------------------------------------------- light

private val Papper = AppTheme(
    id = "papper",
    name = "Papper",
    description = "Warm paper, ink blue, reading-first",
    dark = false,
    serifHeadings = true,
    ornament = Ornament.PAPER_GRAIN,
    scheme = lightColorScheme(
        primary = Color(0xFF1D4ED8),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFDCE7FF),
        onPrimaryContainer = Color(0xFF10285E),
        secondary = Color(0xFF6B5B3E),
        onSecondary = Color(0xFFFFFFFF),
        secondaryContainer = Color(0xFFEDE3CF),
        onSecondaryContainer = Color(0xFF3A3120),
        background = Color(0xFFFBF7EF),
        onBackground = Color(0xFF23201A),
        surface = Color(0xFFFFFCF6),
        onSurface = Color(0xFF23201A),
        surfaceVariant = Color(0xFFF0EADC),
        onSurfaceVariant = Color(0xFF5C5648),
        outline = Color(0xFFD6CEBC),
        outlineVariant = Color(0xFFE6DFCF),
    ),
)

private val Dagsljus = AppTheme(
    id = "dagsljus",
    name = "Dagsljus",
    description = "Crisp white, sky wash, neutral",
    dark = false,
    ornament = Ornament.SOFT_WASH,
    scheme = lightColorScheme(
        primary = Color(0xFF0E7490),
        onPrimary = Color(0xFFFFFFFF),
        primaryContainer = Color(0xFFCFF0F7),
        onPrimaryContainer = Color(0xFF06333F),
        secondary = Color(0xFF4F6472),
        secondaryContainer = Color(0xFFDCE7EE),
        onSecondaryContainer = Color(0xFF1E2E38),
        background = Color(0xFFFAFCFD),
        onBackground = Color(0xFF141A1D),
        surface = Color(0xFFFFFFFF),
        onSurface = Color(0xFF141A1D),
        surfaceVariant = Color(0xFFEEF3F6),
        onSurfaceVariant = Color(0xFF54646E),
        outline = Color(0xFFCBD8DF),
        outlineVariant = Color(0xFFE2EAEF),
    ),
)

// ----------------------------------------------------------------- dark

private val Black = AppTheme(
    id = "black",
    name = "Bläck",
    description = "Near-black, indigo, high contrast",
    dark = true,
    ornament = Ornament.NONE,
    shapes = ShapeStyle.SHARP,
    scheme = darkColorScheme(
        primary = Color(0xFF93B4FF),
        onPrimary = Color(0xFF0A1330),
        primaryContainer = Color(0xFF23346B),
        onPrimaryContainer = Color(0xFFDCE7FF),
        secondary = Color(0xFF9FA8C0),
        secondaryContainer = Color(0xFF2A3040),
        onSecondaryContainer = Color(0xFFDDE2F0),
        background = Color(0xFF0A0A0C),
        onBackground = Color(0xFFEDEDF0),
        surface = Color(0xFF141419),
        onSurface = Color(0xFFEDEDF0),
        surfaceVariant = Color(0xFF1E1E26),
        onSurfaceVariant = Color(0xFF9E9EAC),
        outline = Color(0xFF33333F),
        outlineVariant = Color(0xFF26262F),
    ),
)

private val Norrsken = AppTheme(
    id = "norrsken",
    name = "Norrsken",
    description = "Charcoal with an aurora wash",
    dark = true,
    ornament = Ornament.AURORA,
    scheme = darkColorScheme(
        primary = Color(0xFF5EEAD4),
        onPrimary = Color(0xFF002F2A),
        primaryContainer = Color(0xFF14514A),
        onPrimaryContainer = Color(0xFFB8FFF3),
        secondary = Color(0xFFC4B5FD),
        secondaryContainer = Color(0xFF3B2E63),
        onSecondaryContainer = Color(0xFFEAE0FF),
        background = Color(0xFF0B1014),
        onBackground = Color(0xFFE6EDF0),
        surface = Color(0xFF121A1F),
        onSurface = Color(0xFFE6EDF0),
        surfaceVariant = Color(0xFF1B252B),
        onSurfaceVariant = Color(0xFF95A6AF),
        outline = Color(0xFF2C3A42),
        outlineVariant = Color(0xFF222E35),
    ),
)

private val Tecknad = AppTheme(
    id = "tecknad",
    name = "Tecknad",
    description = "Chunky cartoon, coral pop",
    dark = true,
    ornament = Ornament.HALFTONE,
    shapes = ShapeStyle.ROUNDED,
    scheme = darkColorScheme(
        primary = Color(0xFFFF8A6B),
        onPrimary = Color(0xFF3B1000),
        primaryContainer = Color(0xFF7A2D14),
        onPrimaryContainer = Color(0xFFFFDBD0),
        secondary = Color(0xFFFFD166),
        secondaryContainer = Color(0xFF5C4415),
        onSecondaryContainer = Color(0xFFFFEFC7),
        background = Color(0xFF141B2E),
        onBackground = Color(0xFFF2F4FA),
        surface = Color(0xFF1E2740),
        onSurface = Color(0xFFF2F4FA),
        surfaceVariant = Color(0xFF2A3556),
        onSurfaceVariant = Color(0xFFA9B4D0),
        outline = Color(0xFF44547F),
        outlineVariant = Color(0xFF33406A),
    ),
)

private val Skog = AppTheme(
    id = "skog",
    name = "Skog",
    description = "Forest dark, moss accent, organic",
    dark = true,
    ornament = Ornament.BLOBS,
    shapes = ShapeStyle.ROUNDED,
    scheme = darkColorScheme(
        primary = Color(0xFF9BD68C),
        onPrimary = Color(0xFF0D2A08),
        primaryContainer = Color(0xFF2A4A22),
        onPrimaryContainer = Color(0xFFD4F5C9),
        secondary = Color(0xFFD8C89A),
        secondaryContainer = Color(0xFF44402A),
        onSecondaryContainer = Color(0xFFF2E9C9),
        background = Color(0xFF0C120D),
        onBackground = Color(0xFFE4EDE2),
        surface = Color(0xFF141C15),
        onSurface = Color(0xFFE4EDE2),
        surfaceVariant = Color(0xFF1D281E),
        onSurfaceVariant = Color(0xFF95A694),
        outline = Color(0xFF2E3D2F),
        outlineVariant = Color(0xFF243024),
    ),
)

val ALL_THEMES = listOf(Papper, Dagsljus, Black, Norrsken, Tecknad, Skog)

fun themeById(id: String): AppTheme = ALL_THEMES.firstOrNull { it.id == id } ?: Black

/**
 * Optional accent override. Each theme ships an accent chosen for its
 * background; these let that be swapped without breaking the rest of the
 * palette, since only the primary roles are replaced.
 */
data class Accent(val id: String, val name: String, val light: Color, val dark: Color)

val ACCENTS = listOf(
    Accent("default", "Theme default", Color.Unspecified, Color.Unspecified),
    Accent("blue", "Blue", Color(0xFF1D4ED8), Color(0xFF93B4FF)),
    Accent("teal", "Teal", Color(0xFF0F766E), Color(0xFF5EEAD4)),
    Accent("green", "Green", Color(0xFF15803D), Color(0xFF86EFAC)),
    Accent("amber", "Amber", Color(0xFFB45309), Color(0xFFFCD34D)),
    Accent("coral", "Coral", Color(0xFFDC2626), Color(0xFFFF8A6B)),
    Accent("violet", "Violet", Color(0xFF6D28D9), Color(0xFFC4B5FD)),
    Accent("pink", "Pink", Color(0xFFDB2777), Color(0xFFF9A8D4)),
)

fun accentById(id: String): Accent = ACCENTS.firstOrNull { it.id == id } ?: ACCENTS[0]

/** Applies an accent override to a theme's scheme, leaving everything else. */
fun AppTheme.withAccent(accent: Accent): ColorScheme {
    val c = if (dark) accent.dark else accent.light
    if (c == Color.Unspecified) return scheme
    return scheme.copy(
        primary = c,
        onPrimary = if (dark) Color(0xFF0D0D12) else Color.White,
        primaryContainer = if (dark) c.copy(alpha = 0.24f).compositeOverOpaque(scheme.surface)
                           else c.copy(alpha = 0.16f).compositeOverOpaque(scheme.surface),
        onPrimaryContainer = if (dark) c else scheme.onSurface,
    )
}

/** Flattens a translucent colour onto an opaque one, so container colours stay
 *  opaque and do not let list content show through. */
private fun Color.compositeOverOpaque(bg: Color): Color = Color(
    red = red * alpha + bg.red * (1 - alpha),
    green = green * alpha + bg.green * (1 - alpha),
    blue = blue * alpha + bg.blue * (1 - alpha),
    alpha = 1f,
)
