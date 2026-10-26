@file:OptIn(ExperimentalTvMaterial3Api::class, ExperimentalGlideComposeApi::class, ExperimentalComposeUiApi::class)

package dev.miraiijo.lumentube.ui

import android.view.KeyEvent as AndroidKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.Text
import com.bumptech.glide.integration.compose.ExperimentalGlideComposeApi
import com.google.android.exoplayer2.Format
import com.google.android.exoplayer2.Player
import com.google.android.exoplayer2.text.CaptionStyleCompat
import com.google.android.exoplayer2.ui.AspectRatioFrameLayout
import com.google.android.exoplayer2.ui.PlayerView
import com.google.android.exoplayer2.video.VideoListener
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.lumentube.AppScope
import dev.miraiijo.lumentube.data.ACCOUNT_CHANGED_MESSAGE
import dev.miraiijo.lumentube.data.ActionResult
import dev.miraiijo.lumentube.data.Chapter
import dev.miraiijo.lumentube.data.Details
import dev.miraiijo.lumentube.data.Segment
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.player.AutoFrameRate
import dev.miraiijo.lumentube.player.MirazukiPlayer
import dev.miraiijo.lumentube.player.PictureInPicture
import dev.miraiijo.lumentube.remote.RemoteControl
import dev.miraiijo.lumentube.player.PlayerPrefs
import kotlinx.coroutines.delay
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private const val SEEK_STEP_MS = 10_000L
/** Gamepad L2 / R2 on a video without chapters. */
private const val CHAPTERLESS_JUMP_MS = 60_000L
/** Previous chapter within its first 3 s: the one before (else the start of the current one). */
private const val CHAPTER_RESTART_MS = 3_000L

/** Held seek keys speed up: ×1, then ×3, then ×6 (never more). */
private fun seekSpeed(repeatCount: Int) = when {
    repeatCount >= 20 -> 6
    repeatCount >= 8 -> 3
    else -> 1
}
/** Same gap between every button of the controls row (a thumb's count sits inside its own slot). */
private val BUTTON_GAP = 10.dp
/** Room kept on the right of the title: the clock, or the open panel (settings: 400 dp). */
private val PANEL_CLEARANCE = 440.dp
/** The controls' bar and buttons above their bottom inset (the skip notice sits over them). */
private val CONTROLS_HEIGHT = 108.dp
private val CLOCK_CLEARANCE = 130.dp
/** The progress bar's handle when focused, and its halo. */
private val HANDLE = 14.dp
private val HANDLE_HALO = 3.dp
private const val CONTROLS_HIDE_MS = 4_000L
private const val SAVE_EVERY_MS = 30_000L
private const val SKIP_NOTICE_MS = 3_000L
private const val UP_NEXT_SECONDS = 10
private const val SEEK_LINE_MS = 1_500L
/** Pause after the last Left/Right before seeking (the presses in between add up). */
private const val SEEK_COMMIT_MS = 600L
/** adb logcat -s miryo tube.Timing: how long a video takes to start, step by step. */
private const val TIMING_TAG = "MirazukiTube.Timing"
/** First frame (or a new size) to display switch: Auto's first quality change has passed. */
private const val AFR_SETTLE_MS = 1_500L

private fun formatsKind(info: com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo) = when {
    info.containsDashFormats() -> "DASH"
    info.containsSabrFormats() -> "SABR"
    info.isLive -> "live"
    else -> "URL"
}
private val PREVIEW_WIDTH = 256.dp
/** Subtitle height, as a share of the video's height (ExoPlayer's default is 0.0533). */
private const val SUBTITLE_SIZE = 0.033f
/** Subtitles' bottom margin (screen fraction), and the fallback above the seek line (it's measured). */
private const val SUBTITLE_BOTTOM = 0.06f
private const val SUBTITLE_RAISED = 0.34f

/**
 * Full-screen player (the redesign): title, channel and counts top left, the clock top right; at
 * the bottom a thin progress bar, identical round buttons and the time; gradients top and bottom.
 * Keys, like YouTube's TV app: hidden controls → OK, Up or Down shows them (focus on the bar),
 * Left/Right = ±10 s (faster when held). Shown → on the bar Left/Right seek and OK plays / pauses,
 * Down goes to the buttons (never hides), Up goes back up (buttons → bar → hidden). Back closes
 * the panel, then the controls, then the video.
 */
