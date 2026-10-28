package dev.miraiijo.miryotube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Text
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.video.VideoListener
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import dev.miraiijo.miryotube.data.ACCOUNT_CHANGED_MESSAGE
import dev.miraiijo.miryotube.data.ActionResult
import dev.miraiijo.miryotube.data.Details
import dev.miraiijo.miryotube.data.Video
import dev.miraiijo.miryotube.data.VideoRow
import dev.miraiijo.miryotube.data.YouTube
import dev.miraiijo.miryotube.player.MiryoPlayer
import kotlinx.coroutines.launch
import android.view.KeyEvent as AndroidKeyEvent

/**
 * The Shorts page under the tabs: the short where one left off, as a vertical card. Down from the
 * « Shorts » tab lands on it, which starts the Shorts full screen ([ShortsPlayer]).
 */
@Composable
fun ShortsLanding(current: Video?, loading: Boolean, error: String?, onWatch: () -> Unit, onRetry: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(bottom = SafeArea.bottom), contentAlignment = Alignment.Center) {
        when {
            current != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier
                        .weight(1f, fill = false)
                        .aspectRatio(9f / 16f)
                        .clip(CardShape)
                        .onFocusChanged { if (it.isFocused) onWatch() }
                        .focusable(),
                ) {
                    Thumbnail(null, current.thumbnail, Modifier.fillMaxSize())
                }
                Text("Bas pour regarder", fontSize = Dimens.TextMin, color = Palette.TextSecondary)
            }
            error != null -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(error, fontSize = 15.sp, color = Palette.TextSecondary)
                MiryoButton("Réessayer", icon = MiryoIcon.REFRESH, onClick = onRetry)
            }
            // Still loading: focusable all the same, the Shorts open and start once they're here.
            else -> Box(Modifier.size(1.dp).onFocusChanged { if (it.isFocused) onWatch() }.focusable()) {
                if (!loading) Text("Pas de Shorts pour le moment.", fontSize = 15.sp, color = Palette.TextSecondary)
            }
        }
        if (current == null && loading) LoadingDots(Modifier.align(Alignment.Center))
    }
}

/**
 * The Shorts, full screen, one at a time: the vertical video in the middle of the safe zone, its own
 * picture dimmed on the sides. Up / Down: previous / next short (the next one's address is fetched
 * ahead, so Down is quick); OK: pause; Right: the panel (J'aime, Commentaires, the channel,
 * S'abonner), Left or Back closes it; Back (or Up on the first one): back to the « Shorts » tab.
 */
