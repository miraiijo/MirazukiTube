@file:OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class, ExperimentalGlideComposeApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale

/* miryo's controls and focus scrolling, shared by every miryo tube screen. */

fun FocusRequester.tryRequestFocus(): Boolean = try {
    requestFocus(FocusDirection.Enter)
} catch (_: IllegalStateException) {
    false
}

/**
 * Button, as in miryo: the suite's glass when idle (Glass.kt), the solid focus capsule when
 * focused. A [containerColor] (the red S'abonner, a selected choice) replaces the glass.
 */
@Composable
fun MirazukiButton(
    text: String,
    modifier: Modifier = Modifier,
    icon: MirazukiIcon? = null,
    containerColor: Color? = null,
    /** Text and icon when not focused (white on the red S'abonner, by day too). */
    contentColor: Color = Palette.TextPrimary,
    /** 13 dp on choice pills (sorts, suggestions), the redesign's. */
    iconSize: Dp = 15.dp,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(Dimens.ControlHeight + 6.dp),
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
        shape = ButtonDefaults.shape(shape = CircleShape),
        scale = ButtonDefaults.scale(focusedScale = FocusScale.Control, pressedScale = FocusScale.Control),
        colors = ButtonDefaults.colors(
            containerColor = containerColor ?: Palette.Glass,
            contentColor = contentColor,
            focusedContainerColor = Palette.Focus,
            focusedContentColor = Palette.OnFocus,
            pressedContainerColor = Palette.Focus,
            pressedContentColor = Palette.OnFocus,
        ),
        border = if (containerColor == null) ButtonDefaults.border(border = androidx.tv.material3.Border(glassRim(), shape = CircleShape), focusedBorder = androidx.tv.material3.Border.None) else ButtonDefaults.border(),
    ) {
        icon?.let {
            Icon(it, size = iconSize)
            Spacer(Modifier.width(7.dp))
        }
        // One line; too long for the room ("Se déconnecter de <a long name>" at 120 %): an ellipsis.
        Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * One line « [start] · [end] » where only [start] is cut: a long channel or author name ends in
 * « … » and « il y a 3 jours » always stays whole at the end of the line.
 */
@Composable
fun KeepEndLine(
    start: String,
    end: String?,
    modifier: Modifier = Modifier,
    fontSize: androidx.compose.ui.unit.TextUnit = 13.sp,
    lineHeight: androidx.compose.ui.unit.TextUnit = androidx.compose.ui.unit.TextUnit.Unspecified,
    color: Color = Palette.TextSecondary,
    fontWeight: FontWeight? = null,
    separator: String = " · ",
) {
    androidx.compose.foundation.layout.Row(modifier) {
        if (start.isNotBlank()) {
            Text(start, fontSize = fontSize, lineHeight = lineHeight, color = color, fontWeight = fontWeight, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
        if (!end.isNullOrBlank()) {
            Text(if (start.isBlank()) end else separator + end, fontSize = fontSize, lineHeight = lineHeight, color = color, fontWeight = fontWeight, maxLines = 1, softWrap = false)
        }
    }
}

/**
 * Up / Down between rows, the Apple TV way: the page glides to the focused row on a soft spring
 * without bounce (about 0.4 s to settle) instead of Compose's stiff default (about 0.15 s), which
 * turned every row change into a jump on the box. A spring keeps its speed when the next row is
 * asked for before the page has arrived: a held Down is one continuous slide, never a stop and a
 * fresh start at each row. Left / Right along a row keep Compose's default.
 */
private val PageGlide: AnimationSpec<Float> = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 240f)
private val RowSpring: AnimationSpec<Float> = spring()

/**
 * Pages of rows keep the rows around the visible ones ready: one screen below is composed and laid
 * out ahead of time (in the frames' idle time), half a screen above is kept. A row is never built
 * in the middle of the glide that brings it in, and going back up doesn't rebuild it.
 */
@OptIn(ExperimentalFoundationApi::class)
private val ROWS_CACHE = LazyLayoutCacheWindow(aheadFraction = 1f, behindFraction = 0.5f)

/** State of a vertical page of rows (see [ROWS_CACHE]). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun rememberRowsState(): LazyListState = rememberLazyListState(cacheWindow = ROWS_CACHE)

/**
 * Scrolls only when the focused element would leave the visible area, keeping margins. Vertical
 * lists [glide]; lines that scroll sideways don't.
 */
private class MarginSpec(private val start: Float, private val end: Float, private val glide: Boolean = false) : BringIntoViewSpec {
    @Suppress("OVERRIDE_DEPRECATION")
    override val scrollAnimationSpec: AnimationSpec<Float> get() = if (glide) PageGlide else RowSpring

    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val visibleEnd = containerSize - end
        return when {
            offset < start -> offset - start
            offset + size > visibleEnd -> minOf(offset + size - visibleEnd, offset - start)
            else -> 0f
        }
    }
}

/** The safe zone's sides in the current dp ([LocalSafe]), for one-sided paddings. */
object SafeArea {
    val side: Dp
        @Composable @ReadOnlyComposable get() = LocalSafe.current.calculateStartPadding(LayoutDirection.Ltr)
    val top: Dp
        @Composable @ReadOnlyComposable get() = LocalSafe.current.calculateTopPadding()
    val bottom: Dp
        @Composable @ReadOnlyComposable get() = LocalSafe.current.calculateBottomPadding()
}

/** Keeps the content inside the TV's safe zone (all four sides). */
@Composable
fun Modifier.safePadding(): Modifier = padding(LocalSafe.current)

/**
 * A dialog's panel, centred inside the safe zone: [width] at most (narrower on a small or scaled
 * screen), never taller than the safe zone (its content scrolls instead). [modifier] is the
 * panel's own (background, inner padding), applied inside the width cap, outside the scroll.
 */
@Composable
fun SafeDialog(
    width: Dp,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    // The padded Box already bounds the height: no BoxWithConstraints (a subcomposition) needed.
    Box(Modifier.fillMaxSize().safePadding(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = width).then(modifier).verticalScroll(rememberScrollState()),
            horizontalAlignment = horizontalAlignment,
            verticalArrangement = verticalArrangement,
            content = content,
        )
    }
}