@Composable
fun PlayerScreen(
    videoId: String,
    title: String?,
    channel: String?,
    thumbnail: String?,
    startSeconds: Int,
    /** The card played (playlist context for the next video, like), null for a link. */
    item: MediaItem?,
    signedIn: Boolean,
    onNext: (Video) -> Unit,
    onExit: () -> Unit,
    /** Retour on the video, nothing over it (the system's window if chosen); else as [onExit]. */
    onBack: () -> Unit = onExit,
    /** In-app picture-in-picture: small window in a corner, no controls, browsing goes on. */
    minimized: Boolean = false,
    onMinimize: () -> Unit = {},
    /** Small window → full screen (OK on the small window, which takes the focus like a card). */
    onRestore: () -> Unit = {},
    /** "Voir la chaîne" from the panel: channel id and name. */
    onOpenChannel: (String, String) -> Unit = { _, _ -> },
    /** Where the video is (seconds), told when it pauses for the background: kept across a process kill. */
    onPosition: (Int) -> Unit = {},
    /** "Fréquence d'affichage automatique": shared by the player screens that follow each other (null: no activity). */
    frameRate: AutoFrameRate? = null,
    /** Kids profile: no comments, no channel page, no YouTube "À suivre" (the video ends, the page comes back). */
    kids: Boolean = false,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val isMini by rememberUpdatedState(minimized)
    val playNext by rememberUpdatedState(onNext)
    val tellPosition by rememberUpdatedState(onPosition)
    val context = LocalContext.current
    val prefs = remember { PlayerPrefs(context) }
    val player = remember(videoId) { MirazukiPlayer(context).also { it.setQuality(prefs.quality) } }
    var shownTitle by remember(videoId) { mutableStateOf(title.orEmpty()) }
    var shownChannel by remember(videoId) { mutableStateOf(channel.orEmpty()) }
    var error by remember(videoId) { mutableStateOf<String?>(null) }
    var started by remember(videoId) { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var buffering by remember { mutableStateOf(true) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }
    var buffered by remember { mutableLongStateOf(0L) }
    var live by remember { mutableStateOf(false) }
    var controls by remember { mutableStateOf(true) }
    var activity by remember { mutableIntStateOf(0) }
    var loadTick by remember(videoId) { mutableIntStateOf(0) }
    var retried by remember(videoId) { mutableStateOf(false) }
    /** Seek preview on the progress bar (committed when the arrow is released). */
    var preview by remember { mutableStateOf<Long?>(null) }
    val playPause = remember { FocusRequester() }
    val progress = remember { FocusRequester() }
    val root = remember { FocusRequester() }
    val settingsButton = remember { FocusRequester() }
    val subtitlesButton = remember { FocusRequester() }
    var swallowOkUp by remember { mutableStateOf(false) }
    /** Incremented on each Left/Right with the controls hidden: the time line shows a moment. */
    var seekFlash by remember { mutableIntStateOf(0) }
    var seekLineShown by remember { mutableStateOf(false) }
    /** Measured: the screen and the time line (subtitles and the SponsorBlock notice go above it). */
    var screenPx by remember { mutableIntStateOf(0) }
    var seekLinePx by remember { mutableIntStateOf(0) }
    /** Target of the Left/Right presses not applied yet. */
    var pendingSeek by remember(videoId) { mutableStateOf<Long?>(null) }
    LaunchedEffect(seekFlash) {
        if (seekFlash == 0) return@LaunchedEffect
        // The presses stopped for a moment: one seek to where they lead.
        delay(SEEK_COMMIT_MS)
        pendingSeek?.let {
            player.exo.seekTo(it)
            position = it
        }
        pendingSeek = null
    }
    LaunchedEffect(seekFlash) {
        if (seekFlash == 0) return@LaunchedEffect
        seekLineShown = true
        delay(SEEK_LINE_MS)
        seekLineShown = false
    }
    /** Settings panel level shown (null = closed). */
    var menu by remember(videoId) { mutableStateOf<PlayerMenu?>(null) }
    /** Level the panel was opened on: Settings button = the main list, subtitles button = its list. */
    var menuRoot by remember(videoId) { mutableStateOf(PlayerMenu.MAIN) }
    /** Incremented when the panel closes: the focus goes back to the settings button. */
    var menuClosed by remember { mutableIntStateOf(0) }
    /** Incremented when the tracks change: the panel's lists follow. */
    var tracksTick by remember(videoId) { mutableIntStateOf(0) }
    var subtitlesRestored by remember(videoId) { mutableStateOf(false) }
    var audioRestored by remember(videoId) { mutableStateOf(false) }
    var sponsorBlock by remember { mutableStateOf(prefs.sponsorBlock) }
    var segments by remember(videoId) { mutableStateOf(emptyList<Segment>()) }
    var skipped by remember(videoId) { mutableStateOf(emptySet<Segment>()) }
    var skipNotice by remember(videoId) { mutableStateOf<String?>(null) }
    var details by remember(videoId) { mutableStateOf<Details?>(null) }
    val chapters = details?.chapters.orEmpty()
    var liked by remember(videoId) { mutableStateOf(false) }
    var disliked by remember(videoId) { mutableStateOf(false) }
    /** Estimated dislikes (Return YouTube Dislike), null while unknown. */
    var dislikes by remember(videoId) { mutableStateOf<String?>(null) }
    /** Comments panel open: the video goes on behind it, the controls stay hidden. */
    var commentsOpen by remember(videoId) { mutableStateOf(false) }
    /** Panel just closed: the focus goes back to the comments button, not play / pause. */
    var refocusComments by remember { mutableStateOf(false) }
    val commentsButton = remember { FocusRequester() }
    val infoButton = remember { FocusRequester() }
    val chaptersButton = remember { FocusRequester() }
    var subscribed by remember(videoId) { mutableStateOf(false) }
    /**
     * The account the details (like, subscription) were read for: the actions only go to it. miryo
     * switching the account meanwhile drops them with a message instead of acting as the other one.
     */
    var actionAccount by remember(videoId) { mutableStateOf<String?>(null) }
    var watchLaterDone by remember(videoId) { mutableStateOf(false) }
    var storyboard by remember(videoId) { mutableStateOf<MediaItemStoryboard?>(null) }
    var pictureFormat by remember { mutableIntStateOf(prefs.pictureFormat) }
    /** Video ended with a next one to play: the "À suivre" card counts down. */
    var upNext by remember(videoId) { mutableStateOf<Video?>(null) }
    /** Formats received: the side requests (SponsorBlock, details) start only then, so they never
     *  compete with the one the picture waits for. */
    var infoReady by remember(videoId) { mutableStateOf(false) }
    var openedAt by remember(videoId) { mutableLongStateOf(0L) }
    /** "Statistiques pour les curieux": shown, and how this video was opened (formats, client, timings). */
    var stats by remember { mutableStateOf(prefs.stats) }
    var autoFrameRate by remember { mutableStateOf(prefs.autoFrameRate) }
    var streamInfo by remember(videoId) { mutableStateOf<String?>(null) }
    var formatsMs by remember(videoId) { mutableLongStateOf(-1L) }
    var firstFrameMs by remember(videoId) { mutableLongStateOf(-1L) }
    val scope = rememberCoroutineScope()

    /**
     * Previous / next chapter (gamepad L2 / R2): the start of the next one, or of the current one
     * (or the previous one when just past its start, like a CD player). Without chapters: ±60 s.
     */
    fun jumpChapter(direction: Int) {
        if (live && duration <= 0) return
        val at = player.exo.currentPosition
        val target = if (chapters.isEmpty()) {
            at + direction * CHAPTERLESS_JUMP_MS
        } else if (direction > 0) {
            chapters.firstOrNull { it.startMs > at + 1_000 }?.startMs ?: return
        } else {
            chapters.lastOrNull { it.startMs < at - CHAPTER_RESTART_MS }?.startMs ?: 0L
        }.coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        player.exo.seekTo(target)
        position = target
        seekFlash++
    }

    fun closeMenu() {
        if (menu == null) return
        menu = null
        menuClosed++
    }

    fun show() {
        // The time line is only refreshed while it's shown: up to date as soon as it appears.
        position = player.exo.currentPosition
        buffered = player.exo.bufferedPosition
        controls = true
        activity++
    }

    fun togglePlay() {
        player.exo.playWhenReady = !player.exo.playWhenReady
        show()
    }

    /**
     * Left/Right with the controls hidden: the presses add up into one target (10 s each, faster
     * when the key is held), shown on the time line with its picture; the player seeks once,
     * when the presses stop — not a reload per press.
     */
    fun nudge(direction: Int, repeatCount: Int) {
        if (live && duration <= 0) return
        val step = SEEK_STEP_MS * seekSpeed(repeatCount)
        val from = pendingSeek ?: player.exo.currentPosition.also { position = it }
        pendingSeek = (from + direction * step).coerceIn(0L, (duration - 1_000L).coerceAtLeast(0L))
        seekFlash++
    }

    // Load (and reload after a 403: SmartTube switches to another YouTube client and retries).
    LaunchedEffect(videoId, loadTick) {
        error = null
        val resumeAt = if (loadTick == 0) startSeconds * 1000L else player.exo.currentPosition
        openedAt = android.os.SystemClock.elapsedRealtime()
        val info = runCatching { YouTube.formatInfo(videoId) }.getOrNull()
        formatsMs = android.os.SystemClock.elapsedRealtime() - openedAt
        // Debug builds only: no video id in a release's logs.
        if (dev.miraiijo.lumentube.BuildConfig.DEBUG) android.util.Log.i(TIMING_TAG, "$videoId: formats in $formatsMs ms (${info?.let { formatsKind(it) } ?: "none"})")
        streamInfo = info?.let { listOfNotNull(formatsKind(it), it.clientInfo?.clientName?.takeIf { name -> name.isNotBlank() }).joinToString(" · ") }
        infoReady = true
        when {
            info == null -> error = "YouTube ne répond pas."
            info.isUnplayable -> error = info.playabilityReason?.takeIf { it.isNotBlank() } ?: "Cette vidéo ne peut pas être lue."
            else -> {
                if (shownTitle.isBlank()) shownTitle = info.title.orEmpty()
                if (shownChannel.isBlank()) shownChannel = info.author.orEmpty()
                live = info.isLive
                player.setVolumeLevel(if (prefs.stableVolume) info.volumeLevel else 1f)
                player.open(info, resumeAt)?.let { error = it }
                if (storyboard == null && !info.isLive) storyboard = YouTube.storyboard(info)
            }
        }
    }

    DisposableEffect(player) {
        player.onTracksChanged = {
            tracksTick++
            // Subtitles: the last language chosen, when this video has it (once, at the start).
            // Dubbed video: the original language, not the dub YouTube may have picked by default.
            if (!audioRestored && player.audioTracks().isNotEmpty()) {
                audioRestored = true
                val original = player.originalAudio()
                if (original >= 0 && original != player.playingAudio) player.selectAudio(original)
            }
            val tracks = player.subtitleTracks()
            if (!subtitlesRestored && tracks.isNotEmpty()) {
                subtitlesRestored = true
                // -1 (off) is applied too: no track turns on by itself as the manifest's default.
                player.selectSubtitle(prefs.subtitles?.let(tracks::indexOf) ?: -1)
            }
        }
        player.onError = { e ->
            if (!retried) {
                retried = true
                YouTubeServiceManager.instance().switchNextClient()
                loadTick++
            } else {
                error = e.cause?.message ?: e.message ?: "Erreur de lecture."
            }
        }
        val listener = object : Player.EventListener {
            override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) {
                buffering = playbackState == Player.STATE_BUFFERING || playbackState == Player.STATE_IDLE
                if (playbackState == Player.STATE_READY) {
                    if (!started) {
                        started = true
                        firstFrameMs = android.os.SystemClock.elapsedRealtime() - openedAt
                        if (dev.miraiijo.lumentube.BuildConfig.DEBUG) android.util.Log.i(TIMING_TAG, "$videoId: first frame ready $firstFrameMs ms after the request")
                        // A linked phone follows what plays here.
                        RemoteControl.reportPlaying(videoId, player.exo.currentPosition, player.exo.duration.coerceAtLeast(0L), playWhenReady)
                    }
                    retried = false
                }
                if (playbackState == Player.STATE_ENDED) {
                    val next = details?.next?.takeIf { !kids }
                    when {
                        !prefs.autoplay || next == null -> onExit()
                        // Small window: no card to show, the next one starts right away.
                        isMini -> playNext(next)
                        // Alone on screen: no controls under the card, nothing for the focus to escape to.
                        else -> {
                            controls = false
                            menu = null
                            commentsOpen = false
                            refocusComments = false
                            upNext = next
                        }
                    }
                }
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
                if (started) RemoteControl.reportState(player.exo.currentPosition, player.exo.duration.coerceAtLeast(0L), player.exo.playWhenReady)
            }

            override fun onSeekProcessed() {
                if (started) RemoteControl.reportState(player.exo.currentPosition, player.exo.duration.coerceAtLeast(0L), player.exo.playWhenReady)
            }
        }
        player.exo.addListener(listener)
        // The system's picture-in-picture window plays this player (and its Lecture / Pause follows it).
        PictureInPicture.attach(context, player)
        onDispose {
            PictureInPicture.detach(player)
            val seconds = player.exo.currentPosition / 1000f
            // Outlives the screen (its own scope is cancelled with it).
            AppScope.launch { YouTube.savePosition(videoId, seconds) }
            player.exo.removeListener(listener)
            player.release()
            RemoteControl.reportIdle()
        }
    }

    // The display follows the video's frame rate, once its first frame is on screen and the format
    // has settled (Auto may start low); announced by the manifest, unknown = the TV stays as it is.
    // Declared after the player's effect: disposed before the player is released.
    DisposableEffect(player, autoFrameRate, frameRate) {
        val afr = frameRate
        if (afr == null || !autoFrameRate) {
            afr?.reset()
            return@DisposableEffect onDispose { }
        }
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        val check = Runnable {
            val fps = player.exo.videoFormat?.frameRate ?: Format.NO_VALUE.toFloat()
            if (fps > 0f) afr.apply(fps)
        }
        fun settle() {
            handler.removeCallbacks(check)
            handler.postDelayed(check, AFR_SETTLE_MS)
        }
        val listener = object : VideoListener {
            override fun onRenderedFirstFrame() = settle()

            override fun onVideoSizeChanged(width: Int, height: Int, unappliedRotationDegrees: Int, pixelWidthHeightRatio: Float) = settle()
        }
        player.exo.addVideoListener(listener)
        // Turned on while the video already plays.
        if (player.exo.videoFormat != null) settle()
        onDispose {
            handler.removeCallbacks(check)
            player.exo.removeVideoListener(listener)
        }
    }

    // Remote's Home (or another app on top): pause and save the position; back in the app, the
    // video waits paused with its controls shown.
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, player) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> {
                    player.exo.playWhenReady = false
                    val seconds = player.exo.currentPosition / 1000f
                    tellPosition(seconds.toInt())
                    AppScope.launch { YouTube.savePosition(videoId, seconds) }
                }
                // Not under the comments nor "À suivre" (they keep the screen and the focus).
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (!commentsOpen && upNext == null) show()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // "Regarder sur la TV": the linked phone drives this video (the view model opens / stops videos).
    LaunchedEffect(player) {
        RemoteControl.events.collect { event ->
            when (event) {
                is RemoteControl.Event.Seek -> {
                    player.exo.seekTo(event.positionMs)
                    position = event.positionMs
                }
                // The phone sent the video that's already playing: only its position.
                is RemoteControl.Event.Open -> if (event.videoId == videoId && event.startMs > 0) {
                    player.exo.seekTo(event.startMs)
                    position = event.startMs
                }
                RemoteControl.Event.Play -> player.exo.playWhenReady = true
                RemoteControl.Event.Pause -> player.exo.playWhenReady = false
                RemoteControl.Event.Next -> details?.next?.let(playNext)
                RemoteControl.Event.StateRequest ->
                    RemoteControl.reportPlaying(videoId, player.exo.currentPosition, player.exo.duration.coerceAtLeast(0L), player.exo.playWhenReady)
                else -> Unit
            }
        }
    }

    // Dislikes from Return YouTube Dislike (a third-party service the engine asks with the video id).
    LaunchedEffect(videoId, infoReady) {
        if (!infoReady || live) return@LaunchedEffect
        dislikes = YouTube.dislikes(videoId)
    }

    // SponsorBlock segments and chapters, fetched next to the video (they never delay it).
    LaunchedEffect(videoId, sponsorBlock, infoReady) {
        if (!infoReady) return@LaunchedEffect
        segments = if (sponsorBlock) YouTube.skipSegments(videoId) else emptyList()
    }
    LaunchedEffect(videoId, infoReady) {
        if (!infoReady) return@LaunchedEffect
        actionAccount = YouTube.activeAccountId()
        details = YouTube.details(videoId, item)
        liked = details?.liked == true
        disliked = details?.disliked == true
        subscribed = details?.subscribed == true
    }
    LaunchedEffect(skipNotice) {
        if (skipNotice != null) {
            delay(SKIP_NOTICE_MS)
            skipNotice = null
        }
    }

    // Time line, refreshed only while something shows it (controls, seek line, stats): the player
    // screen isn't recomposed twice a second for nothing. YouTube history saved every 30 s.
    LaunchedEffect(player) {
        var lastSave = 0L
        while (true) {
            val at = player.exo.currentPosition
            val total = player.exo.duration.coerceAtLeast(0L)
            if (total != duration) duration = total
            if ((controls || seekLineShown) && !isMini) {
                position = at
                buffered = player.exo.bufferedPosition
            }
            // SponsorBlock: each segment skipped once (seeking back into it plays it).
            if (sponsorBlock && player.exo.playWhenReady) {
                segments.firstOrNull { at >= it.startMs && at < it.endMs - 500 && it !in skipped }?.let { segment ->
                    skipped = skipped + segment
                    player.exo.seekTo(segment.endMs)
                    position = segment.endMs
                    skipNotice = "Passé : ${segment.label}"
                }
            }
            if (isPlaying && System.currentTimeMillis() - lastSave > SAVE_EVERY_MS) {
                lastSave = System.currentTimeMillis()
                launch { YouTube.savePosition(videoId, at / 1000f) }
            }
            delay(500)
        }
    }

    // Controls hide after a while when playing (never while paused or loading).
    // Read in a flow, not as effect keys: a key press (activity++) doesn't recompose the screen.
    LaunchedEffect(Unit) {
        snapshotFlow { listOf(activity, isPlaying, controls, menu) }.collectLatest {
            if (controls && isPlaying && menu == null) {
                delay(CONTROLS_HIDE_MS)
                controls = false
            }
        }
    }
    // An error closes the panels: nothing stays under its veil, and after "Réessayer" the focus
    // goes back to play / pause.
    LaunchedEffect(error) {
        if (error != null) {
            menu = null
            commentsOpen = false
        }
    }
    // error == null as a key: after "Réessayer" the controls come back with play / pause focused;
    // started: the controls appear with the first frame; commentsOpen: back from the comments.
    LaunchedEffect(controls, minimized, error == null, started, commentsOpen) {
        withFrameNanos { }
        // The comments panel and an open menu keep their own focus (Menu pressed while loading).
        if (upNext != null || minimized || commentsOpen || menu != null) return@LaunchedEffect
        if (controls && refocusComments) {
            refocusComments = false
            if (commentsButton.tryRequestFocus()) return@LaunchedEffect
        }
        // Loading: the controls wait for the first frame, the screen itself holds the remote.
        // Shown: the bar first (Left/Right seek at once), play / pause when there's no bar (live).
        if (controls && started) {
            val barShown = !live || duration > 0
            if (!(barShown && progress.tryRequestFocus())) playPause.tryRequestFocus()
        } else {
            root.tryRequestFocus()
        }
    }
    // "À suivre" counting down when the window shrinks: the next video starts now.
    LaunchedEffect(minimized, upNext) {
        val next = upNext
        if (minimized && next != null) playNext(next)
    }
    // Into the small window: no controls nor panel. Back to full screen: controls shown.
    LaunchedEffect(minimized) {
        if (minimized) {
            menu = null
            commentsOpen = false
            controls = false
        } else {
            show()
        }
    }

    // "J'aime" and "Je n'aime pas" exclude each other (YouTube does the same on its side).
    fun toggleLike() {
        val target = !liked
        val wasDisliked = disliked
        liked = target
        if (target) disliked = false
        scope.launch {
            val result = YouTube.setLiked(videoId, item, target, actionAccount)
            if (result != ActionResult.DONE) {
                liked = !target
                disliked = wasDisliked
                if (result == ActionResult.ACCOUNT_CHANGED) skipNotice = ACCOUNT_CHANGED_MESSAGE
            }
        }
        show()
    }

    fun toggleDislike() {
        val target = !disliked
        val wasLiked = liked
        disliked = target
        if (target) liked = false
        scope.launch {
            val result = YouTube.setDisliked(videoId, item, target, actionAccount)
            if (result != ActionResult.DONE) {
                disliked = !target
                liked = wasLiked
                if (result == ActionResult.ACCOUNT_CHANGED) skipNotice = ACCOUNT_CHANGED_MESSAGE
            }
        }
        show()
    }

    fun openComments() {
        if (kids || details?.commentsKey == null) return
        menu = null
        commentsOpen = true
        controls = false
    }

    fun closeComments() {
        commentsOpen = false
        refocusComments = true
        show()
    }

    fun toggleSubscribe() {
        val channelId = details?.channelId ?: return
        val target = !subscribed
        subscribed = target
        details = details?.copy(subscribed = target)
        scope.launch {
            val result = YouTube.setSubscribed(channelId, target, actionAccount)
            if (result != ActionResult.DONE) {
                subscribed = !target
                details = details?.copy(subscribed = !target)
                if (result == ActionResult.ACCOUNT_CHANGED) skipNotice = ACCOUNT_CHANGED_MESSAGE
            }
        }
    }

    fun addToWatchLater() {
        if (watchLaterDone) return
        watchLaterDone = true
        scope.launch {
            val result = YouTube.watchLater(videoId, actionAccount)
            if (result != ActionResult.DONE) {
                watchLaterDone = false
                if (result == ActionResult.ACCOUNT_CHANGED) skipNotice = ACCOUNT_CHANGED_MESSAGE
            }
        }
    }

    // Back leaves the video once nothing is over it. Declared after (so asked first): the controls,
    // then the panels (settings, comments) — Back closes what's on top, one thing at a time.
    BackHandler(enabled = !minimized) { onBack() }
    BackHandler(enabled = controls && started && error == null && upNext == null && !commentsOpen && !minimized) {
        // The controls go, the video goes on (a seek preview not released is dropped).
        preview = null
        controls = false
    }
    // Declared after: while the panel is open, Back goes up one level, then closes it.
    BackHandler(enabled = menu != null && !minimized) {
        // Up one level, or closed when it's the level the panel was opened on.
        if (menu == menuRoot) closeMenu() else menu = PlayerMenu.MAIN
    }
    // Declared last: with the comments open, Back only closes them (the video goes on).
    BackHandler(enabled = commentsOpen && !minimized) { closeComments() }
    LaunchedEffect(menuClosed) {
        if (menuClosed == 0) return@LaunchedEffect
        withFrameNanos { }
        // Back to the button the panel was opened from (subtitles or settings).
        val origin = when (menuRoot) {
            PlayerMenu.SUBTITLES -> subtitlesButton
            PlayerMenu.INFO -> infoButton
            PlayerMenu.CHAPTERS -> chaptersButton
            else -> null
        }
        if (origin?.tryRequestFocus() == true) return@LaunchedEffect
        settingsButton.tryRequestFocus()
    }

    Box(
        modifier
            .onSizeChanged { screenPx = it.height }
            .background(Color.Black)
            .then(
                // Small window: not focusable, the page keeps the remote.
                if (minimized) Modifier else Modifier
                    // The page under the player never gets the focus.
                    .focusProperties { exit = { FocusRequester.Cancel } }
                    .focusGroup()
                    // The requester sits on the focusable box itself, not on the group: on the group,
                    // a request entered its children, i.e. the controls fading out, and the focus was
                    // lost with them (Back while the progress bar was shown).
                    .focusRequester(root)
                    .focusable(),
            )
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                // Small window: not the player's keys (the page keeps the remote; OK on it restores it).
                if (minimized) return@onPreviewKeyEvent false
                val isOk = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER || native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                    native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                // The OK that showed the controls: its release must not click the play button that
                // just took the focus (it would pause).
                if (native.action == AndroidKeyEvent.ACTION_UP && isOk && swallowOkUp) {
                    swallowOkUp = false
                    return@onPreviewKeyEvent true
                }
                if (native.action != AndroidKeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                // The "À suivre" card, the comments panel and the error box have their own focus
                // (no seek, no settings under the error's veil).
                if (upNext != null || commentsOpen || error != null) return@onPreviewKeyEvent false
                // Media keys (remote, or the gamepad: X, L1 / R1, L2 / R2) and Menu (gamepad Y / Start)
                // act the same with the controls shown or hidden.
                when (native.keyCode) {
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { togglePlay(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PLAY -> { player.exo.playWhenReady = true; show(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PAUSE -> { player.exo.playWhenReady = false; show(); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_REWIND -> { nudge(-1, native.repeatCount); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { nudge(1, native.repeatCount); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_PREVIOUS -> { if (native.repeatCount == 0) jumpChapter(-1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MEDIA_NEXT -> { if (native.repeatCount == 0) jumpChapter(1); return@onPreviewKeyEvent true }
                    AndroidKeyEvent.KEYCODE_MENU -> {
                        if (native.repeatCount == 0 && menu == null) {
                            show()
                            menuRoot = PlayerMenu.MAIN
                            menu = PlayerMenu.MAIN
                        }
                        return@onPreviewKeyEvent true
                    }
                }
                if (controls) {
                    activity++
                    return@onPreviewKeyEvent false
                }
                when (native.keyCode) {
                    // OK only shows the controls (play / pause is the focused button then); the remote's
                    // play-pause key still toggles directly.
                    AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> { swallowOkUp = true; show(); true }
                    // Seek without bringing the buttons (nor their focus): only the time line flashes.
                    AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { nudge(-1, native.repeatCount); true }
                    AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { nudge(1, native.repeatCount); true }
                    // Up / Down: the controls (focus on the bar); nothing ever closes the video but Back.
                    AndroidKeyEvent.KEYCODE_DPAD_UP, AndroidKeyEvent.KEYCODE_DPAD_DOWN -> { if (native.repeatCount == 0) show(); true }
                    else -> false
                }
            },
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    useController = false
                    resizeMode = resizeModeOf(pictureFormat)
                    keepScreenOn = true
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    // Subtitles readable from the couch: white on a translucent band, a bit larger.
                    subtitleView?.apply {
                        setApplyEmbeddedStyles(false)
                        setStyle(
                            CaptionStyleCompat(
                                android.graphics.Color.WHITE,
                                0x99000000.toInt(),
                                android.graphics.Color.TRANSPARENT,
                                CaptionStyleCompat.EDGE_TYPE_NONE,
                                android.graphics.Color.TRANSPARENT,
                                null,
                            ),
                        )
                        setFractionalTextSize(SUBTITLE_SIZE)
                    }
                    this.player = player.exo
                    // Registered after PlayerView's own text output, so these cues are the ones shown:
                    // YouTube's cues carry positions (auto captions often "left, 0 %"), which put them
                    // off-center or off-screen. Rebuilt as plain cues: centered, at the bottom.
                    val centered = CenteredCues()
                    val view = subtitleView
                    player.exo.addTextOutput { cues -> view?.setCues(centered.of(cues)) }
                }
            },
            update = {
                it.player = player.exo
                it.resizeMode = resizeModeOf(pictureFormat)
                // Never under the buttons: hidden with the controls, raised above the time line.
                it.subtitleView?.visibility = if (controls && started && !commentsOpen && !minimized && error == null) android.view.View.INVISIBLE else android.view.View.VISIBLE
                // Above the time line as it is measured (it grows with the interface size and the preview).
                val raised = if (screenPx > 0 && seekLinePx > 0) (seekLinePx.toFloat() / screenPx).coerceIn(SUBTITLE_BOTTOM, 0.7f) else SUBTITLE_RAISED
                it.subtitleView?.setBottomPaddingFraction(if (seekLineShown && !controls) raised else SUBTITLE_BOTTOM)
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Before the first frame: the thumbnail, dimmed.
        AnimatedVisibility(visible = !started && error == null, enter = fadeIn(), exit = fadeOut(tween(400))) {
            Box(Modifier.fillMaxSize()) {
                // The card's own picture (same address: already in Glide's cache, not asked again).
                Thumbnail(videoId, thumbnail, Modifier.fillMaxSize(), small = true)
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)))
                // Loading: the dimmed picture and the dots only (the controls come with the first frame).
                LoadingDots(Modifier.align(Alignment.Center))
            }
        }
        if (started && buffering && error == null) LoadingDots(Modifier.align(Alignment.Center))

        AnimatedVisibility(visible = controls && started && error == null && !minimized && !commentsOpen, enter = fadeIn(tween(150)), exit = fadeOut(tween(250))) {
            val chapterIndex = chapters.indexOfLast { it.startMs <= (preview ?: position) }
            val chapter = chapters.getOrNull(chapterIndex)?.title?.takeIf { it.isNotBlank() }?.let { "Chapitre ${chapterIndex + 1} : $it" }
            Controls(
                title = shownTitle,
                channel = listOfNotNull(shownChannel.takeIf { it.isNotBlank() }, chapter).joinToString(" · "),
                panelOpen = menu != null,
                openedFrom = if (menu != null) menuRoot else null,
                isPlaying = isPlaying || (player.exo.playWhenReady && buffering),
                live = live,
                position = preview ?: position,
                duration = duration,
                buffered = buffered,
                playPause = playPause,
                progress = progress,
                onPlayPause = ::togglePlay,
                onPreview = { delta ->
                    preview = ((preview ?: position) + delta).coerceIn(0L, duration.coerceAtLeast(0L))
                    activity++
                },
                onCommit = {
                    preview?.let { player.exo.seekTo(it); position = it }
                    preview = null
                },
                onHide = { controls = false },
                segments = segments,
                chapters = chapters,
                hasSubtitles = remember(tracksTick) { player.subtitleTracks().isNotEmpty() },
                settingsButton = settingsButton,
                subtitlesButton = subtitlesButton,
                onSubtitles = {
                    menuRoot = PlayerMenu.SUBTITLES
                    menu = PlayerMenu.SUBTITLES
                },
                onSettings = {
                    menuRoot = PlayerMenu.MAIN
                    menu = PlayerMenu.MAIN
                },
                onMinimize = onMinimize,
                canRate = signedIn,
                likes = details?.likes,
                dislikes = dislikes,
                liked = liked,
                disliked = disliked,
                onLike = ::toggleLike,
                onDislike = ::toggleDislike,
                hasComments = !kids && details?.commentsKey != null,
                commentsButton = commentsButton,
                onComments = ::openComments,
                hasInfo = details != null,
                infoButton = infoButton,
                onInfo = {
                    menuRoot = PlayerMenu.INFO
                    menu = PlayerMenu.INFO
                },
                hasChapters = chapters.isNotEmpty(),
                chaptersButton = chaptersButton,
                onChapters = {
                    menuRoot = PlayerMenu.CHAPTERS
                    menu = PlayerMenu.CHAPTERS
                },
                storyboard = storyboard,
                previewing = preview != null,
            )
        }

        // Left/Right with the controls hidden: the time line alone, at the bottom, not focusable.
        AnimatedVisibility(
            visible = seekLineShown && !controls && !minimized && error == null,
            enter = fadeIn(tween(100)),
            exit = fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter).onSizeChanged { seekLinePx = it.height },
        ) {
            SeekLine(pendingSeek ?: position, duration, buffered, storyboard = storyboard.takeIf { pendingSeek != null })
        }

        // SponsorBlock: what was just skipped, bottom left, above the buttons or the time line (as
        // measured), clear of the title and the stats (top); hidden while the bar shows a preview.
        val seekLineTop = with(androidx.compose.ui.platform.LocalDensity.current) { seekLinePx.toDp() } + 12.dp
        AnimatedVisibility(
            visible = skipNotice != null && !minimized && preview == null,
            enter = fadeIn(),
            exit = fadeOut(tween(400)),
            // Above the controls: their bottom inset (the safe zone) + bar and buttons (108 dp).
            modifier = Modifier.align(Alignment.BottomStart).padding(start = SafeArea.side, bottom = if (controls) SafeArea.bottom + CONTROLS_HEIGHT else if (seekLineShown) seekLineTop else SafeArea.bottom),
        ) {
            Text(
                skipNotice.orEmpty(),
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                modifier = Modifier.background(Color.Black.copy(alpha = 0.7f), ControlShape).padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }

        menu?.let { level ->
            PlayerMenuPanel(
                menu = level,
                player = player,
                prefs = prefs,
                live = live,
                tracksTick = tracksTick,
                sponsorBlock = sponsorBlock,
                onSponsorBlock = {
                    sponsorBlock = it
                    prefs.sponsorBlock = it
                },
                pictureFormat = pictureFormat,
                onPictureFormat = {
                    pictureFormat = it
                    prefs.pictureFormat = it
                },
                actions = VideoActions(
                    details = details,
                    signedIn = signedIn,
                    watchLaterDone = watchLaterDone,
                    onSubscribe = ::toggleSubscribe,
                    onWatchLater = ::addToWatchLater,
                    onChannel = {
                        val id = details?.channelId
                        if (id != null && !kids) {
                            closeMenu()
                            onOpenChannel(id, details?.channel ?: shownChannel)
                        }
                    },
                    stats = stats,
                    onStats = {
                        stats = !stats
                        prefs.stats = stats
                    },
                    autoFrameRate = autoFrameRate,
                    onAutoFrameRate = {
                        autoFrameRate = !autoFrameRate
                        prefs.autoFrameRate = autoFrameRate
                    },
                    chapters = chapters,
                    currentChapter = chapters.indexOfLast { it.startMs <= position },
                    onChapter = { chapter ->
                        player.exo.seekTo(chapter.startMs)
                        position = chapter.startMs
                    },
                    kids = kids,
                ),
                // A choice made in a list opened directly (subtitles button) closes the panel.
                onMenu = { next -> if (next == null || (next == PlayerMenu.MAIN && menuRoot != PlayerMenu.MAIN)) closeMenu() else menu = next },
                modifier = Modifier.align(Alignment.CenterEnd),
            )
        }

        val commentsKey = details?.commentsKey
        if (commentsOpen && commentsKey != null && !minimized) {
            CommentsPanel(commentsKey, Modifier.align(Alignment.CenterEnd))
        }

        // With the controls hidden only (the redesign's "stats" state): never over the title, the
        // bar or a settings panel; width capped so it stays clear of the comments panel too.
        if (stats && !minimized && !(controls && started)) {
            StatsOverlay(
                player = player,
                videoId = videoId,
                streamInfo = streamInfo,
                formatsMs = formatsMs,
                firstFrameMs = firstFrameMs,
                modifier = Modifier.align(Alignment.TopStart).padding(top = SafeArea.top, start = SafeArea.side).widthIn(max = 340.dp),
            )
        }

        upNext?.takeIf { !minimized }?.let { next ->
            UpNextCard(
                next,
                onPlay = { onNext(next) },
                onCancel = onExit,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = SafeArea.side, bottom = SafeArea.bottom),
            )
        }

        // Small window: focusable like a card (reached with Down / Right from the page); OK brings
        // it back full screen. Remotes without a play key need this.
        if (minimized) {
            var miniFocused by remember { mutableStateOf(false) }
            androidx.tv.material3.Surface(
                onClick = onRestore,
                modifier = Modifier.fillMaxSize().onFocusChanged { miniFocused = it.isFocused },
                shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(shape = RoundedCornerShape(0.dp)),
                scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    focusedContainerColor = Color.Transparent,
                    pressedContainerColor = Color.Transparent,
                ),
                border = androidx.tv.material3.ClickableSurfaceDefaults.border(
                    focusedBorder = androidx.tv.material3.Border(androidx.compose.foundation.BorderStroke(3.dp, Color.White)),
                ),
            ) {
                Box(Modifier.fillMaxSize()) {
                }
            }
        }

        if (error != null && minimized) {
            Text("Lecture impossible", fontSize = 14.sp, color = Color.White, modifier = Modifier.align(Alignment.Center))
        } else if (error != null) {
            // A veil over the video, the box on it (its width capped: a long message wraps; inside
            // the safe zone, scrolling rather than overflowing at 120 %).
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)))
            SafeDialog(
                560.dp,
                Modifier.background(Palette.Panel.copy(alpha = 0.95f), DialogShape).padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("Lecture impossible", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text(error.orEmpty(), fontSize = 15.sp, color = Palette.TextSecondary, textAlign = TextAlign.Center)
                val retry = remember { FocusRequester() }
                Row(horizontalArrangement = Arrangement.spacedBy(Dimens.PillGap)) {
                    MirazukiButton("Réessayer", modifier = Modifier.focusRequester(retry)) {
                        retried = false
                        YouTubeServiceManager.instance().switchNextClientNow()
                        loadTick++
                    }
                    MirazukiButton("Fermer", onClick = onExit)
                }
                LaunchedEffect(Unit) {
                    withFrameNanos { }
                    retry.tryRequestFocus()
                }
            }
        }
    }
}

