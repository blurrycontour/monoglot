package se.svenska.trainer.ui.theme

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** The active theme, so screens can react to ornament and shape choices. */
val LocalAppTheme = staticCompositionLocalOf { themeById("black") }

/** Transcript text is large and generously spaced: it is read in motion, on a
 *  phone, by someone who is also listening. */
val TranscriptStyle = TextStyle(
    fontSize = 21.sp,
    lineHeight = 34.sp,
    fontWeight = FontWeight.Normal,
)

private fun shapesFor(style: ShapeStyle): Shapes = when (style) {
    ShapeStyle.SHARP -> Shapes(
        extraSmall = RoundedCornerShape(2.dp),
        small = RoundedCornerShape(4.dp),
        medium = RoundedCornerShape(6.dp),
        large = RoundedCornerShape(8.dp),
    )
    ShapeStyle.NORMAL -> Shapes()
    ShapeStyle.ROUNDED -> Shapes(
        extraSmall = RoundedCornerShape(10.dp),
        small = RoundedCornerShape(16.dp),
        medium = RoundedCornerShape(22.dp),
        large = RoundedCornerShape(28.dp),
        extraLarge = RoundedCornerShape(34.dp),
    )
}

private fun typographyFor(theme: AppTheme): Typography {
    val base = Typography()
    if (!theme.serifHeadings) return base
    // Papper is a reading theme; a serif display face makes the headers feel
    // like a page rather than a dashboard.
    return base.copy(
        headlineLarge = base.headlineLarge.copy(fontFamily = FontFamily.Serif),
        headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif),
        headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif),
        titleLarge = base.titleLarge.copy(fontFamily = FontFamily.Serif),
    )
}

@Composable
fun MonoglotTheme(
    themeId: String = "black",
    accentId: String = "default",
    content: @Composable () -> Unit,
) {
    val theme = themeById(themeId)
    val scheme = theme.withAccent(accentById(accentId))

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !theme.dark
                isAppearanceLightNavigationBars = !theme.dark
            }
        }
    }

    CompositionLocalProvider(LocalAppTheme provides theme) {
        MaterialTheme(
            colorScheme = scheme,
            typography = typographyFor(theme),
            shapes = shapesFor(theme.shapes),
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(scheme.background)
                    .themeOrnament(theme, scheme.primary, scheme.secondary),
            ) { content() }
        }
    }
}

/**
 * A small deterministic PRNG. Ornaments must not reshuffle on every
 * recomposition, so the scatter is seeded rather than random.
 */
private class Scatter(private var seed: Int) {
    fun next(): Float {
        seed = seed * 1103515245 + 12345
        return ((seed ushr 16) and 0x7FFF) / 32767f
    }
    fun range(a: Float, b: Float) = a + next() * (b - a)
}

/**
 * Background ornament. Low-contrast, because this sits behind a transcript
 * that has to stay readable, but dense enough to actually read as art: list
 * cards are opaque and cover most of the canvas, so a handful of large shapes
 * is nearly invisible in practice.
 */
