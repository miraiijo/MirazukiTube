package dev.miraiijo.miryotube.ui

import android.content.Context
import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.edit
import androidx.tv.material3.Text

/*
 * « Aides touches »: the suite's one way of saying which remote keys to use (same file in miryo,
 * miryo iptv and miryo tube, only the package and the Palette accessors differ). A pill in the
 * bottom right corner, inside the safe zone, each key drawn (never a font glyph: the Freebox
 * Player POP's font lacks the arrows) and a short verb. Never focusable.
 * It shows when the screen opens and fades after 4 s; a hint whose key was used goes for good on
 * that screen; after 3 visits the screen shows none. Kids and guests always see them.
 */

/** The remote's keys a hint can show. */
enum class RemoteKey { UP, DOWN, LEFT, RIGHT, UP_DOWN, LEFT_RIGHT, OK, OK_LONG, BACK, MENU, PLAY_PAUSE }

@Immutable
data class KeyHint(val key: RemoteKey, val label: String)

/**
 * [PAGE]: glass of the theme, over a page. [ART]: dark glass, over a video or a picture (day and
 * night). [COMPACT]: dark, smaller and in a column, when the picture is in the middle (Shorts).
 * [INLINE]: no pill, in a dialog's footer (left of its buttons).
 */
enum class HintStyle { PAGE, ART, COMPACT, INLINE }

object KeyHints {
    private const val PREFS = "key_hints"
    private const val ENABLED = "enabled"
    const val MAX_HINTS = 3
    const val MAX_VISITS = 3
    const val SHOW_MS = 4_000L

    /** A key pressed (down), told by MainActivity: the hints whose key it is go away. */
    @Immutable
    data class Press(val keyCode: Int, val repeat: Boolean, val serial: Long)

    var pressed by mutableStateOf<Press?>(null)
        private set
    private var serial = 0L

    fun onKey(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN) pressed = Press(event.keyCode, event.repeatCount > 0, ++serial)
    }

    /** miryo's choice when miryo is there (Réglages › Affichage), else this app's own. */
    fun enabled(context: Context): Boolean =
        dev.miraiijo.miryotube.link.MiryoLook.current?.keyHints ?: prefs(context).getBoolean(ENABLED, true)

    /** This app's own choice (shown only without miryo). */
    fun ownEnabled(context: Context): Boolean = prefs(context).getBoolean(ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) = prefs(context).edit { putBoolean(ENABLED, on) }

    /** Visits and used keys forgotten: every screen shows its hints again. */
    fun reset(context: Context) {
        val on = ownEnabled(context)
        prefs(context).edit { clear(); putBoolean(ENABLED, on) }
    }

    internal fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    internal fun matches(key: RemoteKey, press: Press): Boolean {
        val code = press.keyCode
        val ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER || code == KeyEvent.KEYCODE_BUTTON_A
        return when (key) {
            RemoteKey.UP -> code == KeyEvent.KEYCODE_DPAD_UP
            RemoteKey.DOWN -> code == KeyEvent.KEYCODE_DPAD_DOWN
            RemoteKey.LEFT -> code == KeyEvent.KEYCODE_DPAD_LEFT
            RemoteKey.RIGHT -> code == KeyEvent.KEYCODE_DPAD_RIGHT
            RemoteKey.UP_DOWN -> code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN
            RemoteKey.LEFT_RIGHT -> code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT
            RemoteKey.OK -> ok && !press.repeat
            RemoteKey.OK_LONG -> ok && press.repeat || code == KeyEvent.KEYCODE_MENU
            RemoteKey.BACK -> code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_B
            RemoteKey.MENU -> code == KeyEvent.KEYCODE_MENU
            RemoteKey.PLAY_PAUSE -> code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY || code == KeyEvent.KEYCODE_MEDIA_PAUSE
        }
    }
}