/**
 * The player's side panels (settings, comments): the background runs to the screen's right, top
 * and bottom edges, the content stays in the safe zone (end and top padded); the list inside gets
 * the bottom inset as content padding ([content]'s argument), and the focused line is never
 * scrolled into it.
 */
@Composable
fun SafeSidePanel(width: Dp, modifier: Modifier = Modifier, content: @Composable ColumnScope.(bottom: PaddingValues) -> Unit) {
    val safe = LocalSafe.current
    val bottom = safe.calculateBottomPadding()
    val density = LocalDensity.current
    val spec = remember(density, bottom) { with(density) { MarginSpec(0f, bottom.toPx(), glide = true) } }
    Column(
        modifier
            .width(width)
            .fillMaxHeight()
            .background(Palette.Panel.copy(alpha = 0.97f))
            .padding(start = PANEL_INNER, end = safe.calculateEndPadding(LayoutDirection.Ltr), top = safe.calculateTopPadding()),
    ) {
        CompositionLocalProvider(LocalBringIntoViewSpec provides spec) { this@Column.content(PaddingValues(bottom = bottom)) }
    }
}

/** A side panel's inner margin on the video's side. */
private val PANEL_INNER = 20.dp

/**
 * Page rows: the column only scrolls when the focused card would leave the screen, keeping its row
 * title ([start]) and its caption ([end]) visible. The page doesn't move on every Up / Down.
 */
