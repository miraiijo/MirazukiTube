@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class)

package dev.miraiijo.lumentube.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.bumptech.glide.integration.compose.GlideImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.em
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import dev.miraiijo.lumentube.ChannelHeader
import dev.miraiijo.lumentube.Page
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.runtime.snapshotFlow

/** The row title's line (16 sp text). */
private val ROW_TITLE_LINE = 20.sp
/** The hairline between two rows (Palette.Divider: 8 % white at night, 8 % black by day). */
private val ROW_DIVIDER = 1.dp
/** Default card width; on a page it follows the screen (as many cards as fit at ~[CARD_SLOT] fill the width). */
private val CARD_WIDTH = 204.dp
/** Width per card used to count the cards of a row: 4 at 100 %, 5 at 80 %, 6 at 60 %, 3 at 120 %. */
private val CARD_SLOT = 232.dp

/**
 * A section (or an opened channel / playlist), like miryo's Bibliothèque in the side-menu layout:
 * the focused video's sheet (title, channel, duration) over its picture, and the rows below it,
 * the active row pinned under the sheet.
 */
@Composable
fun BrowsePage(
    page: Page,
    title: String?,
    focusTick: Int,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onRetry: () -> Unit,
    needsAccount: Boolean,
    onSignIn: () -> Unit,
    /** Card key to focus when returning from a popped page (else the first card). */
    focusKey: String? = null,
    /** "Réessayer" under an empty page's message. */
    showRetry: Boolean = true,
    /** Long press (or the Menu key) on a card: its menu. */
    onLongPress: (Video) -> Unit = {},
    /** A channel page: its header (avatar, name, S'abonner, orders) instead of the video sheet. */
    channel: ChannelHeader? = null,
    signedIn: Boolean = false,
    onSubscribe: () -> Unit = {},
    onSort: (Int) -> Unit = {},
    /** Room above the page's own title / channel header. */
    top: androidx.compose.ui.unit.Dp = 0.dp,
    /** The focus is somewhere the user put it on purpose (the header's tabs): the page leaves it there. */
    focusElsewhere: () -> Boolean = { false },
    /** The last rows are on screen and the page is loaded (Accueil: its extra rows come then). */
    onEndReached: () -> Unit = {},
    /** "Pour Enfant" on a channel page (null: no kids profile, no button). */
    onKid: (() -> Unit)? = null,
) {
    val first = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    /** The last focusTick served: each one moves the focus once, not again when data arrives later. */
    var servedTick by remember { mutableIntStateOf(0) }
    /** The focus is in the channel's header (S'abonner, a sort): rows arriving don't take it away. */
    var sheetFocused by remember { mutableStateOf(false) }
    val targetPos = remember(page.rows, focusKey) {
        if (focusKey == null) null
        else page.rows.withIndex().firstNotNullOfOrNull { (rIndex, row) ->
            val cIndex = row.items.indexOfFirst { it.key == focusKey }
            if (cIndex >= 0) rIndex to cIndex else null
        }
    }
    val targetRow = targetPos?.first ?: 0
    val targetItem = targetPos?.second ?: 0
    // Room kept above / below a focused card when the rows scroll, from the text lines (they follow
    // the system's font scale): the row title (8 + line + 4) with the row's top padding (12), and
    // the card's title (10 + 2 lines) and channel (2 + line), then the TV's safe zone (48 system
    // dp): the last row's caption never goes under the screen's bottom edge (overscan).
    val rowTitle = 24.dp + lines(ROW_TITLE_LINE, 1)
    val cardCaption = 12.dp + lines(TITLE_LINE, 2) + lines(CHANNEL_LINE, 1) + SafeArea.bottom
    val listState = rememberRowsState()
    LaunchedEffect(focusTick, page.rows.isNotEmpty(), page.error, needsAccount) {
        if (focusTick == 0 || focusTick == servedTick) return@LaunchedEffect
        // Moved meanwhile to the tabs or the channel's header (rows still loading): left there.
        if (focusElsewhere() || sheetFocused) {
            servedTick = focusTick
            return@LaunchedEffect
        }
        if (targetPos != null && page.rows.isNotEmpty()) {
            listState.scrollToItem(targetRow)
        }
        withFrameNanos { }
        if (first.tryRequestFocus() || action.tryRequestFocus()) servedTick = focusTick
    }
    // "Réessayer" goes away with the error, focus and all: the page's first card takes it once loaded.
    val retry = {
        servedTick = -1
        onRetry()
    }
    // Read in a derived state: scrolling doesn't recompose the page, only crossing the end does.
    val atEnd by androidx.compose.runtime.remember(listState) {
        androidx.compose.runtime.derivedStateOf {
            val info = listState.layoutInfo
            info.totalItemsCount > 0 && (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    val endReached by androidx.compose.runtime.rememberUpdatedState(onEndReached)
    LaunchedEffect(atEnd, page.loading) {
        if (atEnd && !page.loading) endReached()
    }
    // A row inserted on top (Reprendre arriving after the feed) while the page was at its top: back
    // to the top, the list kept the old first row in place and hid the new one above the screen.
    val firstKey = page.rows.firstOrNull()?.key
    LaunchedEffect(firstKey) {
        withFrameNanos { }
        if (firstKey != null && listState.firstVisibleItemIndex == 1 && listState.firstVisibleItemScrollOffset == 0) {
            listState.requestScrollToItem(0)
        }
    }

    Column(Modifier.fillMaxSize()) {
        Spacer(Modifier.height(top))
        when {
            channel != null -> ChannelSheet(channel, signedIn, onSubscribe, onSort, onSignIn, onKid, Modifier.onFocusChanged { sheetFocused = it.hasFocus })
            title != null -> Text(
                title,
                fontSize = 34.sp,
                lineHeight = 40.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = DisplayTracking,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 20.dp, bottom = 6.dp),
            )
        }
        // The cards share the screen's width: the same layout on any TV, box or density.
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val perRow = (maxWidth / CARD_SLOT).toInt().coerceIn(3, 7)
            val cardWidth = ((maxWidth - Dimens.Gutter * 2 - Dimens.CardGap * (perRow - 1)) / perRow).coerceAtLeast(120.dp)
            when {
                needsAccount -> Message(
                    "Connecte ton compte YouTube pour voir cette page.",
                    button = "Se connecter",
                    buttonModifier = Modifier.focusRequester(action),
                    onClick = onSignIn,
                )
                page.rows.isEmpty() && page.loading -> Message("Chargement…")
                page.rows.isEmpty() -> Message(
                    page.error ?: "Rien à afficher.",
                    button = if (showRetry) "Réessayer" else null,
                    buttonModifier = Modifier.focusRequester(action),
                    onClick = retry,
                )
                else -> RowsScroll(rowTitle, cardCaption) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = SafeArea.bottom + 16.dp),
                    ) {
                        itemsIndexed(page.rows, key = { _, row -> row.key }, contentType = { _, _ -> 0 }) { index, row ->
                            VideoRowView(
                                row,
                                cardWidth = cardWidth,
                                firstRequester = if (index == targetRow) first else null,
                                firstIndex = if (index == targetRow) targetItem else 0,
                                divider = index > 0,
                                onSelect = onSelect,
                                onLoadMore = onLoadMore,
                                onLongPress = onLongPress,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoRowView(
    row: VideoRow,
    cardWidth: androidx.compose.ui.unit.Dp,
    firstRequester: FocusRequester?,
    firstIndex: Int = 0,
    onSelect: (Video) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onLongPress: (Video) -> Unit,
    /** A hairline above the row (every row but the first), inside the side margins. */
    divider: Boolean = false,
) {
    val rowState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(firstRequester, firstIndex) {
        if (firstRequester != null && firstIndex > 0) rowState.scrollToItem(firstIndex)
    }
    val gutter = Dimens.Gutter
    val line = Palette.Divider
    Column(
        if (!divider) Modifier else Modifier.drawBehind {
            val inset = gutter.toPx()
            drawLine(line, Offset(inset, 0f), Offset(size.width - inset, 0f), strokeWidth = ROW_DIVIDER.toPx())
        },
    ) {
        Text(
            row.title,
            fontSize = 16.sp,
            // A fixed line: with the paddings (8 + line + 4) and the row's 12, the room kept above a card.
            lineHeight = ROW_TITLE_LINE,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 8.dp, bottom = 4.dp),
        )
        // Only channels: round cards, a bit more room above (the zoomed avatar stays clear of the
        // title). With videos (a search's mixed results too): the row is a video card's height,
        // whatever cards are on screen, so it never shrinks or grows while it scrolls sideways.
        val channelsOnly = row.items.all { it.isChannel }
        val rowHeight = if (channelsOnly) Modifier else Modifier.height(24.dp + cardWidth * 9f / 16f + 12.dp + lines(TITLE_LINE, 2) + lines(CHANNEL_LINE, 1))
        RowScroll {
            LazyRow(
                state = rowState,
                modifier = rowHeight,
                contentPadding = PaddingValues(horizontal = Dimens.Gutter, vertical = if (channelsOnly) 16.dp else 12.dp),
                horizontalArrangement = Arrangement.spacedBy(Dimens.CardGap),
            ) {
                itemsIndexed(row.items, key = { _, video -> video.key }, contentType = { _, video -> if (video.isChannel) 1 else 0 }) { index, video ->
                    VideoCard(
                        video,
                        width = cardWidth,
                        modifier = if (index == firstIndex && firstRequester != null) Modifier.focusRequester(firstRequester) else Modifier,
                        onFocus = {
                            // Near the end: the next page of the row.
                            if (index >= row.items.size - 4) onLoadMore(row)
                        },
                        onClick = { onSelect(video) },
                        onLongClick = { onLongPress(video) },
                    )
                }
            }
        }
    }
}

/** 16:9 thumbnail (duration badge, watched lane), title, then channel and publication date below. */
@Composable
fun VideoCard(
    video: Video,
    modifier: Modifier = Modifier,
    width: androidx.compose.ui.unit.Dp = CARD_WIDTH,
    onFocus: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    if (video.isChannel) return ChannelCard(video, modifier, onFocus, onClick)
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = modifier
                .menuKey(onLongClick)
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .onFocusChanged {
                    isFocused = it.isFocused
                    if (it.isFocused) onFocus()
                },
            shape = ClickableSurfaceDefaults.shape(shape = CardShape),
            // miryo's Opale focus: lifted (1.05) with a soft ring on its edge and a deep shadow.
            scale = ClickableSurfaceDefaults.scale(focusedScale = FocusScale.CardOpale, pressedScale = FocusScale.CardOpale),
            border = ClickableSurfaceDefaults.border(
                focusedBorder = Border(androidx.compose.foundation.BorderStroke(3.dp, Palette.FocusRing.copy(alpha = OPALE_RING_ALPHA)), shape = CardShape),
            ),
            glow = ClickableSurfaceDefaults.glow(
                focusedGlow = androidx.tv.material3.Glow(elevationColor = Palette.Shadow, elevation = 24.dp),
                pressedGlow = androidx.tv.material3.Glow(elevationColor = Palette.Shadow, elevation = 24.dp),
            ),
            colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Tile, focusedContainerColor = Palette.TileFocused),
        ) {
            Box(Modifier.fillMaxSize()) {
                Thumbnail(video.videoId, video.thumbnail, Modifier.fillMaxSize(), custom = video.customThumbnail, small = true, width = width)
                val badge = when {
                    video.live -> "EN DIRECT"
                    video.upcoming -> "BIENTÔT"
                    video.durationMs > 0 -> formatDuration(video.durationMs)
                    else -> video.badge
                }
                if (badge != null) {
                    Text(
                        badge,
                        fontSize = Dimens.TextMin,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = if (video.watchedPercent in 1..99) 14.dp else 8.dp)
                            .background(if (video.live) Palette.Live else Color.Black.copy(alpha = 0.72f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (video.watchedPercent in 1..99) {
                    // Watched: a thin white lane inside the picture (always on the artwork).
                    Box(
                        Modifier
                            .align(Alignment.BottomStart)
                            .padding(horizontal = 9.dp, vertical = 6.dp)
                            .fillMaxWidth()
                            .height(3.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(Color.White.copy(alpha = 0.3f)),
                    ) {
                        Box(Modifier.fillMaxWidth(video.watchedPercent / 100f).fillMaxHeight().background(Color.White))
                    }
                }
                // miryo's shine: one light sweep across the card when it gets the focus.
                CardShine(isFocused)
            }
        }
        Text(
            video.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.TextPrimary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            lineHeight = TITLE_LINE,
            // A fixed height (2 lines), whatever the title holds: minLines alone still let a title with
            // emoji or another script come out a few dp taller, and the whole row (and every row
            // below) moved as such a card scrolled into view. Same for the channel line.
            modifier = Modifier.padding(top = 10.dp).height(lines(TITLE_LINE, 2)),
        )
        // Channel · when it was published (a channel page's cards already say the date alone). A long
        // channel name is cut, never the date after it.
        KeepEndLine(
            start = video.channel,
            end = video.published?.takeUnless { video.channel.contains(it) },
            fontSize = 13.sp,
            lineHeight = CHANNEL_LINE,
            modifier = Modifier.padding(top = 2.dp).height(lines(CHANNEL_LINE, 1)),
        )
    }
}

/** Opale's soft ring on a focused card (the focus color at 35 %). */
private const val OPALE_RING_ALPHA = 0.35f
private const val SHINE_MS = 600
private const val SHINE_ALPHA = 0.16f

/** One soft light sweep across a card when it gains the focus (miryo's shine), nothing otherwise. */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.CardShine(focused: Boolean) {
    val sweep = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(focused) {
        if (focused) {
            sweep.snapTo(0f)
            sweep.animateTo(1f, androidx.compose.animation.core.tween(SHINE_MS))
        }
    }
    if (!focused) return
    Box(
        Modifier.matchParentSize().drawWithContent {
            val progress = sweep.value
            if (progress in 0.001f..0.999f) {
                val x = -size.width * 0.6f + progress * size.width * 2.2f
                drawRect(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                        0f to Color.Transparent,
                        0.5f to Color.White.copy(alpha = SHINE_ALPHA),
                        1f to Color.Transparent,
                        start = androidx.compose.ui.geometry.Offset(x, 0f),
                        end = androidx.compose.ui.geometry.Offset(x + size.width * 0.6f, size.height),
                    ),
                )
            }
        },
    )
}

/**
 * The options keys, when the remote has one, do what a long press does: Menu, Y / Select on a
 * gamepad, the red key and the TV context-menu keys (the same set as miryo and miryo iptv).
 */
private fun Modifier.menuKey(action: (() -> Unit)?): Modifier = if (action == null) this else onPreviewKeyEvent { event ->
    val native = event.nativeKeyEvent
    if (native.keyCode !in OPTIONS_KEYS) return@onPreviewKeyEvent false
    if (native.action == android.view.KeyEvent.ACTION_DOWN && native.repeatCount == 0) action()
    true
}

/**
 * A channel page's header, like miryo's detail page: "CHAÎNE" above its round avatar and name in
 * large, then "S’abonner" / "Abonné" and its orders (Accueil, Plus récentes, Populaires…).
 */
@Composable
private fun ChannelSheet(channel: ChannelHeader, signedIn: Boolean, onSubscribe: () -> Unit, onSort: (Int) -> Unit, onSignIn: () -> Unit, onKid: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(
        // 54 dp under the sorts before the first row (the redesign's room between header and rows).
        modifier.fillMaxWidth().padding(top = 36.dp, bottom = 54.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(Modifier.padding(horizontal = Dimens.Gutter), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            // No picture: the initial, white on a colored gradient (as for accounts).
            AvatarPicture(channel.avatar, channel.name, 66.dp, 28.sp)
            Column {
                Text("CHAÎNE", fontSize = Dimens.TextMin, fontWeight = FontWeight.Bold, letterSpacing = 0.08.em, color = Palette.TextSecondary)
                Text(channel.name, fontSize = 48.sp, lineHeight = 52.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, letterSpacing = (-0.035).em)
            }
        }
        // S'abonner and the sorts scroll when they don't fit (120 %, long names): full width, the
        // gutter inside, so the first pill's zoom isn't clipped at the edge.
        EdgeScroll(Dimens.Gutter) { Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = Dimens.Gutter),
            horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Always there (its place kept while the state loads, so the sorts never shift under the
            // focus): red "S’abonner" (white text, by day too), "Abonné" once subscribed; signed out,
            // OK opens the sign-in.
            val subscribed = signedIn && channel.subscribed == true
            MirazukiButton(
                if (subscribed) "Abonné" else "S’abonner",
                // As wide either way: the sorts don't slide when it switches.
                modifier = Modifier.widthIn(min = SUBSCRIBE_WIDTH),
                icon = if (subscribed) MirazukiIcon.CHECK else MirazukiIcon.PLUS,
                containerColor = if (subscribed) null else Palette.Live,
                contentColor = if (subscribed) Palette.TextPrimary else Color.White,
                onClick = {
                    when {
                        !signedIn -> onSignIn()
                        channel.subscribed != null -> onSubscribe()
                    }
                },
            )
            // The kids profile may watch this channel (a parent decides, from an adult profile).
            if (onKid != null && channel.kidAllowed != null) {
                MirazukiButton(
                    if (channel.kidAllowed) "Autorisée pour Enfant" else "Pour Enfant",
                    icon = if (channel.kidAllowed) MirazukiIcon.CHECK else MirazukiIcon.PLUS,
                    onClick = onKid,
                )
            }
            if (channel.sorts.isNotEmpty()) {
                Spacer(Modifier.width(12.dp))
                SortChip("Accueil", channel.sort == -1) { onSort(-1) }
                channel.sorts.forEachIndexed { index, name -> SortChip(name, channel.sort == index) { onSort(index) } }
            }
        } }
    }
}

/** "S’abonner" with its icon (the wider of the two labels). */
private val SUBSCRIBE_WIDTH = 116.dp

@Composable
private fun SortChip(label: String, selected: Boolean, onClick: () -> Unit) =
    MirazukiButton(label, icon = if (selected) MirazukiIcon.CHECK else null, iconSize = 13.dp, containerColor = if (selected) Palette.Selected else null, contentColor = Palette.ChipText, onClick = onClick)

@Composable
private fun Message(text: String, button: String? = null, buttonModifier: Modifier = Modifier, onClick: () -> Unit = {}) {
    Column(Modifier.padding(start = Dimens.Gutter, end = Dimens.Gutter, top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(text, fontSize = 16.sp, color = Palette.TextSecondary)
        if (button != null) MirazukiButton(button, modifier = buttonModifier, onClick = onClick)
    }
}



/** A channel ("Vos chaînes préférées"…): round avatar and name, smaller than a video card. */
@Composable
private fun ChannelCard(video: Video, modifier: Modifier, onFocus: () -> Unit, onClick: () -> Unit) {
    var isFocused by remember { mutableStateOf(false) }
    Column(Modifier.width(CHANNEL_CARD), horizontalAlignment = Alignment.CenterHorizontally) {
        // The ring (a gap, then the focus color) drawn by this box, as in "Qui regarde ?":
        // tv-material's border landed on the round picture. No picture: the initial on a gradient.
        Box(
            Modifier
                .size(CHANNEL_RING)
                .graphicsLayer {
                    val s = if (isFocused) CHANNEL_FOCUS_SCALE else 1f
                    scaleX = s
                    scaleY = s
                }
                .then(if (isFocused) Modifier.border(2.5.dp, Palette.FocusRing, CircleShape) else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            Surface(
                onClick = onClick,
                modifier = modifier
                    .size(CHANNEL_AVATAR)
                    .onFocusChanged {
                        isFocused = it.isFocused
                        if (it.isFocused) onFocus()
                    },
                shape = ClickableSurfaceDefaults.shape(shape = CircleShape),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(containerColor = Palette.Tile, focusedContainerColor = Palette.Tile),
            ) {
                AvatarPicture(video.thumbnail, video.title, CHANNEL_AVATAR, 36.sp)
            }
        }
        Text(
            video.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = if (isFocused) Palette.TextPrimary else Palette.TextPrimary.copy(alpha = 0.85f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            lineHeight = 17.sp,
            modifier = Modifier.padding(top = 4.dp).height(lines(17.sp, 2)),
        )
    }
}

private val CHANNEL_CARD = 120.dp
private val TITLE_LINE = 18.sp
private val CHANNEL_LINE = 16.sp

/** The height of [count] lines of [lineHeight], in dp (follows the font scale). */
@Composable
private fun lines(lineHeight: androidx.compose.ui.unit.TextUnit, count: Int): androidx.compose.ui.unit.Dp =
    with(androidx.compose.ui.platform.LocalDensity.current) { (lineHeight * count).toDp() }
private val CHANNEL_AVATAR = 96.dp
/** The avatar, a 4 dp gap and the 2.5 dp ring. */
private val CHANNEL_RING = CHANNEL_AVATAR + 13.dp
private const val CHANNEL_FOCUS_SCALE = 1.06f

private val OPTIONS_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_MENU,
    android.view.KeyEvent.KEYCODE_BUTTON_Y,
    android.view.KeyEvent.KEYCODE_BUTTON_SELECT,
    android.view.KeyEvent.KEYCODE_PROG_RED,
    android.view.KeyEvent.KEYCODE_TV_CONTENTS_MENU,
    android.view.KeyEvent.KEYCODE_TV_MEDIA_CONTEXT_MENU,
)
