@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.miryotube.ui

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.miraiijo.miryotube.data.Section
import dev.miraiijo.miryotube.update.Updater
import kotlinx.coroutines.delay

/**
 * The redesign's header (miryo's bar): the account left-most, the sections as tabs with Recherche
 * right after them, and on the right ("Lecture en cours" with a video in the small window) the
 * appearance (Jour / Nuit / Auto: OK goes to the next one), Réglages (a red dot: an update is
 * available), miryo's weather and the time right-most. Replaces the side menu. OK on a tab opens that section.
 */
@Composable
fun TopHeader(
    section: Section,
    signedIn: Boolean,
    accountName: String?,
    accountAvatar: String?,
    minimized: Boolean,
    modifier: Modifier = Modifier,
    onFocusChange: (Boolean) -> Unit,
    onSection: (Section) -> Unit,
    onAccount: () -> Unit,
    onSettings: () -> Unit,
    onRestorePlayer: () -> Unit,
    /** "Apparence" (0 = Auto, 1 = Jour, 2 = Nuit) and OK on its button: the next one. */
    appearance: Int = 0,
    onAppearance: () -> Unit = {},
    /** An update was found (Réglages › Mises à jour): a red dot on Réglages. */
    updateFound: Updater.UpdateInfo? = null,
    /** Incremented when a section is shown from the tabs: the focus goes (stays) on its tab. */
    tabFocusTick: Int = 0,
    /** Kids profile: Accueil alone, no Rechercher nor Réglages. */
    kids: Boolean = false,
) {
    val sections = if (kids) listOf(Section.HOME) else Section.entries.filter { it != Section.SEARCH && (!it.account || signedIn) }
    val requesters = remember { Section.entries.associateWith { FocusRequester() } }
    val playerButton = remember { FocusRequester() }
    LaunchedEffect(tabFocusTick) {
        if (tabFocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (section in sections) requesters.getValue(section).tryRequestFocus()
    }
    Row(
        modifier
            .fillMaxWidth()
            .onFocusChanged { onFocusChange(it.hasFocus) }
            // Entering the header (Up from the rows, Back) lands on the current section's tab, or on
            // "Lecture en cours" with a video in the small window (Back then OK: full screen again).
            .focusProperties { enter = { if (minimized) playerButton else if (section in sections) requesters.getValue(section) else FocusRequester.Default } }
            .focusGroup()
            // The bar's top in the TV's safe zone (system dp, whatever the interface size).
            .padding(start = Dimens.Gutter, end = Dimens.Gutter, top = SafeArea.top, bottom = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Opale: one bar, as in miryo. The sections on the left (no big title) and Recherche right
        // after them; the round glass buttons and the time right-most, all on one line.
        Row(Modifier.weight(1f, fill = false), horizontalArrangement = Arrangement.spacedBy(TAB_GAP), verticalAlignment = Alignment.CenterVertically) {
            // The account left-most, as miryo's profile.
            RoundButton(onClick = onAccount, filled = false) { AvatarPicture(accountAvatar, accountName, HEADER_BUTTON, 13.sp) }
            // The tabs scroll when they don't fit (signed in, 9 tabs, from ~95 %): before, the last
            // ones were squeezed to nothing and the focus went invisible on them.
            Box(Modifier.weight(1f, fill = false)) {
                EdgeScroll(TAB_MARGIN) {
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(TAB_GAP)) {
                        sections.forEach { item ->
                            Tab(
                                label = if (item == Section.HOME) "Pour toi" else item.label,
                                current = item == section,
                                modifier = Modifier.focusRequester(requesters.getValue(item)),
                                onClick = { onSection(item) },
                            )
                        }
                    }
                }
            }
            if (!kids) RoundButton(onClick = { onSection(Section.SEARCH) }) { Icon(MiryoIcon.SEARCH, size = HEADER_ICON) }
        }
        Row(
            Modifier.padding(start = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (minimized) {
                MiryoButton(
                    "Lecture en cours",
                    modifier = Modifier.focusRequester(playerButton),
                    icon = MiryoIcon.PIP,
                    onClick = onRestorePlayer,
                )
            }
            // One button for the three looks: it shows the current one (sun, moon, miryo's half circle for Auto).
            RoundButton(onClick = onAppearance) {
                when (appearance) {
                    1 -> Icon(MiryoIcon.SUN, size = HEADER_ICON)
                    2 -> Icon(MiryoIcon.MOON, size = HEADER_ICON)
                    else -> Icon(MiryoIcon.CONTRAST, size = HEADER_ICON)
                }
            }
            // Réglages, with miryo's red dot when an update is waiting (drawn over the button's corner).
            if (!kids) Box(Modifier.size(HEADER_BUTTON)) {
                RoundButton(onClick = onSettings) { Icon(MiryoIcon.SETTINGS, size = HEADER_ICON) }
                if (updateFound != null) {
                    Box(Modifier.align(Alignment.TopEnd).size(UPDATE_DOT).background(Palette.Live, CircleShape))
                }
            }
            // miryo's weather (its city), then the time right-most, as in miryo.
            HeaderWeather(Modifier.padding(start = 2.dp))
            HeaderClock(Modifier.padding(start = 2.dp))
        }
    }
}

/**
 * A section tab, as text (no pill: "Pour toi" starts right under the title): bold when it's the
 * current section; a solid bar under the word when focused, a faint one under the current section.
 */
@Composable
private fun Tab(label: String, current: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { focused = it.isFocused },
        shape = ClickableSurfaceDefaults.shape(shape = RectangleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (current) Palette.TextPrimary else Palette.TextSecondary,
            focusedContainerColor = Color.Transparent,
            focusedContentColor = Palette.TextPrimary,
            pressedContainerColor = Color.Transparent,
            pressedContentColor = Palette.TextPrimary,
        ),
    ) {
        // As wide as the word: the bar sits right under it.
        Column(Modifier.width(IntrinsicSize.Max).padding(top = 8.dp)) {
            // Steady width: the bold current tab never pushes the others.
            SteadyText(label, fontSize = 15.sp, fontWeight = if (current) FontWeight.Bold else FontWeight.Medium)
            Box(
                Modifier
                    .padding(top = 5.dp)
                    .fillMaxWidth()
                    .height(TAB_BAR)
                    .clip(RoundedCornerShape(TAB_BAR / 2))
                    .background(
                        when {
                            focused -> Palette.Focus
                            current -> Palette.TextPrimary.copy(alpha = 0.25f)
                            else -> Color.Transparent
                        },
                    ),
            )
        }
    }
}

/** miryo's round header button: the suite's glass (Glass.kt); solid focus color when focused. */
@Composable
private fun RoundButton(onClick: () -> Unit, filled: Boolean = true, content: @Composable () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.size(HEADER_BUTTON),
        shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.Avatar, pressedScale = FocusScale.Avatar),
        // Opale's glass button: translucent fill and a thin edge; focused, the solid focus color
        // (the avatar keeps its picture: a ring around it instead).
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (filled) Palette.Glass else Color.Transparent,
            contentColor = Palette.TextPrimary,
            focusedContainerColor = if (filled) Palette.Focus else Color.Transparent,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = if (filled) Palette.Focus else Color.Transparent,
            pressedContentColor = Palette.OnFocus,
        ),
        border = ClickableSurfaceDefaults.border(
            border = if (filled) androidx.tv.material3.Border(glassRim(), shape = CircleShape) else androidx.tv.material3.Border.None,
            focusedBorder = if (filled) androidx.tv.material3.Border.None else androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Palette.Focus), inset = 3.dp, shape = CircleShape),
        ),
        glow = ClickableSurfaceDefaults.glow(focusedGlow = androidx.tv.material3.Glow(elevationColor = Palette.Shadow, elevation = 10.dp)),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { content() }
    }
}