@Composable
fun ShortsPlayer(
    row: VideoRow?,
    index: Int,
    signedIn: Boolean,
    onIndex: (Int) -> Unit,
    onLoadMore: (VideoRow) -> Unit,
    onOpenChannel: (id: String, name: String) -> Unit,
    onNotice: (String) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items = row?.items.orEmpty()
    val current = items.getOrNull(index)
    val player = remember { MiryoPlayer(context) }
    DisposableEffect(player) { onDispose { player.release() } }
    var rendered by remember { mutableStateOf(false) }
    var paused by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var panel by remember { mutableStateOf(false) }
    var comments by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<Details?>(null) }
    var liked by remember { mutableStateOf(false) }
    var subscribed by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<String?>(null) }
    /** Addresses fetched ahead (the next short), by video id. */
    val ahead = remember { HashMap<String, MediaItemFormatInfo>() }
    val focus = remember { FocusRequester() }
    val panelFirst = remember { FocusRequester() }
    val latestIndex by rememberUpdatedState(index)
    val latestItems by rememberUpdatedState(items)
    val latestOnIndex by rememberUpdatedState(onIndex)
    val latestOnExit by rememberUpdatedState(onExit)

    fun go(to: Int) {
        if (to < 0) return latestOnExit()
        if (to >= latestItems.size) return
        panel = false
        comments = false
        latestOnIndex(to)
    }

    LaunchedEffect(current?.videoId) {
        val id = current?.videoId ?: return@LaunchedEffect
        rendered = false
        paused = false
        failed = null
        progress = 0f
        details = null
        val info = ahead.remove(id) ?: runCatching { YouTube.formatInfo(id) }.getOrNull()
        if (info == null) {
            failed = "Ce Short ne peut pas être lu."
        } else {
            player.open(info)?.let { failed = it }
        }
        // The next one's address now, while this one plays.
        items.getOrNull(index + 1)?.videoId?.let { next ->
            if (next !in ahead) runCatching { YouTube.formatInfo(next) }.getOrNull()?.let { ahead[next] = it }
        }
        if (row != null && index >= items.size - LOAD_AHEAD) onLoadMore(row)
    }
    // Comes next by itself at the end, loops on the last one.
    DisposableEffect(player) {
        val listener = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                if (playbackState != Player.STATE_ENDED) return
                if (latestIndex + 1 < latestItems.size) go(latestIndex + 1) else player.exo.seekTo(0)
            }
        }
        val video = object : VideoListener {
            override fun onRenderedFirstFrame() {
                rendered = true
            }
        }
        player.exo.addListener(listener)
        player.exo.addVideoListener(video)
        onDispose {
            player.exo.removeListener(listener)
            player.exo.removeVideoListener(video)
        }
    }
    LaunchedEffect(current?.videoId) {
        while (true) {
            val duration = player.exo.duration
            progress = if (duration > 0) (player.exo.currentPosition.toFloat() / duration).coerceIn(0f, 1f) else 0f
            kotlinx.coroutines.delay(PROGRESS_MS)
        }
    }
    // The panel's state (J'aime, S'abonner) when it opens, for the short shown.
    LaunchedEffect(panel, current?.videoId) {
        if (!panel || details != null) return@LaunchedEffect
        val short = current ?: return@LaunchedEffect
        val id = short.videoId ?: return@LaunchedEffect
        account = YouTube.activeAccountId()
        val loaded = YouTube.details(id, short.raw) ?: return@LaunchedEffect
        details = loaded
        liked = loaded.liked
        subscribed = loaded.subscribed
    }
    LaunchedEffect(Unit) { focus.tryRequestFocus() }
    LaunchedEffect(panel) {
        if (panel) {
            androidx.compose.runtime.withFrameNanos { }
            panelFirst.tryRequestFocus()
        } else {
            focus.tryRequestFocus()
        }
    }

    BackHandler {
        when {
            comments -> comments = false
            panel -> panel = false
            else -> onExit()
        }
    }

    fun pauseOrPlay() {
        paused = !paused
        player.exo.playWhenReady = !paused
    }

    fun toggleLike() {
        val video = current?.videoId ?: return
        val target = !liked
        liked = target
        scope.launch {
            val result = YouTube.setLiked(video, current.raw, target, account)
            if (result != ActionResult.DONE) {
                liked = !target
                if (result == ActionResult.ACCOUNT_CHANGED) onNotice(ACCOUNT_CHANGED_MESSAGE)
            }
        }
    }

    fun toggleSubscribe() {
        val channelId = details?.channelId ?: current?.channelId ?: return
        val target = !subscribed
        subscribed = target
        scope.launch {
            val result = YouTube.setSubscribed(channelId, target, account)
            if (result != ActionResult.DONE) {
                subscribed = !target
                if (result == ActionResult.ACCOUNT_CHANGED) onNotice(ACCOUNT_CHANGED_MESSAGE)
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (panel || comments || native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_DPAD_UP -> { if (native.repeatCount == 0) go(latestIndex - 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (native.repeatCount == 0) go(latestIndex + 1); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { if (current != null) panel = true; true }
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> true
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER,
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, AndroidKeyEvent.KEYCODE_MEDIA_PLAY, AndroidKeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> { if (native.repeatCount == 0) pauseOrPlay(); true }
                    else -> false
                }
            }
            .focusable(),
    ) {
        // The sides: the short's own picture, enlarged and dimmed (blurred where Android can, 12+).
        if (current != null) {
            Thumbnail(null, current.thumbnail, Modifier.fillMaxSize().blur(40.dp))
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.62f)))
        }
        // The short: 9:16, inside the safe zone (never touching the top or the bottom).
        Box(
            Modifier
                .align(Alignment.Center)
                .padding(top = SafeArea.top, bottom = SafeArea.bottom)
                .fillMaxHeight()
                .aspectRatio(9f / 16f)
                .clip(CardShape)
                .background(Color.Black),
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        keepScreenOn = true
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        this.player = player.exo
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            // Until its first frame: the short's picture.
            AnimatedVisibility(visible = !rendered && current != null, enter = fadeIn(tween(0)), exit = fadeOut(tween(250))) {
                Box(Modifier.fillMaxSize()) {
                    if (current != null) Thumbnail(null, current.thumbnail, Modifier.fillMaxSize())
                    if (failed == null) LoadingDots(Modifier.align(Alignment.Center))
                }
            }
            if (current != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                        .padding(horizontal = 14.dp, vertical = 14.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(current.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        if (current.channel.isNotBlank()) Text(current.channel, fontSize = Dimens.TextMin, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(3.dp).background(Palette.Live))
            }
            if (paused) {
                Box(Modifier.align(Alignment.Center).size(56.dp).background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(50)), contentAlignment = Alignment.Center) {
                    Icon(MiryoIcon.PAUSE, size = 24.dp, color = Color.White)
                }
            }
            failed?.let {
                Text(it, fontSize = 15.sp, color = Color.White, modifier = Modifier.align(Alignment.Center).padding(16.dp))
            }
            if (row == null) LoadingDots(Modifier.align(Alignment.Center))
        }

        // The hints: in a small column, clear of the short.
        KeyHintBar(
            screen = "tube.shorts",
            hints = listOf(KeyHint(RemoteKey.UP_DOWN, "Changer"), KeyHint(RemoteKey.RIGHT, "Réactions"), KeyHint(RemoteKey.BACK, "Menu")),
            style = HintStyle.COMPACT,
            enabled = !panel && !comments,
            modifier = Modifier.align(Alignment.BottomEnd).padding(end = SafeArea.side, bottom = SafeArea.bottom),
        )

        AnimatedVisibility(
            visible = panel && !comments && current != null,
            enter = slideInHorizontally(tween(250)) { it } + fadeIn(tween(200)),
            exit = slideOutHorizontally(tween(250)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.CenterEnd),
        ) {
            SafeSidePanel(
                width = SHORTS_PANEL_WIDTH,
                modifier = Modifier.onPreviewKeyEvent { event ->
                    val native = event.nativeKeyEvent
                    if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_LEFT) {
                        panel = false
                        true
                    } else {
                        false
                    }
                },
            ) {
                Text(current?.title.orEmpty(), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Palette.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    if (signedIn) {
                        MiryoButton(
                            "J’aime" + (details?.likes?.let { " · $it" } ?: ""),
                            modifier = Modifier.fillMaxWidth().focusRequester(panelFirst),
                            icon = if (liked) MiryoIcon.THUMB_UP else MiryoIcon.THUMB_UP_OUTLINE,
                            onClick = ::toggleLike,
                        )
                    }
                    if (details?.commentsKey != null) {
                        MiryoButton(
                            "Commentaires",
                            modifier = Modifier.fillMaxWidth().then(if (signedIn) Modifier else Modifier.focusRequester(panelFirst)),
                            icon = MiryoIcon.COMMENT,
                        ) { comments = true }
                    }
                    val channelId = details?.channelId ?: current?.channelId
                    if (channelId != null) {
                        MiryoButton(
                            "Voir la chaîne",
                            modifier = Modifier.fillMaxWidth().then(if (signedIn || details?.commentsKey != null) Modifier else Modifier.focusRequester(panelFirst)),
                            icon = MiryoIcon.USER,
                        ) { onOpenChannel(channelId, details?.channel ?: current?.channel.orEmpty()) }
                        if (signedIn) {
                            MiryoButton(
                                if (subscribed) "Abonné" else "S’abonner",
                                modifier = Modifier.fillMaxWidth(),
                                icon = if (subscribed) MiryoIcon.CHECK else MiryoIcon.PLUS,
                                onClick = ::toggleSubscribe,
                            )
                        }
                    }
                    // Nothing to choose yet (details loading, signed out): the panel holds the focus.
                    if (!signedIn && details == null) {
                        Box(Modifier.size(1.dp).focusRequester(panelFirst).focusable())
                        LoadingDots(Modifier.padding(top = 8.dp))
                    }
                }
            }
        }
        val commentsKey = details?.commentsKey
        if (comments && commentsKey != null) {
            CommentsPanel(commentsKey, Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** The next page of Shorts is asked when this many are left. */
private const val LOAD_AHEAD = 3
private const val PROGRESS_MS = 250L
private val SHORTS_PANEL_WIDTH = 340.dp