/**
 * The hints of [screen] (a stable name: visits and used keys are kept per screen), [MAX_HINTS] at
 * most, the most useful first. The caller places it (bottom right, inside the safe zone).
 * [always]: kids and guests, no limit of visits and nothing remembered. [enabled]: false hides it
 * (the screen isn't really on show yet, a panel covers the corner…).
 */
@Composable
fun KeyHintBar(
    screen: String,
    hints: List<KeyHint>,
    modifier: Modifier = Modifier,
    style: HintStyle = HintStyle.PAGE,
    always: Boolean = false,
    enabled: Boolean = true,
) {
    val context = LocalContext.current
    val on = remember { KeyHints.enabled(context) }
    if (!on || hints.isEmpty()) return
    val prefs = remember { KeyHints.prefs(context) }
    // Counted once per opening of the screen.
    val allowed = remember(screen) {
        val visits = prefs.getInt("visits:$screen", 0)
        if (!always) prefs.edit { putInt("visits:$screen", visits + 1) }
        always || visits < KeyHints.MAX_VISITS
    }
    if (!allowed) return
    val used = remember(screen) {
        mutableStateListOf<String>().apply { if (!always) addAll(prefs.getStringSet("used:$screen", emptySet()).orEmpty()) }
    }
    // The key that opened the screen doesn't count.
    val since = remember(screen) { KeyHints.pressed?.serial ?: 0L }
    val press = KeyHints.pressed
    LaunchedEffect(press) {
        if (press == null || press.serial <= since) return@LaunchedEffect
        val hit = hints.filter { it.key.name !in used && KeyHints.matches(it.key, press) }
        if (hit.isEmpty()) return@LaunchedEffect
        used += hit.map { it.key.name }
        if (!always) prefs.edit { putStringSet("used:$screen", used.toSet()) }
    }
    var shown by remember(screen) { mutableStateOf(false) }
    LaunchedEffect(screen) {
        shown = true
        kotlinx.coroutines.delay(KeyHints.SHOW_MS)
        shown = false
    }
    val left = hints.take(KeyHints.MAX_HINTS).filter { it.key.name !in used }
    // Économes: a plain fade, nothing that moves.
    val reduced = remember { dev.miraiijo.miryotube.system.LowEnd.reduced(context) }
    val enter = if (reduced) fadeIn(tween(200)) else fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 3 }
    val exit = if (reduced) fadeOut(tween(200)) else fadeOut(tween(450)) + slideOutVertically(tween(450)) { it / 3 }
    // Kept while fading out, so the pill doesn't empty first.
    val last = remember(screen) { arrayOf(left) }
    if (left.isNotEmpty()) last[0] = left
    AnimatedVisibility(visible = shown && enabled && left.isNotEmpty(), enter = enter, exit = exit, modifier = modifier) {
        HintPill(last[0], style)
    }
}

@Composable
private fun HintPill(hints: List<KeyHint>, style: HintStyle) {
    val compact = style == HintStyle.COMPACT
    val onArt = style == HintStyle.ART || compact
    val text = if (onArt) Color.White.copy(alpha = 0.80f) else Palette.TextSecondary
    val fontSize = if (compact) 12.sp else Dimens.TextMin
    val shape = if (compact) RoundedCornerShape(12.dp) else RoundedCornerShape(50)
    val reduced = dev.miraiijo.miryotube.system.LowEnd.reduced()
    val pill = when (style) {
        HintStyle.INLINE -> Modifier
        HintStyle.PAGE -> Modifier.liquidGlass(shape, shadow = !reduced).padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp)
        else -> Modifier
            .background(Color(0xFF0A0A10).copy(alpha = if (reduced) 0.85f else 0.60f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.16f), shape)
            .padding(if (compact) androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp) else androidx.compose.foundation.layout.PaddingValues(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 6.dp))
    }
    if (compact) {
        Column(pill, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (hint in hints) HintItem(hint, onArt, text, fontSize, compact = true)
        }
    } else {
        Row(pill, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
            for (hint in hints) HintItem(hint, onArt, text, fontSize, compact = false)
        }
    }
}

