package dev.miraiijo.lumentube.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Typography
import androidx.tv.material3.darkColorScheme
import dev.miraiijo.lumentube.R

/* miryo's design tokens (same palettes, radii, Inter typography): miryo tube looks like miryo. */

/**
 * The two looks of the redesign (miryo's): Nuit (dark) and Jour (light). Colors are read from the
 * current one, a snapshot state: switching the appearance recomposes everything that uses them.
 */
class MirazukiColors(
    val background: Color,
    val panel: Color,
    val tile: Color,
    val tileFocused: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    val controlIdle: Color,
    val selected: Color,
    val outline: Color,
    val track: Color,
    val divider: Color,
    /** Focused element: a solid pill (white at night, near-black by day) and its content. */
    val focus: Color,
    val onFocus: Color,
    /** Ring around a focused card. */
    val focusRing: Color,
    /** Soft lights of the background: warm top right, cool bottom left. */
    val glowWarm: Color,
    val glowCool: Color,
    val isLight: Boolean,
    /** miryo's Opale: the background's diagonal ("Neutre" ambiance), from [background] to these. */
    val backgroundMid: Color,
    val backgroundEnd: Color,
    /** Glass surfaces (round buttons, panels) and their thin edge; drop shadow of lifted elements. */
    val glass: Color,
    val glassBorder: Color,
    val shadow: Color,
)

// miryo's Opale charter (miryo Common.kt / Backdrop.kt, "Neutre" ambiance): neutral greys, no blue tint.
val NightColors = MirazukiColors(
    background = Color(0xFF0A0B0E), panel = Color(0xFF161618), tile = Color(0xFF1C1C1E), tileFocused = Color(0xFF2C2C2E),
    textPrimary = Color(0xFFF5F5F7), textSecondary = Color(0xFF98989D),
    controlIdle = Color.White.copy(alpha = 0.10f), selected = Color.White.copy(alpha = 0.16f), outline = Color.White.copy(alpha = 0.22f),
    track = Color.White.copy(alpha = 0.22f), divider = Color.White.copy(alpha = 0.08f),
    focus = Color(0xFFF5F5F7), onFocus = Color(0xFF0A0A0B), focusRing = Color(0xFFF5F5F7),
    glowWarm = Color(0x24C8A46A), glowCool = Color(0x1A5A8CFF), isLight = false,
    backgroundMid = Color(0xFF0E0F13), backgroundEnd = Color(0xFF111216),
    glass = Color.White.copy(alpha = 0.08f), glassBorder = Color.White.copy(alpha = 0.10f), shadow = Color.Black.copy(alpha = 0.55f),
)

/**
 * Jour's lightest color: the logo's "blanc lune", not pure white, which dazzles on a TV in a dark
 * room. Backgrounds are a shade under it (the same warm greys as miryo), panels and the focused card are it.
 */
private val MOONLIGHT = Color(0xFFF3EEE1)

val DayColors = MirazukiColors(
    background = Color(0xFFE4E1D9), panel = MOONLIGHT, tile = Color(0xFFD9D5CB), tileFocused = MOONLIGHT,
    textPrimary = Color(0xFF16171A), textSecondary = Color(0xFF505257),
    controlIdle = Color.Black.copy(alpha = 0.06f), selected = MOONLIGHT.copy(alpha = 0.90f), outline = Color.Black.copy(alpha = 0.16f),
    track = Color.Black.copy(alpha = 0.14f), divider = Color.Black.copy(alpha = 0.08f),
    focus = Color(0xFF16171A), onFocus = MOONLIGHT, focusRing = Color(0xFF16171A),
    glowWarm = Color(0x4DC8A46A), glowCool = Color(0x1F5A8CFF), isLight = true,
    backgroundMid = Color(0xFFDFDBD2), backgroundEnd = Color(0xFFDAD6CC),
    glass = MOONLIGHT.copy(alpha = 0.62f), glassBorder = MOONLIGHT.copy(alpha = 0.90f), shadow = Color(0xFF2A2418).copy(alpha = 0.20f),
)

object Palette {
    /** Set by MirazukiTubeTheme from the "Apparence" setting. */
    var colors by androidx.compose.runtime.mutableStateOf(NightColors)