private fun Modifier.themeOrnament(
    theme: AppTheme,
    primary: Color,
    secondary: Color,
): Modifier = when (theme.ornament) {

    Ornament.NONE -> this

    // Fine speckle, like paper stock.
    Ornament.PAPER_GRAIN -> drawBehind {
        val ink = Color(0xFF6B5B3E)
        val rng = Scatter(12345)
        repeat(2600) {
            drawCircle(
                ink.copy(alpha = rng.range(0.02f, 0.06f)),
                radius = rng.range(0.6f, 1.3f),
                center = Offset(rng.next() * size.width, rng.next() * size.height),
            )
        }
        // Short fibres, as in real paper stock.
        repeat(90) {
            val x = rng.next() * size.width
            val y = rng.next() * size.height
            val len = rng.range(6f, 22f)
            val ang = rng.range(0f, 360f)
            rotate(ang, Offset(x, y)) {
                drawLine(
                    ink.copy(alpha = 0.05f),
                    Offset(x - len / 2f, y), Offset(x + len / 2f, y),
                    strokeWidth = 0.9f,
                )
            }
        }
    }

    // Soft colour wash bleeding in from the top-left.
    Ornament.SOFT_WASH -> drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.14f), Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.05f),
                radius = size.width * 0.9f,
            )
        )
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(secondary.copy(alpha = 0.10f), Color.Transparent),
                center = Offset(size.width * 0.95f, size.height * 0.85f),
                radius = size.width * 0.8f,
            )
        )
        // Outlined circles drifting across, like light through water.
        val rng = Scatter(4242)
        repeat(22) {
            drawCircle(
                color = primary.copy(alpha = rng.range(0.05f, 0.11f)),
                radius = size.width * rng.range(0.02f, 0.10f),
                center = Offset(rng.next() * size.width, rng.next() * size.height),
                style = Stroke(width = 1.4f),
            )
        }
    }

    // Two overlapping bands, loosely northern-lights shaped.
    Ornament.AURORA -> drawBehind {
        drawRect(
            brush = Brush.linearGradient(
                colors = listOf(
                    primary.copy(alpha = 0.14f),
                    Color.Transparent,
                    secondary.copy(alpha = 0.10f),
                ),
                start = Offset(0f, 0f),
                end = Offset(size.width, size.height * 0.7f),
            )
        )
        val path = Path().apply {
            moveTo(0f, size.height * 0.16f)
            var x = 0f
            while (x <= size.width) {
                lineTo(x, size.height * 0.16f + sin(x / 190f) * 34f)
                x += 12f
            }
            lineTo(size.width, 0f)
            lineTo(0f, 0f)
            close()
        }
        drawPath(path, primary.copy(alpha = 0.05f))

        // Night sky: a deterministic star field, so it does not shimmer on
        // every recomposition, plus two ringed planets.
        val rng = Scatter(987654321)
        repeat(260) {
            val x = rng.next() * size.width
            val y = rng.next() * size.height
            val r = rng.range(0.6f, 2.3f)
            drawCircle(
                Color.White.copy(alpha = rng.range(0.06f, 0.22f)),
                radius = r,
                center = Offset(x, y),
            )
        }
        // Brighter stars with a cross flare, scattered down the whole page.
        val bright = List(9) { Pair(rng.next(), rng.next()) }
        bright.forEach { (fx, fy) ->
            val c = Offset(size.width * fx, size.height * fy)
            drawCircle(Color.White.copy(alpha = 0.30f), radius = 2.1f, center = c)
            drawLine(Color.White.copy(alpha = 0.16f),
                Offset(c.x - 9f, c.y), Offset(c.x + 9f, c.y), strokeWidth = 1f)
            drawLine(Color.White.copy(alpha = 0.16f),
                Offset(c.x, c.y - 9f), Offset(c.x, c.y + 9f), strokeWidth = 1f)
        }
        // A second, smaller planet lower down so the sky is not top-heavy.
        val p2 = Offset(size.width * 0.20f, size.height * 0.62f)
        val r2 = size.width * 0.045f
        drawCircle(secondary.copy(alpha = 0.10f), radius = r2, center = p2)
        rotate(14f, p2) {
            drawOval(
                color = secondary.copy(alpha = 0.10f),
                topLeft = Offset(p2.x - r2 * 1.9f, p2.y - r2 * 0.26f),
                size = androidx.compose.ui.geometry.Size(r2 * 3.8f, r2 * 0.52f),
                style = Stroke(width = 1.6f),
            )
        }
        // Planet with a tilted ring.
        val pc = Offset(size.width * 0.82f, size.height * 0.20f)
        val pr = size.width * 0.075f
        drawCircle(secondary.copy(alpha = 0.13f), radius = pr, center = pc)
        drawCircle(secondary.copy(alpha = 0.07f), radius = pr * 0.62f,
            center = Offset(pc.x - pr * 0.28f, pc.y - pr * 0.22f))
        rotate(-20f, pc) {
            drawOval(
                color = primary.copy(alpha = 0.13f),
                topLeft = Offset(pc.x - pr * 1.75f, pc.y - pr * 0.30f),
                size = androidx.compose.ui.geometry.Size(pr * 3.5f, pr * 0.60f),
                style = Stroke(width = 2f),
            )
        }
        // Smaller distant moon.
        drawCircle(primary.copy(alpha = 0.10f), radius = size.width * 0.032f,
            center = Offset(size.width * 0.14f, size.height * 0.86f))
    }

    // Forest floor: soft canopy shapes with a dense scatter of leaves.
    Ornament.BLOBS -> drawBehind {
        drawCircle(
            primary.copy(alpha = 0.06f),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.85f, size.height * 0.10f),
        )
        drawCircle(
            secondary.copy(alpha = 0.045f),
            radius = size.width * 0.45f,
            center = Offset(size.width * 0.05f, size.height * 0.84f),
        )

        // Each leaf is two mirrored quadratic curves with a midrib. Scattered
        // across the whole canvas so some are always visible between cards.
        val rng = Scatter(20260823)
        repeat(46) {
            val cx = rng.range(-0.05f, 1.05f) * size.width
            val cy = rng.range(-0.02f, 1.02f) * size.height
            val len = size.width * rng.range(0.045f, 0.105f)
            val wid = len * rng.range(0.34f, 0.52f)
            val deg = rng.range(0f, 360f)
            val tint = if (rng.next() > 0.65f) secondary else primary
            val alpha = rng.range(0.07f, 0.15f)
            rotate(deg, Offset(cx, cy)) {
                val leaf = Path().apply {
                    moveTo(cx, cy - len / 2f)
                    quadraticTo(cx + wid, cy, cx, cy + len / 2f)
                    quadraticTo(cx - wid, cy, cx, cy - len / 2f)
                    close()
                }
                drawPath(leaf, tint.copy(alpha = alpha))
                drawLine(
                    color = tint.copy(alpha = alpha * 1.5f),
                    start = Offset(cx, cy - len / 2f),
                    end = Offset(cx, cy + len / 2f),
                    strokeWidth = 1.1f,
                )
            }
        }
    }

    // Comic book: halftone dots plus a few speed-line bursts.
    Ornament.HALFTONE -> drawBehind {
        val step = 26f
        var y = 0f
        var row = 0
        while (y < size.height) {
            var x = if (row % 2 == 0) 0f else step / 2f
            while (x < size.width) {
                drawCircle(primary.copy(alpha = 0.035f), radius = 2.4f, center = Offset(x, y))
                x += step
            }
            y += step
            row++
        }
        // Radiating bursts, the comic panel cue.
        listOf(
            Triple(0.90f, 0.09f, size.width * 0.34f),
            Triple(0.08f, 0.55f, size.width * 0.26f),
            Triple(0.72f, 0.93f, size.width * 0.30f),
        ).forEach { (fx, fy, len) ->
            val c = Offset(size.width * fx, size.height * fy)
            repeat(12) { i ->
                val a = (i * 30f) * (PI / 180f).toFloat()
                drawLine(
                    color = secondary.copy(alpha = 0.055f),
                    start = Offset(c.x + cos(a) * len * 0.32f, c.y + sin(a) * len * 0.32f),
                    end = Offset(c.x + cos(a) * len, c.y + sin(a) * len),
                    strokeWidth = 2.5f,
                )
            }
        }
    }

    // Editorial hairline rules, like a ruled notebook seen at low contrast.
    Ornament.RULES -> drawBehind {
        var y = size.height * 0.06f
        while (y < size.height) {
            drawLine(
                color = primary.copy(alpha = 0.035f),
                start = Offset(0f, y),
                end = Offset(size.width, y),
                strokeWidth = 1f,
            )
            y += 46f
        }
        // A single accent margin rule, as on ruled paper.
        drawLine(
            color = primary.copy(alpha = 0.12f),
            start = Offset(size.width * 0.085f, 0f),
            end = Offset(size.width * 0.085f, size.height),
            strokeWidth = 1.5f,
        )
        // Scattered marginalia ticks, so the page is ruled rather than striped.
        val rng = Scatter(777)
        repeat(34) {
            val x = rng.range(0.10f, 0.98f) * size.width
            val y = rng.next() * size.height
            val len = rng.range(5f, 16f)
            drawLine(
                primary.copy(alpha = rng.range(0.05f, 0.12f)),
                Offset(x, y), Offset(x + len, y), strokeWidth = 1.4f,
            )
        }
    }
}

