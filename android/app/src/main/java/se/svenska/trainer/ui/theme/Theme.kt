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
fun SvenskaTheme(
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
        val path = androidx.compose.ui.graphics.Path().apply {
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
    }

    // Large soft organic shapes, low in the frame.
    Ornament.BLOBS -> drawBehind {
        drawCircle(
            primary.copy(alpha = 0.07f),
            radius = size.width * 0.55f,
            center = Offset(size.width * 0.85f, size.height * 0.12f),
        )
        drawCircle(
            secondary.copy(alpha = 0.05f),
            radius = size.width * 0.45f,
            center = Offset(size.width * 0.05f, size.height * 0.82f),
        )
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

