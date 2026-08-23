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
 * Background ornament. Every treatment is deliberately low-contrast: this sits
 * behind a transcript that has to stay readable, so it may add character but
 * never compete for attention.
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
        var seed = 12345
        repeat(1400) {
            seed = seed * 1103515245 + 12345
            val x = ((seed ushr 16) % size.width.toInt().coerceAtLeast(1)).toFloat()
            seed = seed * 1103515245 + 12345
            val y = ((seed ushr 16) % size.height.toInt().coerceAtLeast(1)).toFloat()
            drawCircle(ink.copy(alpha = 0.035f), radius = 0.9f, center = Offset(x, y))
        }
    }

    // Soft colour wash bleeding in from the top-left.
    Ornament.SOFT_WASH -> drawBehind {
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(primary.copy(alpha = 0.12f), Color.Transparent),
                center = Offset(size.width * 0.1f, size.height * 0.05f),
                radius = size.width * 0.9f,
            )
        )
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
        var seed = 987654321
        fun rnd(): Float {
            seed = seed * 1103515245 + 12345
            return ((seed ushr 16) and 0x7FFF) / 32767f
        }
        repeat(90) {
            val x = rnd() * size.width
            val y = rnd() * size.height
            val r = 0.7f + rnd() * 1.5f
            drawCircle(Color.White.copy(alpha = 0.05f + rnd() * 0.09f), radius = r, center = Offset(x, y))
        }
        // A few brighter stars with cross flare.
        listOf(0.18f to 0.28f, 0.77f to 0.12f, 0.62f to 0.71f).forEach { (fx, fy) ->
            val c = Offset(size.width * fx, size.height * fy)
            drawCircle(Color.White.copy(alpha = 0.20f), radius = 1.7f, center = c)
            drawLine(Color.White.copy(alpha = 0.10f),
                Offset(c.x - 7f, c.y), Offset(c.x + 7f, c.y), strokeWidth = 1f)
            drawLine(Color.White.copy(alpha = 0.10f),
                Offset(c.x, c.y - 7f), Offset(c.x, c.y + 7f), strokeWidth = 1f)
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

    // Forest: soft canopy shapes plus scattered leaves in the accent colour.
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

        // Leaves. Each is two mirrored quadratic curves with a midrib, rotated
        // by a per-leaf angle so the scatter does not read as a pattern.
        val leaves = listOf(
            Triple(0.12f, 0.06f, -22f), Triple(0.84f, 0.30f, 34f),
            Triple(0.22f, 0.46f, 12f), Triple(0.72f, 0.62f, -48f),
            Triple(0.10f, 0.74f, 58f), Triple(0.88f, 0.88f, -14f),
            Triple(0.44f, 0.94f, 26f), Triple(0.60f, 0.16f, -66f),
        )
        leaves.forEach { (fx, fy, deg) ->
            val cx = size.width * fx
            val cy = size.height * fy
            val len = size.width * 0.085f
            val wid = len * 0.46f
            rotate(deg, Offset(cx, cy)) {
                val leaf = Path().apply {
                    moveTo(cx, cy - len / 2f)
                    quadraticTo(cx + wid, cy, cx, cy + len / 2f)
                    quadraticTo(cx - wid, cy, cx, cy - len / 2f)
                    close()
                }
                drawPath(leaf, primary.copy(alpha = 0.075f))
                drawLine(
                    color = primary.copy(alpha = 0.11f),
                    start = Offset(cx, cy - len / 2f),
                    end = Offset(cx, cy + len / 2f),
                    strokeWidth = 1.1f,
                )
            }
        }
    }

    // Comic halftone dots, coarse and regular.
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
    }
}