@Composable
private fun Controls(
    title: String,
    channel: String,
    /** A panel (settings, chapters, about) is open on the right: the title stops before it. */
    panelOpen: Boolean,
    /** The level the open panel was opened on: its button stays white (null: no panel). */
    openedFrom: PlayerMenu?,
    isPlaying: Boolean,
    live: Boolean,
    position: Long,
    duration: Long,
    buffered: Long,
    playPause: FocusRequester,
    progress: FocusRequester,
    onPlayPause: () -> Unit,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onHide: () -> Unit,
    segments: List<Segment>,
    chapters: List<Chapter>,
    hasSubtitles: Boolean,
    settingsButton: FocusRequester,
    subtitlesButton: FocusRequester,
    onSubtitles: () -> Unit,
    onSettings: () -> Unit,
    /** Signed in: the thumbs can be pressed; otherwise only their counts are shown (top). */
    canRate: Boolean,
    likes: String?,
    dislikes: String?,
    liked: Boolean,
    disliked: Boolean,
    onLike: () -> Unit,
    onDislike: () -> Unit,
    hasComments: Boolean,
    commentsButton: FocusRequester,
    onComments: () -> Unit,
    hasInfo: Boolean,
    infoButton: FocusRequester,
    onInfo: () -> Unit,
    hasChapters: Boolean,
    chaptersButton: FocusRequester,
    onChapters: () -> Unit,
    storyboard: MediaItemStoryboard?,
    previewing: Boolean,
    onMinimize: () -> Unit,
) {
    val likeButton = remember { FocusRequester() }
    val dislikeButton = remember { FocusRequester() }
    val pipButton = remember { FocusRequester() }
    /** The last button focused: Down from the progress bar goes back to it (play / pause first). */
    var lastButton by remember { mutableStateOf(playPause) }
    // A button can go away (another video arrives, without chapters or comments…): Down from the
    // bar must not aim at a requester no longer attached.
    LaunchedEffect(hasSubtitles, hasChapters, canRate, hasComments, hasInfo) { lastButton = playPause }
    fun Modifier.button(requester: FocusRequester) = focusRequester(requester).onFocusChanged { if (it.isFocused) lastButton = requester }
    // Live without a seekable window: no bar above the buttons, so Up hides the controls.
    val barShown = !live || duration > 0
    val barUp = if (barShown) progress else null
    val upHide = if (barShown) null else onHide
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxWidth().height(150.dp).align(Alignment.TopCenter).background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent))))
        Box(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter).background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))

        // Top right corner: the clock, centred on the title's first line (hidden under an open panel).
        if (!panelOpen) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = SafeArea.top, end = SafeArea.side)
                    .height(with(androidx.compose.ui.platform.LocalDensity.current) { 23.sp.toDp() }),
                contentAlignment = Alignment.CenterEnd,
            ) { Clock() }
        }

        // Top left (the redesign): the whole title (2 lines at most), then the channel and the
        // chapter, then the thumbs' counts. It stops before the clock, or before the open panel.
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .padding(start = SafeArea.side, top = SafeArea.top, end = if (panelOpen) PANEL_CLEARANCE else CLOCK_CLEARANCE),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(title, fontSize = 18.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                if (channel.isNotBlank()) {
                    Text(channel, fontSize = 13.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
                if (live) {
                    Text(
                        "EN DIRECT",
                        fontSize = Dimens.TextMin,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.background(Palette.Live, RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                }
                if (likes != null || dislikes != null) {
                    val countColor = Color.White.copy(alpha = 0.72f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (likes != null) {
                            Icon(if (liked) MirazukiIcon.THUMB_UP else MirazukiIcon.THUMB_UP_OUTLINE, size = 13.dp, color = countColor)
                            Text(likes, fontSize = 13.sp, color = countColor, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 5.dp))
                        }
                        if (likes != null && dislikes != null) Spacer(Modifier.width(15.dp))
                        if (dislikes != null) {
                            Icon(if (disliked) MirazukiIcon.THUMB_DOWN else MirazukiIcon.THUMB_DOWN_OUTLINE, size = 13.dp, color = countColor)
                            Text(dislikes, fontSize = 13.sp, color = countColor, maxLines = 1, softWrap = false, modifier = Modifier.padding(start = 5.dp))
                        }
                    }
                }
            }
        }

        // Bottom: the progress bar, the buttons and the time, nothing else.
        Column(
            Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(start = SafeArea.side, end = SafeArea.side, top = 28.dp, bottom = SafeArea.bottom),
        ) {
            if (barShown) {
                // Seek preview: the picture at the aimed position, above the bar.
                if (previewing && storyboard != null && duration > 0) {
                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val fraction = (position.toFloat() / duration).coerceIn(0f, 1f)
                        val x = (maxWidth * fraction - PREVIEW_WIDTH / 2).coerceIn(0.dp, maxWidth - PREVIEW_WIDTH)
                        Column(Modifier.offset(x = x).width(PREVIEW_WIDTH), horizontalAlignment = Alignment.CenterHorizontally) {
                            StoryboardFrame(storyboard, position, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(ControlShape).background(Color.Black))
                            Text(formatTime(position), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                }
                ProgressBar(position, duration, buffered, progress, down = lastButton, onPreview = onPreview, onCommit = onCommit, onClick = onPlayPause, onUp = onHide, segments = segments, chapters = chapters)
                Spacer(Modifier.height(8.dp))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(horizontalArrangement = Arrangement.spacedBy(BUTTON_GAP), verticalAlignment = Alignment.CenterVertically) {
                    // Ordered by use, most used first, one even gap between identical round buttons:
                    // playback (play, subtitles, chapters) · the video (thumbs, comments, about) ·
                    // window and settings (always last). ±10 s: Left / Right, or the progress bar.
                    ControlButton(if (isPlaying) MirazukiIcon.PAUSE else MirazukiIcon.PLAY, Modifier.button(playPause), up = barUp, onUp = upHide, onClick = onPlayPause)
                    if (hasSubtitles) ControlButton(MirazukiIcon.SUBTITLES, Modifier.button(subtitlesButton), up = barUp, onUp = upHide, highlighted = openedFrom == PlayerMenu.SUBTITLES, onClick = onSubtitles)
                    if (hasChapters) ControlButton(MirazukiIcon.CHAPTERS, Modifier.button(chaptersButton), up = barUp, onUp = upHide, highlighted = openedFrom == PlayerMenu.CHAPTERS, onClick = onChapters)
                    if (canRate) {
                        ControlButton(if (liked) MirazukiIcon.THUMB_UP else MirazukiIcon.THUMB_UP_OUTLINE, Modifier.button(likeButton), up = barUp, onUp = upHide, active = liked, onClick = onLike)
                        ControlButton(if (disliked) MirazukiIcon.THUMB_DOWN else MirazukiIcon.THUMB_DOWN_OUTLINE, Modifier.button(dislikeButton), up = barUp, onUp = upHide, active = disliked, onClick = onDislike)
                    }
                    if (hasComments) ControlButton(MirazukiIcon.COMMENT, Modifier.button(commentsButton), up = barUp, onUp = upHide, onClick = onComments)
                    if (hasInfo) ControlButton(MirazukiIcon.INFO, Modifier.button(infoButton), up = barUp, onUp = upHide, highlighted = openedFrom == PlayerMenu.INFO, onClick = onInfo)
                    // Small window in a corner: keep watching while browsing ("Lecture en cours" brings it back).
                    ControlButton(MirazukiIcon.PIP, Modifier.button(pipButton), up = barUp, onUp = upHide, onClick = onMinimize)
                    ControlButton(MirazukiIcon.SETTINGS, Modifier.button(settingsButton), up = barUp, onUp = upHide, highlighted = openedFrom == PlayerMenu.MAIN, onClick = onSettings)
                }
                Text(
                    if (live && duration <= 0) "En direct" else "${formatTime(position)} / ${formatTime(duration)}",
                    fontSize = 15.sp,
                    color = Color.White.copy(alpha = 0.9f),
                )
            }
        }
    }
}

/** Nuvio's round icon button: translucent, white with a black icon when focused. */
@Composable
private fun ControlButton(
    icon: MirazukiIcon,
    modifier: Modifier = Modifier,
    up: FocusRequester? = null,
    /** Up with nothing above (live without a bar): this instead (hide the controls). */
    onUp: (() -> Unit)? = null,
    onDown: (() -> Unit)? = null,
    /** On (liked…): a light fill when not focused. */
    active: Boolean = false,
    /** Its panel is open (the focus is in the panel): drawn as if focused. */
    highlighted: Boolean = false,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .then(if (up != null) Modifier.focusProperties { this.up = up } else Modifier)
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                // Down: the bottom row, nothing under it — kept here (never hides the controls nor
                // lets the focus slip out of the player).
                if (native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN) {
                    onDown?.invoke()
                    true
                } else if (onUp != null && native.action == AndroidKeyEvent.ACTION_DOWN && native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_UP) {
                    onUp()
                    true
                } else {
                    false
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = if (highlighted) Color.White else if (active) Color.White.copy(alpha = 0.3f) else Color.White.copy(alpha = 0.12f),
            focusedContainerColor = Color.White,
            contentColor = if (highlighted) Color.Black else Color.White,
            focusedContentColor = Color.Black,
        ),
        shape = IconButtonDefaults.shape(shape = CircleShape),
    ) {
        Icon(icon, size = 24.dp)
    }
}

/** Thin bar (buffered + played), thicker with a round handle when focused: Left/Right preview a seek, released = seek. */
@Composable
private fun ProgressBar(
    position: Long,
    duration: Long,
    buffered: Long,
    requester: FocusRequester,
    down: FocusRequester,
    onPreview: (Long) -> Unit,
    onCommit: () -> Unit,
    onClick: () -> Unit,
    onUp: () -> Unit,
    segments: List<Segment>,
    chapters: List<Chapter>,
) {
    var focused by remember { mutableStateOf(false) }
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val loaded = if (duration > 0) (buffered.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shownPlayed by animateFloatAsState(played, tween(100), label = "played")
    val height by animateFloatAsState(if (focused) 7f else 4f, tween(150), label = "bar-height")
    Box(
        Modifier
            .fillMaxWidth()
            // Tall enough for the handle and its halo (20 dp), drawn outside the clipped bar.
            .height(20.dp)
            .focusRequester(requester)
            .focusProperties { this.down = down }
            .onFocusChanged { focused = it.isFocused }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                when (native.action) {
                    AndroidKeyEvent.ACTION_DOWN -> when (native.keyCode) {
                        // Held: faster, like Left / Right with the controls hidden (×3, then ×6 at most).
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> { onPreview(-SEEK_STEP_MS * seekSpeed(native.repeatCount)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onPreview(SEEK_STEP_MS * seekSpeed(native.repeatCount)); true }
                        AndroidKeyEvent.KEYCODE_DPAD_CENTER, AndroidKeyEvent.KEYCODE_ENTER, AndroidKeyEvent.KEYCODE_NUMPAD_ENTER -> { if (native.repeatCount == 0) onClick(); true }
                        AndroidKeyEvent.KEYCODE_DPAD_UP -> { onUp(); true }
                        else -> false
                    }
                    AndroidKeyEvent.ACTION_UP -> when (native.keyCode) {
                        AndroidKeyEvent.KEYCODE_DPAD_LEFT, AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> { onCommit(); true }
                        else -> false
                    }
                    else -> false
                }
            }
            .focusable(),
        contentAlignment = Alignment.CenterStart,
    ) {
        val barShape = RoundedCornerShape(50)
        Box(Modifier.fillMaxWidth().height(height.dp).clip(barShape).background(Color.White.copy(alpha = 0.22f))) {
            Box(Modifier.fillMaxWidth(loaded).fillMaxHeight().background(Color.White.copy(alpha = 0.35f)))
            Box(Modifier.fillMaxWidth(shownPlayed).fillMaxHeight().background(Color.White))
            if (duration > 0 && (segments.isNotEmpty() || chapters.size > 1)) {
                Canvas(Modifier.matchParentSize()) {
                    // SponsorBlock segments in yellow, chapter starts as small gaps.
                    segments.forEach { segment ->
                        val start = size.width * (segment.startMs.toFloat() / duration).coerceIn(0f, 1f)
                        val end = size.width * (segment.endMs.toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(Palette.Sponsor, topLeft = Offset(start, 0f), size = Size(end - start, size.height))
                    }
                    val gap = 2.dp.toPx()
                    chapters.drop(1).forEach { chapter ->
                        val x = size.width * (chapter.startMs.toFloat() / duration).coerceIn(0f, 1f)
                        drawRect(Color.Black.copy(alpha = 0.6f), topLeft = Offset(x - gap / 2, 0f), size = Size(gap, size.height))
                    }
                }
            }
        }
        if (focused) {
            // The handle at the played position: a white dot and a soft halo, outside the clip.
            Canvas(Modifier.matchParentSize()) {
                val center = Offset(size.width * shownPlayed, size.height / 2)
                drawCircle(Color.White.copy(alpha = 0.3f), radius = (HANDLE / 2 + HANDLE_HALO).toPx(), center = center)
                drawCircle(Color.White, radius = (HANDLE / 2).toPx(), center = center)
            }
        }
    }
}

@Composable
private fun Clock() {
    var now by remember { mutableStateOf(LocalTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = LocalTime.now()
            delay(10_000)
        }
    }
    Text(now.format(DateTimeFormatter.ofPattern("HH:mm")), fontSize = 15.sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.92f))
}

/** Three pulsing dots (loading). */
@Composable
private fun LoadingDots(modifier: Modifier = Modifier) {
    var phase by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(300)
            phase = (phase + 1) % 3
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(3) { i ->
            Box(Modifier.size(10.dp).clip(CircleShape).background(Color.White.copy(alpha = if (i == phase) 1f else 0.35f)))
        }
    }
}

private fun formatTime(ms: Long): String = formatDuration(ms).ifEmpty { "0:00" }

/** "À suivre": the next video, played when the countdown ends (OK: now, Annuler: back to the page). */
@Composable
private fun UpNextCard(next: Video, onPlay: () -> Unit, onCancel: () -> Unit, modifier: Modifier = Modifier) {
    var seconds by remember(next.key) { mutableIntStateOf(UP_NEXT_SECONDS) }
    val playNow = remember { FocusRequester() }
    LaunchedEffect(next.key) {
        withFrameNanos { }
        playNow.tryRequestFocus()
        while (seconds > 0) {
            delay(1_000)
            seconds--
        }
        onPlay()
    }
    // Compact, bottom right: the end of the video stays visible around it.
    Row(
        modifier
            // The focus stays on the card ("Lire" / "Annuler") until it goes.
            .focusProperties { exit = { FocusRequester.Cancel } }
            .focusGroup()
            .background(Palette.Panel.copy(alpha = 0.95f), DialogShape)
            .padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(144.dp).aspectRatio(16f / 9f).clip(ControlShape).background(Palette.Tile)) {
            Thumbnail(next.videoId, next.thumbnail, Modifier.fillMaxSize(), small = true, width = 144.dp)
        }
        // 12 + 144 + 14 + 198 + 12 = 380 dp (the redesign).
        Column(Modifier.width(198.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text("À suivre · $seconds s", fontSize = Dimens.TextMin, fontWeight = FontWeight.SemiBold, color = Palette.TextSecondary)
            Text(next.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp, color = Palette.TextPrimary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                MirazukiButton("Lire", modifier = Modifier.focusRequester(playNow), onClick = onPlay)
                MirazukiButton("Annuler", onClick = onCancel)
            }
        }
    }
}

/**
 * The storyboard picture at [positionMs]: one tile of YouTube's sprite sheets. Each sheet is
 * decoded once at its real size (a few kept in memory) and the tile is drawn out of it: holding
 * Left / Right no longer decodes a whole sheet per frame, and the tile is cut from the actual
 * bitmap size (no shift if the server's sheet differs from the declared one).
 */
@Composable
private fun StoryboardFrame(storyboard: MediaItemStoryboard, positionMs: Long, modifier: Modifier) {
    val size = storyboard.groupSize
    val groupMs = storyboard.groupDurationMS
    if (size == null || groupMs <= 0 || size.durationEachMS <= 0 || size.colCount <= 0 || size.rowCount <= 0) return
    val group = (positionMs / groupMs).toInt()
    val index = ((positionMs % groupMs) / size.durationEachMS).toInt().coerceIn(0, size.colCount * size.rowCount - 1)
    val context = LocalContext.current
    val sheets = remember(storyboard) { SheetCache(context) }
    var sheet by remember(storyboard) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    LaunchedEffect(storyboard, group) {
        sheet = sheets.get(group, storyboard.getGroupUrl(group)) ?: sheet
    }
    // Always laid out (black until the sheet arrives): the time and title don't jump when it does.
    Canvas(modifier) {
        val image = sheet ?: return@Canvas
        val tileW = image.width / size.colCount
        val tileH = image.height / size.rowCount
        if (tileW <= 0 || tileH <= 0) return@Canvas
        drawImage(
            image,
            srcOffset = androidx.compose.ui.unit.IntOffset((index % size.colCount) * tileW, (index / size.colCount) * tileH),
            srcSize = androidx.compose.ui.unit.IntSize(tileW, tileH),
            dstSize = androidx.compose.ui.unit.IntSize(this.size.width.toInt(), this.size.height.toInt()),
        )
    }
}

/** The last storyboard sheets decoded (3 at most: the one shown and its neighbours). */
private class SheetCache(private val context: android.content.Context) {
    private val sheets = object : LinkedHashMap<Int, androidx.compose.ui.graphics.ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, androidx.compose.ui.graphics.ImageBitmap>?) = size > 3
    }

    suspend fun get(group: Int, url: String?): androidx.compose.ui.graphics.ImageBitmap? {
        sheets[group]?.let { return it }
        if (url == null) return null
        val bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                com.bumptech.glide.Glide.with(context).asBitmap().load(url)
                    .override(com.bumptech.glide.request.target.Target.SIZE_ORIGINAL)
                    .submit().get()
            }.getOrNull()
        } ?: return null
        return bitmap.asImageBitmap().also { sheets[group] = it }
    }
}

