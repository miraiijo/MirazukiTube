package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.tv.material3.LocalContentColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Line icons drawn in code (no icon library): white by default, black on a focused control. */
enum class MiryoIcon {
    SEARCH, SETTINGS, SUN, PARTLY_CLOUDY, CLOUD, FOG, RAIN, SNOW, STORM,
    DICE, PLAY, PLUS, CHECK, HEART, STAR, REFRESH, CLOSE, MIC, EYE_OFF,
    CHEVRON_UP, CHEVRON_DOWN, INFO, HOME, LIBRARY, APPS, GAMEPAD, MORE,
    // miryo tube
    PAUSE, PIP, SUBTITLES, MUSIC, NEWS, LIVE, SHORTS, HISTORY, PLAYLIST, SUBSCRIPTIONS, REWIND, FORWARD, USER, THUMB_UP, TROPHY,
    // Thumbs: filled = active (liked / disliked), outline otherwise; comments bubble.
    THUMB_UP_OUTLINE, THUMB_DOWN, THUMB_DOWN_OUTLINE, COMMENT,
    // Chapters: three lines (the last shorter), no play triangle.
    CHAPTERS,
    // Apparence Auto (Jour / Nuit), as in miryo: a circle, half filled.
    CONTRAST,
    // Avatar glyphs
    MOON, BOLT, SMILE, CAT, FLAME, LEAF, GHOST, CROWN,
}

@Composable
fun Icon(icon: MiryoIcon, size: Dp = 18.dp, color: Color = LocalContentColor.current, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawIcon(icon, color) }
}

/** Weather emoji (from the weather client) → drawn icon. */
fun weatherIcon(emoji: String): MiryoIcon = when (emoji) {
    "☀️" -> MiryoIcon.SUN
    "🌤️" -> MiryoIcon.PARTLY_CLOUDY
    "☁️" -> MiryoIcon.CLOUD
    "🌫️" -> MiryoIcon.FOG
    "🌦️", "🌧️" -> MiryoIcon.RAIN
    "❄️" -> MiryoIcon.SNOW
    "⛈️" -> MiryoIcon.STORM
    else -> MiryoIcon.CLOUD
}