@Composable
fun RowsScroll(start: Dp, end: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density, start, end) { with(density) { MarginSpec(start.toPx(), end.toPx(), glide = true) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/**
 * A scrolling line of pills or tabs (header tabs, search suggestions, a channel's sorts): it only
 * scrolls when the focused one would leave it, keeping [margin] — never TV's default "pivot"
 * scrolling, which moved the whole line on every Left / Right.
 */
@Composable
fun EdgeScroll(margin: Dp, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val spec = remember(density, margin) { with(density) { MarginSpec(margin.toPx(), margin.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec, content = content)
}

/** A row of cards: side-gutter margins, and it remembers its last focused card. */
@Composable
fun RowScroll(content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val gutter = Dimens.Gutter
    val spec = remember(density, gutter) { with(density) { MarginSpec(gutter.toPx(), gutter.toPx()) } }
    CompositionLocalProvider(LocalBringIntoViewSpec provides spec) {
        Box(Modifier.focusRestorer()) { content() }
    }
}

/**
 * A video's picture in HD: YouTube's 1280×720 version (maxresdefault), cropped to its frame.
 * Some videos don't have one (404): the feed's thumbnail is loaded instead. Glide downsamples it to
 * the frame's size, so a small card costs no more memory than before.
 */
@Composable
fun Thumbnail(
    videoId: String?,
    fallback: String?,
    modifier: Modifier = Modifier,
    custom: String? = null,
    small: Boolean = false,
    /** The frame's width when known: a small one takes YouTube's 480 px picture. */
    width: Dp = Dp.Unspecified,
) {
    // Cards: YouTube's 640 px picture (sddefault), or its 480 px one (hqdefault) when the frame is
    // no wider than that — the usual card on a 1080p box. hqdefault exists for every video (no 404
    // then a second request, as sddefault often does) and weighs about half: less to download and
    // decode on the Freebox Player POP.
    val px = if (width == Dp.Unspecified) Float.MAX_VALUE else with(LocalDensity.current) { width.toPx() }
    val name = when {
        !small -> "maxresdefault"
        px <= HQ_PX -> "hqdefault"
        else -> "sddefault"
    }
    val hd = custom ?: videoId?.let { "https://i.ytimg.com/vi/$it/$name.jpg" }
    GlideImage(
        model = hd ?: fallback,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        requestBuilderTransform = { if (hd != null && fallback != null) it.error(it.clone().load(fallback)) else it },
    )
}

/**
 * Channel and account avatars come small (88 px). Their Google image server resizes on demand:
 * "=s88-…" asked as "=s256-…" gives a sharp picture at the same address.
 */
fun avatarUrl(url: String?): String? = url?.replace(AVATAR_SIZE, "=s$AVATAR_PX")?.let { if (it.startsWith("//")) "https:$it" else it }

/** Width of YouTube's hqdefault picture (480 × 360, the 16:9 frame inside it is 480 × 270). */
private const val HQ_PX = 480f

private val AVATAR_SIZE = Regex("=s\\d+")

private const val AVATAR_PX = 256

/** "12:34" / "1:02:03". */
fun formatDuration(ms: Long): String {
    if (ms <= 0) return ""
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    // By hand: String.format for every card (parses the pattern each time) shows on the box.
    fun two(n: Long) = if (n < 10) "0$n" else n.toString()
    return if (h > 0) "$h:${two(m)}:${two(s)}" else "$m:${two(s)}"
}

/** The miryo tube logo (mark + wordmark), in the version drawn for the current appearance. */
@Composable
fun BrandLogo(modifier: Modifier = Modifier) {
    androidx.compose.foundation.Image(
        androidx.compose.ui.res.painterResource(
            if (Palette.colors.isLight) dev.miraiijo.lumentube.R.drawable.brand_logo_light else dev.miraiijo.lumentube.R.drawable.brand_logo_dark,
        ),
        contentDescription = "miryo tube",
        modifier = modifier,
    )
}

/**
 * A one-line label whose weight changes with the focus or the selection without changing its
 * width: the box is as wide as the bold version, so the bar around it never shifts.
 */
@Composable
fun SteadyText(text: String, fontSize: androidx.compose.ui.unit.TextUnit, fontWeight: androidx.compose.ui.text.font.FontWeight, modifier: Modifier = Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        androidx.tv.material3.Text(text, fontSize = fontSize, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, maxLines = 1, softWrap = false, color = Color.Transparent)
        androidx.tv.material3.Text(text, fontSize = fontSize, fontWeight = fontWeight, maxLines = 1, softWrap = false)
    }
}
