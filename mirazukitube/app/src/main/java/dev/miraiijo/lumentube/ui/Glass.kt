package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * miryo's glass (« Verre miryo »), the one recipe of the suite: the same file, same values, in
 * miryo, miryo iptv and miryo tube. No blur (the Player POP can't afford it): only gradients.
 *
 *  - Nuit: an almost clear body, a soft light from the top-left, and a thin rim lit at the
 *    top-left and bottom-right corners (the light caught by a glass edge).
 *  - Jour: light comes from above. A white rim along the top edge, a hint of shade along the
 *    bottom one, a milky body a step lighter than the page, and a short soft shadow under it.
 *    (The night rim on a light page read as a grey outline broken at the corners: the white
 *    corners vanished on the light background, only the shaded sides were left.)
 *
 * Where it goes (every layout, every app): the round buttons, the buttons' idle state, the status
 * pill, the dock, the panels and setting cards of Réglages. Focus stays a solid capsule.
 */

/** Warm shade of Jour's glass (never a cold grey on the « blanc lune » charter). */
private val Shade = Color(0xFF3C2D14)

/** Jour's rim: white along the top, shade along the bottom. */
private val DayRim: Brush = Brush.verticalGradient(
    0f to Color.White.copy(alpha = 0.95f),
    0.3f to Color.White.copy(alpha = 0.45f),
    0.6f to Shade.copy(alpha = 0.05f),
    1f to Shade.copy(alpha = 0.18f),
)

/** The corner-lit rim, diagonal across whatever it strokes. [side] is the light left on the sides. */
fun glassRimBrush(corner: Color = Color.White, side: Color = Color.White.copy(alpha = 0.07f), mid: Color = Color.White.copy(alpha = 0.3f)): Brush =
    Brush.linearGradient(
        0f to corner, 0.16f to mid, 0.38f to side, 0.62f to side, 0.84f to mid, 1f to corner.copy(alpha = corner.alpha * 0.92f),
        start = Offset.Zero,
        end = Offset.Infinite,
    )

/** The rim as a stroke, for a Surface's border (Jour: lit from above; Nuit: lit at the corners). */
fun glassRim(width: Dp = 1.dp, light: Boolean = Palette.colors.isLight): BorderStroke =
    BorderStroke(width, if (light) DayRim else glassRimBrush())

/**
 * A glass panel: its body with a soft light, the rim, and by day a short shadow under it (drawn only
 * outside the panel, so the milky body stays clean). Kept to three draws for the Player POP's GPU:
 * the light is baked into the body's gradient (no second layer over the whole panel) and the shadow
 * is one path, both built once per size.
 */
fun Modifier.liquidGlass(shape: Shape, fill: Color = Palette.Glass, light: Boolean = Palette.colors.isLight): Modifier = this
    .drawWithCache {
        val outline = shape.createOutline(size, layoutDirection, this)
        val shadow = if (light) {
            val body = outline.toPath()
            val dropped = Path().apply { addPath(body, Offset(0f, 2.dp.toPx())) }
            Path().apply { op(dropped, body, PathOperation.Difference) }
        } else {
            null
        }
        val lit = if (light) {
            Brush.verticalGradient(0f to Color.White.copy(alpha = 0.42f).compositeOver(fill), 0.5f to Color.White.copy(alpha = 0.08f).compositeOver(fill), 1f to fill)
        } else {
            Brush.linearGradient(0f to Color.White.copy(alpha = 0.10f).compositeOver(fill), 0.45f to fill, start = Offset.Zero, end = Offset.Infinite)
        }
        onDrawBehind {
            shadow?.let { drawPath(it, ShadowColor) }
            drawOutline(outline, lit)
        }
    }
    .border(glassRim(1.dp, light), shape)

private val ShadowColor = Shade.copy(alpha = 0.12f)

/** A focused card's ring, lit at the corners like glass ([base]: the ring's own colour on the sides). */
fun glassFocusRing(width: Dp, base: Color): BorderStroke =
    BorderStroke(width, glassRimBrush(corner = Color.White, side = base, mid = Color.White.copy(alpha = 0.6f).compositeOver(base)))

private fun Outline.toPath(): Path = when (this) {
    is Outline.Rectangle -> Path().apply { addRect(rect) }
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Generic -> path
}

private fun Color.compositeOver(under: Color): Color {
    val a = alpha + under.alpha * (1 - alpha)
    if (a == 0f) return Color.Transparent
    return Color(
        (red * alpha + under.red * under.alpha * (1 - alpha)) / a,
        (green * alpha + under.green * under.alpha * (1 - alpha)) / a,
        (blue * alpha + under.blue * under.alpha * (1 - alpha)) / a,
        a,
    )
}