    val Background get() = colors.background
    val Panel get() = colors.panel
    val Tile get() = colors.tile
    val TileFocused get() = colors.tileFocused
    val TextPrimary get() = colors.textPrimary
    val TextSecondary get() = colors.textSecondary
    /** Progress bars, text cursor, pairing codes (the main text color). */
    val Highlight get() = colors.textPrimary
    /** Idle fill of a control (button, field, chip). */
    val ControlIdle get() = colors.controlIdle
    /** Selected / active state (with a check icon). */
    val Selected get() = colors.selected
    val Outline get() = colors.outline
    /** Over artwork (video thumbnails, the player): always light on dark. */
    val ControlOnArt = Color.White.copy(alpha = 0.22f)
    val TextOnArtSecondary = Color.White.copy(alpha = 0.75f)
    val Track get() = colors.track
    val Divider get() = colors.divider
    val Focus get() = colors.focus
    val OnFocus get() = colors.onFocus
    val FocusRing get() = colors.focusRing
    val Glass get() = colors.glass
    val GlassBorder get() = colors.glassBorder
    val Shadow get() = colors.shadow
    val Danger = Color(0xFFFF453A)
    val Warning = Color(0xFFFF9F0A)
    /** The redesign's red (S'abonner, "EN DIRECT") and SponsorBlock's yellow on the bar. */
    val Live = Color(0xFFE5383B)
    val Sponsor = Color(0xFFF2C230)
    /** Text of a choice pill (filters, sorts, settings): a touch softer than TextPrimary. */
    val ChipText get() = if (colors.isLight) Color(0xFF2A2C33) else Color(0xFFE4E5E9)
}

/** Three focus lifts only: controls, cards, avatars. */
object FocusScale {
    const val Control = 1.05f
    const val Card = 1.08f
    /** A card with the focus ring (the redesign): a lighter zoom, the ring already shows it. */
    const val CardRing = 1.04f
    /** miryo's Opale cards: a 1.05 lift (with its soft ring and shadow). */
    const val CardOpale = 1.05f
    const val Avatar = 1.1f
}


/**
 * Inter: the closest open font to SF Pro (Apple TV's system font, licensed for Apple platforms
 * only). Bundled in res/font, Latin subset, 4 weights; licence in licenses/Inter-OFL.txt.
 */
val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

/** Tight tracking for large titles (SF Pro Display-like). */
val DisplayTracking = (-0.022).em

/** Every Material text style (also those set inside tv-material buttons) uses Inter. */
private val MirazukiTypography: Typography = Typography().let { t ->
    // Closer to SF Pro: Material's wide default tracking (up to +0.5 sp) replaced by Apple-like
    // tight tracking, relative to the size (em) so it holds for every explicit fontSize.
    // lineHeight unspecified: Material's fixed ones (24 sp for bodyLarge, the default Text style)
    // made every Text without its own lineHeight 24 dp tall whatever its size, so big titles and
    // codes overflowed their line and small labels (badges, captions) took far too much room.
    fun TextStyle.text() = copy(fontFamily = InterFamily, letterSpacing = (-0.011).em, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
    fun TextStyle.display() = copy(fontFamily = InterFamily, letterSpacing = (-0.022).em, lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified)
    Typography(
        displayLarge = t.displayLarge.display(), displayMedium = t.displayMedium.display(), displaySmall = t.displaySmall.display(),
        headlineLarge = t.headlineLarge.display(), headlineMedium = t.headlineMedium.display(), headlineSmall = t.headlineSmall.display(),
        titleLarge = t.titleLarge.display(), titleMedium = t.titleMedium.text(), titleSmall = t.titleSmall.text(),
        bodyLarge = t.bodyLarge.text(), bodyMedium = t.bodyMedium.text(), bodySmall = t.bodySmall.text(),
        labelLarge = t.labelLarge.text(), labelMedium = t.labelMedium.text(), labelSmall = t.labelSmall.text(),
    )
}

/** "Apparence": Auto (day from 7:30 to 19:30, night otherwise), Jour, Nuit. */
enum class Appearance { AUTO, DAY, NIGHT }

/** Day or night now, for Auto (re-checked every minute). */
private fun isDaytime(): Boolean {
    val now = java.util.Calendar.getInstance()
    val minutes = now.get(java.util.Calendar.HOUR_OF_DAY) * 60 + now.get(java.util.Calendar.MINUTE)
    return minutes in DAY_START_MIN until DAY_END_MIN
}

/**
 * The window's color for an "Apparence" setting (0 = Auto, 1 = Jour, 2 = Nuit): set before Compose
 * draws its first frame, so a day start never flashes black.
 */
fun windowBackground(appearance: Int): Color = when (Appearance.entries.getOrElse(appearance) { Appearance.AUTO }) {
    Appearance.DAY -> DayColors.background
    Appearance.NIGHT -> NightColors.background
    Appearance.AUTO -> if (isDaytime()) DayColors.background else NightColors.background
}

private const val DAY_START_MIN = 7 * 60 + 30
/** "Taille de l'interface", in %: from [UI_SCALE_MIN] to [UI_SCALE_MAX] by [UI_SCALE_STEP]. */
const val UI_SCALE_DEFAULT = 100
const val UI_SCALE_MIN = 60
const val UI_SCALE_MAX = 120
const val UI_SCALE_STEP = 5
private const val DAY_END_MIN = 19 * 60 + 30

@Composable
fun MirazukiTubeTheme(appearance: Appearance = Appearance.NIGHT, uiScale: Int = UI_SCALE_DEFAULT, content: @Composable () -> Unit) {
    var daytime by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(isDaytime()) }
    if (appearance == Appearance.AUTO) {
        androidx.compose.runtime.LaunchedEffect(Unit) {
            while (true) {
                daytime = isDaytime()
                kotlinx.coroutines.delay(60_000)
            }
        }
    }
    val light = when (appearance) {
        Appearance.DAY -> true
        Appearance.NIGHT -> false
        Appearance.AUTO -> daytime
    }
    val target = if (light) DayColors else NightColors
    androidx.compose.runtime.SideEffect {
        if (Palette.colors !== target) Palette.colors = target
    }
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Palette.Highlight,
            background = Palette.Background,
            surface = Palette.Tile,
            onSurface = Palette.TextPrimary,
        ),
        typography = MirazukiTypography,
    ) {
        // tv-material's Text takes LocalContentColor, black by default outside a Surface: without
        // this, every Text with no explicit color (row titles, the sheet's title) is black on black.
        // "Taille de l'interface": everything drawn at uiScale % — dp and sp alike, dialogs included
        // (100 % by default). The safe zone ([Safe]) is not scaled: it's given back in scaled dp.
        val density = androidx.compose.ui.platform.LocalDensity.current
        val scale = uiScale.coerceIn(UI_SCALE_MIN, UI_SCALE_MAX) / 100f
        val scaled = androidx.compose.runtime.remember(density, scale) {
            androidx.compose.ui.unit.Density(density.density * scale, density.fontScale)
        }
        val safe = androidx.compose.runtime.remember(scale) { safeInsets(scale) }
        CompositionLocalProvider(
            LocalContentColor provides Palette.TextPrimary,
            androidx.compose.ui.platform.LocalDensity provides scaled,
            LocalSafe provides safe,
            content = content,
        )
    }
}