/** All drawing happens in a 24×24 grid scaled to the canvas. */
private fun DrawScope.drawIcon(icon: MiryoIcon, color: Color) {
    val u = size.minDimension / 24f
    fun p(x: Float, y: Float) = Offset(x * u, y * u)
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float, w: Float = 2f) =
        drawLine(color, p(x1, y1), p(x2, y2), strokeWidth = w * u, cap = StrokeCap.Round)
    fun ring(x: Float, y: Float, r: Float) = drawCircle(color, r * u, p(x, y), style = stroke)
    fun dot(x: Float, y: Float, r: Float) = drawCircle(color, r * u, p(x, y))
    fun cloud(dx: Float = 0f, dy: Float = 0f, s: Float = 1f) {
        fun c(x: Float, y: Float, r: Float) = drawCircle(color, r * s * u, p(dx + x * s, dy + y * s))
        c(8f, 14f, 4f)
        c(13.5f, 11f, 5f)
        c(17.5f, 14.5f, 3.5f)
        drawRoundRect(color, p(dx + 4f * s, dy + 14f * s), Size(15.5f * s * u, 4f * s * u), CornerRadius(2f * s * u))
    }
    fun rays(cx: Float, cy: Float, from: Float, to: Float, count: Int = 8) {
        repeat(count) { i ->
            val a = 2 * PI * i / count
            line(cx + from * cos(a).toFloat(), cy + from * sin(a).toFloat(), cx + to * cos(a).toFloat(), cy + to * sin(a).toFloat())
        }
    }
    // List, not vararg: Offset is an inline class (vararg of it is not allowed).
    fun polygon(points: List<Offset>, filled: Boolean) {
        val path = Path().apply {
            moveTo(points[0].x, points[0].y)
            points.drop(1).forEach { lineTo(it.x, it.y) }
            close()
        }
        if (filled) drawPath(path, color) else drawPath(path, color, style = stroke)
    }
    fun thumb(filled: Boolean) {
        if (filled) drawRoundRect(color, p(3f, 10f), Size(4f * u, 10f * u), CornerRadius(1f * u))
        else drawRoundRect(color, p(3.5f, 10.5f), Size(3f * u, 9f * u), CornerRadius(1f * u), style = stroke)
        polygon(listOf(p(9f, 10f), p(12.5f, 3.5f), p(14.5f, 4.5f), p(13.5f, 9.5f), p(20f, 9.5f), p(18.5f, 20f), p(9f, 20f)), filled = filled)
    }

    when (icon) {
        MiryoIcon.SEARCH -> {
            ring(10f, 10f, 6f)
            line(14.5f, 14.5f, 20f, 20f, 2.4f)
        }
        MiryoIcon.SETTINGS -> {
            ring(12f, 12f, 6.5f)
            ring(12f, 12f, 2.6f)
            repeat(8) { i ->
                val a = 2 * PI * i / 8
                line(12f + 7.2f * cos(a).toFloat(), 12f + 7.2f * sin(a).toFloat(), 12f + 9.6f * cos(a).toFloat(), 12f + 9.6f * sin(a).toFloat(), 3f)
            }
        }
        MiryoIcon.SUN -> {
            ring(12f, 12f, 4f)
            rays(12f, 12f, 6.8f, 9.2f)
        }
        MiryoIcon.PARTLY_CLOUDY -> {
            ring(8.5f, 8.5f, 3f)
            rays(8.5f, 8.5f, 5f, 6.8f)
            cloud(dx = 3f, dy = 3.5f, s = 0.82f)
        }
        MiryoIcon.CLOUD -> cloud(dy = -1f)
        MiryoIcon.FOG -> {
            line(4f, 8f, 20f, 8f)
            line(6f, 12f, 18f, 12f)
            line(4f, 16f, 20f, 16f)
        }
        MiryoIcon.RAIN -> {
            cloud(dy = -4f)
            line(8f, 18f, 7f, 21f)
            line(12f, 18f, 11f, 21f)
            line(16f, 18f, 15f, 21f)
        }
        MiryoIcon.SNOW -> {
            cloud(dy = -4f)
            dot(8f, 19.5f, 1.3f)
            dot(12f, 21f, 1.3f)
            dot(16f, 19.5f, 1.3f)
        }
        MiryoIcon.STORM -> {
            cloud(dy = -5f)
            polygon(listOf(p(12.5f, 14f), p(9f, 19f), p(12f, 19f), p(10.5f, 23f), p(15f, 17.5f), p(12f, 17.5f)), filled = true)
        }
        MiryoIcon.DICE -> {
            drawRoundRect(color, p(4f, 4f), Size(16f * u, 16f * u), CornerRadius(3.5f * u), style = stroke)
            dot(8.5f, 8.5f, 1.4f)
            dot(15.5f, 8.5f, 1.4f)
            dot(12f, 12f, 1.4f)
            dot(8.5f, 15.5f, 1.4f)
            dot(15.5f, 15.5f, 1.4f)
        }
        // Triangle shifted right so its visual centre (centroid) sits at 12.
        MiryoIcon.PLAY -> polygon(listOf(p(8f, 4.5f), p(20.5f, 12f), p(8f, 19.5f)), filled = true)
        MiryoIcon.PLUS -> {
            line(12f, 5f, 12f, 19f)
            line(5f, 12f, 19f, 12f)
        }
        MiryoIcon.CHECK -> {
            line(5f, 12.5f, 10f, 17.5f)
            line(10f, 17.5f, 19f, 7f)
        }
        MiryoIcon.HEART -> {
            dot(8.5f, 9f, 4.2f)
            dot(15.5f, 9f, 4.2f)
            polygon(listOf(p(4.6f, 10.5f), p(19.4f, 10.5f), p(12f, 19.5f)), filled = true)
        }
        MiryoIcon.STAR -> {
            val points = (0 until 10).map { i ->
                val r = if (i % 2 == 0) 9.5f else 4f
                val a = -PI / 2 + PI * i / 5
                p(12f + r * cos(a).toFloat(), 12.5f + r * sin(a).toFloat())
            }
            polygon(points, filled = true)
        }
        MiryoIcon.REFRESH -> {
            drawArc(color, 30f, 300f, false, p(5f, 5f), Size(14f * u, 14f * u), style = stroke)
            polygon(listOf(p(19.5f, 4f), p(19.5f, 10f), p(13.5f, 10f)), filled = true)
        }
        MiryoIcon.CLOSE -> {
            line(6f, 6f, 18f, 18f)
            line(18f, 6f, 6f, 18f)
        }
        MiryoIcon.MIC -> {
            drawRoundRect(color, p(9f, 3f), Size(6f * u, 11f * u), CornerRadius(3f * u), style = stroke)
            drawArc(color, 0f, 180f, false, p(5.5f, 7.5f), Size(13f * u, 10f * u), style = stroke)
            line(12f, 17.5f, 12f, 21f)
        }
        MiryoIcon.INFO -> {
            ring(12f, 12f, 9f)
            line(12f, 11f, 12f, 17f)
            dot(12f, 7.8f, 1.3f)
        }
        MiryoIcon.HOME -> {
            polygon(listOf(p(3.5f, 11f), p(12f, 3.5f), p(20.5f, 11f)), filled = false)
            line(6f, 10f, 6f, 20f); line(18f, 10f, 18f, 20f); line(6f, 20f, 18f, 20f)
            line(10.5f, 20f, 10.5f, 15f); line(10.5f, 15f, 13.5f, 15f); line(13.5f, 15f, 13.5f, 20f)
        }
        MiryoIcon.MORE -> { dot(5f, 12f, 2f); dot(12f, 12f, 2f); dot(19f, 12f, 2f) }
        MiryoIcon.GAMEPAD -> {
            drawRoundRect(color, p(2.5f, 7f), Size(19f * u, 11f * u), CornerRadius(5.5f * u), style = stroke)
            line(7f, 10.5f, 7f, 14.5f); line(5f, 12.5f, 9f, 12.5f)
            dot(15.5f, 11.3f, 1.2f); dot(17.8f, 13.6f, 1.2f)
        }
        MiryoIcon.APPS -> {
            listOf(4f to 4f, 13.5f to 4f, 4f to 13.5f, 13.5f to 13.5f).forEach { (x, y) ->
                drawRoundRect(color, p(x, y), Size(6.5f * u, 6.5f * u), CornerRadius(1.8f * u), style = stroke)
            }
        }
        MiryoIcon.LIBRARY -> {
            drawRoundRect(color, p(4f, 9f), Size(16f * u, 11f * u), CornerRadius(2f * u), style = stroke)
            line(6f, 6f, 18f, 6f)
            line(8f, 3f, 16f, 3f)
        }
        MiryoIcon.CHEVRON_UP -> { line(6f, 15f, 12f, 9f); line(12f, 9f, 18f, 15f) }
        MiryoIcon.CHEVRON_DOWN -> { line(6f, 9f, 12f, 15f); line(12f, 15f, 18f, 9f) }
        MiryoIcon.MOON -> {
            // Crescent: a disc minus an offset disc (exact tips, centred in the grid: 4–20.7 × 3.4–20.6).
            // The former outline stopped its two arcs at mismatched angles: a flat, cut-off lower tip.
            fun disc(x: Float, y: Float, r: Float) = Path().apply { addOval(Rect(p(x - r, y - r), p(x + r, y + r))) }
            drawPath(Path().apply { op(disc(12.5f, 12f, 8.5f), disc(17f, 8.5f, 7f), PathOperation.Difference) }, color)
        }
        MiryoIcon.BOLT -> polygon(listOf(p(13.5f, 2.5f), p(6f, 13.5f), p(11.5f, 13.5f), p(10f, 21.5f), p(18f, 10f), p(12.5f, 10f)), filled = true)
        MiryoIcon.SMILE -> {
            ring(12f, 12f, 9f)
            dot(8.8f, 9.8f, 1.3f)
            dot(15.2f, 9.8f, 1.3f)
            drawArc(color, 20f, 140f, false, p(7f, 7f), Size(10f * u, 10f * u), style = stroke)
        }
        MiryoIcon.CAT -> {
            polygon(listOf(p(5f, 3.5f), p(9.5f, 7.5f), p(14.5f, 7.5f), p(19f, 3.5f), p(19.5f, 13.5f), p(12f, 20.5f), p(4.5f, 13.5f)), filled = true)
        }
        MiryoIcon.FLAME -> {
            polygon(listOf(p(12f, 2.5f), p(17.5f, 10f), p(18.5f, 15f), p(15f, 21f), p(9f, 21f), p(5.5f, 15f), p(7f, 9.5f), p(10f, 12f)), filled = true)
        }
        MiryoIcon.LEAF -> {
            polygon(listOf(p(4f, 20f), p(5f, 11f), p(11f, 5f), p(20f, 4f), p(19f, 13f), p(13f, 19f)), filled = true)
            line(4f, 20f, 15f, 9f, 1.5f)
        }
        MiryoIcon.GHOST -> {
            drawArc(color, 180f, 180f, true, p(5f, 2f), Size(14f * u, 14f * u))
            drawRoundRect(color, p(5f, 9f), Size(14f * u, 10.5f * u), CornerRadius(0f))
            dot(7.5f, 19.5f, 2.5f); dot(12f, 19.5f, 2.5f); dot(16.5f, 19.5f, 2.5f)
        }
        MiryoIcon.CROWN -> polygon(listOf(p(4f, 18f), p(3f, 7f), p(8.5f, 11.5f), p(12f, 5f), p(15.5f, 11.5f), p(21f, 7f), p(20f, 18f)), filled = true)
        MiryoIcon.PAUSE -> {
            drawRoundRect(color, p(6f, 4.5f), Size(4f * u, 15f * u), CornerRadius(1.2f * u))
            drawRoundRect(color, p(14f, 4.5f), Size(4f * u, 15f * u), CornerRadius(1.2f * u))
        }
        MiryoIcon.PIP -> {
            drawRoundRect(color, p(3f, 5f), Size(18f * u, 14f * u), CornerRadius(2.5f * u), style = stroke)
            drawRoundRect(color, p(11.5f, 11.5f), Size(7f * u, 5f * u), CornerRadius(1f * u))
        }
        MiryoIcon.SUBTITLES -> {
            drawRoundRect(color, p(3f, 5f), Size(18f * u, 14f * u), CornerRadius(3f * u), style = stroke)
            line(6.5f, 12f, 10f, 12f); line(12.5f, 12f, 17.5f, 12f)
            line(6.5f, 15.5f, 13f, 15.5f); line(15.5f, 15.5f, 17.5f, 15.5f)
        }
        MiryoIcon.MUSIC -> {
            line(9f, 18f, 9f, 5f); line(9f, 5f, 19f, 3.5f); line(19f, 3.5f, 19f, 16f)
            dot(6.5f, 18f, 2.8f); dot(16.5f, 16f, 2.8f)
        }
        MiryoIcon.NEWS -> {
            drawRoundRect(color, p(3.5f, 4.5f), Size(17f * u, 15f * u), CornerRadius(2.5f * u), style = stroke)
            drawRoundRect(color, p(6.5f, 8f), Size(5f * u, 4.5f * u), CornerRadius(0.8f * u))
            line(14f, 8.5f, 17.5f, 8.5f); line(14f, 12f, 17.5f, 12f); line(6.5f, 16f, 17.5f, 16f)
        }
        MiryoIcon.LIVE -> {
            dot(12f, 12f, 2.6f)
            drawArc(color, -50f, 100f, false, p(6.5f, 6.5f), Size(11f * u, 11f * u), style = stroke)
            drawArc(color, 130f, 100f, false, p(6.5f, 6.5f), Size(11f * u, 11f * u), style = stroke)
            drawArc(color, -50f, 100f, false, p(2.5f, 2.5f), Size(19f * u, 19f * u), style = stroke)
            drawArc(color, 130f, 100f, false, p(2.5f, 2.5f), Size(19f * u, 19f * u), style = stroke)
        }
        // A phone-shaped frame with a play triangle: the vertical videos.
        MiryoIcon.SHORTS -> {
            drawRoundRect(color, p(6.5f, 2.5f), Size(11f * u, 19f * u), CornerRadius(3f * u), style = stroke)
            polygon(listOf(p(10.5f, 9f), p(15f, 12f), p(10.5f, 15f)), filled = true)
        }
        MiryoIcon.HISTORY -> {
            ring(12f, 12f, 8.5f)
            line(12f, 7f, 12f, 12f); line(12f, 12f, 15.5f, 14f)
        }
        MiryoIcon.PLAYLIST -> {
            line(4f, 6f, 16f, 6f); line(4f, 11f, 16f, 11f); line(4f, 16f, 11f, 16f)
            polygon(listOf(p(14f, 13.5f), p(20.5f, 17f), p(14f, 20.5f)), filled = true)
        }
        MiryoIcon.SUBSCRIPTIONS -> {
            line(6f, 3.5f, 18f, 3.5f); line(4.5f, 6.5f, 19.5f, 6.5f)
            drawRoundRect(color, p(3f, 9.5f), Size(18f * u, 11f * u), CornerRadius(2.5f * u), style = stroke)
            polygon(listOf(p(10.5f, 12.5f), p(14.5f, 15f), p(10.5f, 17.5f)), filled = true)
        }
        MiryoIcon.REWIND -> {
            polygon(listOf(p(11.5f, 6f), p(3.5f, 12f), p(11.5f, 18f)), filled = true)
            polygon(listOf(p(20f, 6f), p(12f, 12f), p(20f, 18f)), filled = true)
        }
        MiryoIcon.FORWARD -> {
            polygon(listOf(p(12.5f, 6f), p(20.5f, 12f), p(12.5f, 18f)), filled = true)
            polygon(listOf(p(4f, 6f), p(12f, 12f), p(4f, 18f)), filled = true)
        }
        MiryoIcon.USER -> {
            ring(12f, 8.5f, 4f)
            drawArc(color, 200f, 140f, false, p(4.5f, 13.5f), Size(15f * u, 12f * u), style = stroke)
        }
        MiryoIcon.THUMB_UP -> thumb(filled = true)
        MiryoIcon.THUMB_UP_OUTLINE -> thumb(filled = false)
        // The same thumb upside down.
        MiryoIcon.THUMB_DOWN -> scale(1f, -1f) { thumb(filled = true) }
        MiryoIcon.THUMB_DOWN_OUTLINE -> scale(1f, -1f) { thumb(filled = false) }
        MiryoIcon.CONTRAST -> {
            ring(12f, 12f, 8f)
            polygon((0..24).map { i -> val a = Math.toRadians(90.0 + 180.0 * i / 24); p(12f + 8f * cos(a).toFloat(), 12f + 8f * sin(a).toFloat()) }, filled = true)
        }
        MiryoIcon.CHAPTERS -> {
            line(4f, 6f, 20f, 6f); line(4f, 12f, 20f, 12f); line(4f, 18f, 14f, 18f)
        }
        MiryoIcon.COMMENT -> {
            drawRoundRect(color, p(3.5f, 4f), Size(17f * u, 12f * u), CornerRadius(3f * u), style = stroke)
            polygon(listOf(p(7.5f, 15.5f), p(7.5f, 20.5f), p(12.5f, 15.5f)), filled = true)
            line(7.5f, 8.5f, 16.5f, 8.5f)
            line(7.5f, 11.5f, 13.5f, 11.5f)
        }
        MiryoIcon.TROPHY -> {
            drawRoundRect(color, p(7f, 3.5f), Size(10f * u, 8f * u), CornerRadius(4f * u))
            drawRoundRect(color, p(7f, 3.5f), Size(10f * u, 4f * u), CornerRadius(0f))
            line(12f, 11.5f, 12f, 16.5f, 2.4f)
            drawRoundRect(color, p(7.5f, 17f), Size(9f * u, 3f * u), CornerRadius(1f * u))
            drawArc(color, 90f, 180f, false, p(3.5f, 4.5f), Size(6f * u, 6f * u), style = stroke)
            drawArc(color, -90f, 180f, false, p(14.5f, 4.5f), Size(6f * u, 6f * u), style = stroke)
        }
        MiryoIcon.EYE_OFF -> {
            drawOval(color, p(3f, 7f), Size(18f * u, 10f * u), style = stroke)
            ring(12f, 12f, 2.5f)
            line(4f, 4f, 20f, 20f)
        }
    }
}