/**
 * The temperature of miryo's city (miryo-link), refreshed every 30 min while the bar is shown.
 * Nothing at all without miryo or without a city there.
 */
@Composable
private fun HeaderWeather(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    val weather by androidx.compose.runtime.produceState<dev.miraiijo.miryotube.link.MiryoWeather.Now?>(null) {
        while (true) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { dev.miraiijo.miryotube.link.MiryoWeather.current(context) }?.let { value = it }
            delay(WEATHER_REFRESH_MS)
        }
    }
    val now = weather ?: return
    val icon = runCatching { MiryoIcon.valueOf(now.icon) }.getOrDefault(MiryoIcon.CLOUD)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, size = 15.dp, color = Palette.TextPrimary)
        Text("${now.temperature}°", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, maxLines = 1, softWrap = false)
    }
}

private const val WEATHER_REFRESH_MS = 30 * 60_000L

@Composable
private fun HeaderClock(modifier: Modifier = Modifier) {
    val format = remember { java.text.SimpleDateFormat("HH:mm", java.util.Locale.FRANCE) }
    // The time right away (an empty first frame made the round buttons jump at launch).
    var time by remember { mutableStateOf(format.format(java.util.Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            time = format.format(java.util.Date())
            delay(10_000)
        }
    }
    // Tabular figures: every digit as wide, so the round buttons don't shift each minute.
    Text(time, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.TextPrimary, style = androidx.tv.material3.LocalTextStyle.current.copy(fontFeatureSettings = "tnum"), modifier = modifier)
}

/**
 * The redesign's background: the page color with two soft lights (warm top right, cool bottom
 * left). Painted once per size or look change into a half-size picture, then drawn as that picture:
 * one opaque blit per frame instead of three full-screen gradients (two of them blended), which the
 * Freebox Player POP's GPU paid on every frame the screen moved. Smooth gradients lose nothing at
 * half size (960 × 540 on a 1080p box, 2 MB).
 */
fun Modifier.miryoBackground(): Modifier = drawWithCache {
    val colors = Palette.colors
    val w = (size.width / BACKGROUND_SCALE).toInt().coerceAtLeast(1)
    val h = (size.height / BACKGROUND_SCALE).toInt().coerceAtLeast(1)
    val picture = androidx.compose.ui.graphics.ImageBitmap(w, h)
    androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
        this, layoutDirection, androidx.compose.ui.graphics.Canvas(picture), Size(w.toFloat(), h.toFloat()),
    ) {
        // miryo's "Neutre" ambiance: a diagonal of three greys, a warm light from the top right corner
        // and a cool one from the bottom left (miryo's 1400 / 1200 px radii on a 1920 px screen).
        drawRect(Brush.linearGradient(0f to colors.background, 0.55f to colors.backgroundMid, 1f to colors.backgroundEnd, start = Offset.Zero, end = Offset(size.width, size.height)))
        drawRect(Brush.radialGradient(listOf(colors.glowWarm, Color.Transparent), center = Offset(size.width, 0f), radius = size.width * GLOW_WARM_RADIUS))
        drawRect(Brush.radialGradient(listOf(colors.glowCool, Color.Transparent), center = Offset(0f, size.height), radius = size.width * GLOW_COOL_RADIUS))
    }
    val target = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
    onDrawBehind { drawImage(picture, dstSize = target, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low) }
}

/** miryo's bar: 30 dp round glass buttons, 14 dp icons, 10 dp apart (Dimens.PillGap). */
private val HEADER_BUTTON = 30.dp
private val HEADER_ICON = 14.dp
private val UPDATE_DOT = 7.dp
/** Tabs as text: the gap between two words, and the bar under the focused / current one. */
private val TAB_GAP = 28.dp
private val TAB_BAR = 3.dp
/** Room kept on each side of the focused tab when the tabs scroll. */
private val TAB_MARGIN = 24.dp
private const val GLOW_WARM_RADIUS = 0.73f
private const val GLOW_COOL_RADIUS = 0.625f
/** The background is painted at 1 / this of the screen, then stretched (smooth gradients only). */
private const val BACKGROUND_SCALE = 2f

/** By day, the header's white buttons get a soft shadow (none at night, on the dark page). */
@Composable
private fun Modifier.dayShadow(): Modifier =
    if (Palette.colors.isLight) shadow(10.dp, CircleShape, clip = false, ambientColor = Color(0x14121317), spotColor = Color(0x14121317)) else this