private fun resizeModeOf(pictureFormat: Int) = when (pictureFormat) {
    1 -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    2 -> AspectRatioFrameLayout.RESIZE_MODE_FILL
    else -> AspectRatioFrameLayout.RESIZE_MODE_FIT
}

/** Time line alone (seeking with the controls hidden): bar and time, nothing to focus. */
@Composable
private fun SeekLine(position: Long, duration: Long, buffered: Long, storyboard: MediaItemStoryboard?) {
    val played = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)))),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(Modifier.fillMaxWidth().padding(start = SafeArea.side, end = SafeArea.side, top = 28.dp, bottom = SafeArea.bottom)) {
            // The picture at the target, above its place on the bar.
            if (storyboard != null && duration > 0) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val x = (maxWidth * played - PREVIEW_WIDTH / 2).coerceIn(0.dp, maxWidth - PREVIEW_WIDTH)
                    StoryboardFrame(storyboard, position, Modifier.offset(x = x).width(PREVIEW_WIDTH).aspectRatio(16f / 9f).clip(ControlShape).background(Color.Black))
                }
                Spacer(Modifier.height(12.dp))
            }
            // The bar, then the time under it on the right (the redesign).
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.3f))) {
                Box(Modifier.fillMaxWidth(played).fillMaxHeight().background(Color.White))
            }
            Spacer(Modifier.height(7.dp))
            Text("${formatTime(position)} / ${formatTime(duration)}", fontSize = 15.sp, color = Color.White.copy(alpha = 0.9f), modifier = Modifier.align(Alignment.End))
        }
    }
}