/**
 * TV overscan safe zone, in system dp (the screen's own: 960 × 540 on the Freebox Player POP),
 * whatever "Taille de l'interface". Read it through [LocalSafe] (in the current, scaled dp).
 */
object Safe {
    val Side = 48.dp
    val Top = 32.dp
    val Bottom = 48.dp
}

/** [Safe] at a UI scale: 48 system dp are 48 / scale dp once the density is scaled. */
private fun safeInsets(scale: Float): PaddingValues =
    PaddingValues(start = Safe.Side / scale, top = Safe.Top / scale, end = Safe.Side / scale, bottom = Safe.Bottom / scale)

/** The safe zone in the current dp (provided by [MirazukiTubeTheme], again in [MirazukiDialog]). */
val LocalSafe = staticCompositionLocalOf { safeInsets(1f) }

/** Shared measurements: one set of radii, heights and font sizes for the whole app. */
object Dimens {
    /**
     * Side margin: the safe zone's side (48 system dp, ~5 % of a 960 dp wide TV screen), in the
     * current dp — never reduced by "Taille de l'interface" (a fixed 48 dp was 38 at 80 %).
     */
    val Gutter: androidx.compose.ui.unit.Dp
        @Composable @androidx.compose.runtime.ReadOnlyComposable get() = LocalSafe.current.calculateStartPadding(androidx.compose.ui.unit.LayoutDirection.Ltr)
    /** Space between the top of a screen's area and its first content. */
    val ContentTop = 16.dp
    /** Space above each section title (rows never touch the next title). */
    val SectionTop = 30.dp
    /** Every chip, button and field has this height. */
    val ControlHeight = 30.dp
    /** Gap between controls in a row (chips, buttons). */
    val PillGap = 10.dp
    /** Gap between cards in a content row (posters, tiles, Reprendre). */
    val CardGap = 16.dp

    /** Radii: controls, cards (tiles, banners, menus), dialogs. */
    val RadiusControl = 14.dp
    /** Opale's cards: 13 dp. */
    val RadiusCard = 13.dp
    val RadiusDialog = 24.dp

    /** Smallest text used anywhere (10-foot legibility). */
    val TextMin = 13.sp
}

val ControlShape = RoundedCornerShape(Dimens.RadiusControl)
val CardShape = RoundedCornerShape(Dimens.RadiusCard)
val DialogShape = RoundedCornerShape(Dimens.RadiusDialog)

/**
 * A full-window dialog drawn at the "Taille de l'interface" scale: a Dialog is its own window,
 * whose view provides the raw screen density again, so the scaled one is carried over here.
 */
@Composable
fun MirazukiDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val safe = LocalSafe.current
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismissRequest,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
    ) {
        // A long OK opens most menus: the release of that press must not click their first button.
        androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.okPressStartsHere()) {
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides density, LocalSafe provides safe, content = content)
        }
    }
}