@Composable
private fun HintItem(hint: KeyHint, onArt: Boolean, text: Color, fontSize: TextUnit, compact: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        KeyCap(hint.key, onArt, compact)
        Text(hint.label, color = text, fontSize = fontSize, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

/** One key, drawn: arrows, Menu and Lecture / Pause as lines; OK and Retour written, as on the remote. */
@Composable
fun KeyCap(key: RemoteKey, onArt: Boolean = false, compact: Boolean = false) {
    val height: Dp = if (compact) 20.dp else 24.dp
    val shape = RoundedCornerShape(if (compact) 6.dp else 8.dp)
    val fill = when {
        onArt -> Color.White.copy(alpha = 0.16f)
        Palette.colors.isLight -> Color.White
        else -> Color.White.copy(alpha = 0.12f)
    }
    val ink = if (onArt) Color.White else Palette.TextPrimary
    Box(
        Modifier.height(height).defaultMinSize(minWidth = height).background(fill, shape).padding(horizontal = if (compact) 4.dp else 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        val label = if (compact) 10.sp else 11.sp
        when (key) {
            RemoteKey.OK -> Text("OK", color = ink, fontSize = label, fontWeight = FontWeight.Bold, maxLines = 1)
            RemoteKey.OK_LONG -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("OK", color = ink, fontSize = label, fontWeight = FontWeight.Bold, maxLines = 1)
                Text("long", color = ink.copy(alpha = 0.75f), fontSize = label * 0.8f, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            RemoteKey.BACK -> Text("Retour", color = ink, fontSize = label, fontWeight = FontWeight.Bold, maxLines = 1)
            else -> {
                val wide = key == RemoteKey.UP_DOWN || key == RemoteKey.LEFT_RIGHT
                val glyph = if (compact) 13.dp else 16.dp
                Canvas(Modifier.width(if (wide) glyph * 1.5f else glyph).height(glyph)) { drawKey(key, ink) }
            }
        }
    }
}

/** In a 24-high grid (36 wide for the pairs), like the suite's icons. */
private fun DrawScope.drawKey(key: RemoteKey, color: Color) {
    val u = size.height / 24f
    val stroke = Stroke(width = 2.4f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun line(vararg points: Pair<Float, Float>) {
        val path = Path()
        points.forEachIndexed { i, (x, y) -> if (i == 0) path.moveTo(x * u, y * u) else path.lineTo(x * u, y * u) }
        drawPath(path, color, style = stroke)
    }
    when (key) {
        RemoteKey.UP -> line(6f to 15f, 12f to 9f, 18f to 15f)
        RemoteKey.DOWN -> line(6f to 9f, 12f to 15f, 18f to 9f)
        RemoteKey.LEFT -> line(15f to 6f, 9f to 12f, 15f to 18f)
        RemoteKey.RIGHT -> line(9f to 6f, 15f to 12f, 9f to 18f)
        RemoteKey.UP_DOWN -> {
            line(4f to 14f, 10f to 8f, 16f to 14f)
            line(20f to 10f, 26f to 16f, 32f to 10f)
        }
        RemoteKey.LEFT_RIGHT -> {
            line(13f to 6f, 7f to 12f, 13f to 18f)
            line(23f to 6f, 29f to 12f, 23f to 18f)
        }
        RemoteKey.MENU -> {
            line(5f to 7f, 19f to 7f)
            line(5f to 12f, 19f to 12f)
            line(5f to 17f, 19f to 17f)
        }
        RemoteKey.PLAY_PAUSE -> {
            val tri = Path().apply { moveTo(4f * u, 6f * u); lineTo(11f * u, 12f * u); lineTo(4f * u, 18f * u); close() }
            drawPath(tri, color)
            drawLine(color, Offset(15f * u, 6f * u), Offset(15f * u, 18f * u), 2.4f * u, StrokeCap.Round)
            drawLine(color, Offset(20f * u, 6f * u), Offset(20f * u, 18f * u), 2.4f * u, StrokeCap.Round)
        }
        else -> Unit
    }
}