/**
 * YouTube cues rebuilt without their position (as SmartTube does): centered at the bottom. Auto
 * captions repeat the previous line in each cue ("rolling" captions); that part is cut, so each
 * line shows once.
 */
private class CenteredCues {
    private var previous: String? = null

    fun of(cues: List<com.google.android.exoplayer2.text.Cue>): List<com.google.android.exoplayer2.text.Cue> = cues.mapNotNull { cue ->
        val text = cue.text?.toString() ?: return@mapNotNull null
        val shown = when {
            // WebVTT rolling line: kept for the next cue, not shown twice.
            text.endsWith("\n") || text.endsWith(" ") -> {
                previous = text
                return@mapNotNull null
            }
            text.contains("\n") -> {
                val kept = previous?.takeIf { text.contains(it) }?.let { text.replace(it, "").replace("\n", "") } ?: text
                val lines = text.split("\n")
                previous = if (lines.size == 2) lines[1] else text
                kept
            }
            else -> {
                val kept = previous?.let { text.replace(it, "") } ?: text
                previous = kept
                kept
            }
        }
        if (shown.isBlank()) null else com.google.android.exoplayer2.text.Cue(shown)
    }
}

/**
 * "Statistiques pour les curieux", top left over the video (not focusable): what's played, how it
 * was opened and how the box copes. Read again every second.
 */
@Composable
private fun StatsOverlay(player: MirazukiPlayer, videoId: String, streamInfo: String?, formatsMs: Long, firstFrameMs: Long, modifier: Modifier) {
    var lines by remember { mutableStateOf(emptyList<Pair<String, String>>()) }
    LaunchedEffect(player, streamInfo, formatsMs, firstFrameMs) {
        while (true) {
            lines = buildList {
                add("Vidéo n°" to videoId)
                streamInfo?.let { add("Flux · client" to it) }
                if (formatsMs >= 0) add("Démarrage" to "formats ${formatsMs} ms" + if (firstFrameMs >= 0) " · image ${firstFrameMs} ms" else "")
                addAll(player.stats())
            }
            delay(1_000)
        }
    }
    Column(
        modifier
            .background(Color.Black.copy(alpha = 0.72f), ControlShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        lines.forEach { (label, value) ->
            Row {
                Text(label, fontSize = Dimens.TextMin, color = Color.White.copy(alpha = 0.65f), modifier = Modifier.width(120.dp))
                Text(value, fontSize = Dimens.TextMin, color = Color.White, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
