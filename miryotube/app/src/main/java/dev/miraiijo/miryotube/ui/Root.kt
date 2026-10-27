@file:OptIn(ExperimentalComposeUiApi::class)

package dev.miraiijo.miryotube.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import dev.miraiijo.miryotube.MainViewModel
import dev.miraiijo.miryotube.player.PictureInPicture

/**
 * The redesign's layout (miryo's): a header with the section's name, the sections as tabs and the
 * round buttons (search, account, settings), the section's rows under it. Search and opened
 * channels / playlists take the whole screen. A video plays full screen, or in a small window.
 */
@Composable
fun Root(vm: MainViewModel) {
    val state by vm.state.collectAsState()
    // The page's rows, rebuilt only when what they show changes (state.page builds a new one each
    // time it's read): a notice, the player or a dialog doesn't recompose every card.
    val page = remember(state.section, state.pages, state.opened, state.search, state.resume, state.liveNow, state.deArrow, state.subsFilter, state.hiddenChannels, state.folders, state.kids) { state.page }
    val content = remember { FocusRequester() }
    val header = remember { FocusRequester() }
    // Restarted by its own update: Réglages › Mises à jour open again.
    LaunchedEffect(Unit) { vm.reopenAfterUpdate() }
    // Incremented when a page should take the focus (section opened from the menu, channel opened).
    // Starts at 1: at launch, Accueil takes the focus as soon as its rows arrive.
    var pageFocusTick by remember { mutableIntStateOf(1) }
    /** The remote's mic / search key: Rechercher opens and starts the dictation (once). */
    var dictate by remember { androidx.compose.runtime.mutableStateOf(false) }
    // Incremented when the player closes: the focus goes back to the card that was played.
    var returnFocusTick by remember { mutableIntStateOf(0) }
    // Incremented when the video goes to the small window: the focus goes to « Lecture en cours »
    // in the header (Back then OK brings it back full screen), the page when there's no header.
    var miniFocusTick by remember { mutableIntStateOf(0) }
    // Incremented on "Pas intéressé": the focus moves to the next card (else the previous one)
    // before the card goes away; without it, the focus was lost.
    var neighbourTick by remember { mutableIntStateOf(0) }
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current

    // A page about to be replaced (channel / playlist opened, or closed): its focused card goes
    // away while the next one loads. This invisible holder keeps the focus meanwhile (otherwise it
    // fell into the side menu, which opened and closed); it's only focusable while holding.
    val holder = remember { FocusRequester() }
    var holding by remember { androidx.compose.runtime.mutableStateOf(false) }
    fun holdFocus() {
        holding = true
        if (!holder.tryRequestFocus()) holding = false
    }
    val select: (dev.miraiijo.miryotube.data.Video) -> Unit = remember(vm) {
        { video ->
            if (!video.isVideo || video.videoId == null) holdFocus()
            vm.select(video)
        }
    }

    val Search = dev.miraiijo.miryotube.data.Section.SEARCH
    val Home = dev.miraiijo.miryotube.data.Section.HOME
    /** The section shown before Rechercher (Back from the search returns to it). */
    var beforeSearch by remember { androidx.compose.runtime.mutableStateOf(Home) }
    /** The focus is in the header (tabs, round buttons). */
    var inHeader by remember { androidx.compose.runtime.mutableStateOf(false) }
    /** OK on a tab (or Back to Accueil from the tabs): the focus stays in the tabs (one can still change one's mind). */
    var tabFocusTick by remember { mutableIntStateOf(0) }
    fun showSection(section: dev.miraiijo.miryotube.data.Section, focusPage: Boolean = true) {
        if (section == Search && state.section != Search) beforeSearch = state.section
        vm.show(section)
        if (focusPage) {
            // The page takes the focus (the header may not even be there: Rechercher).
            inHeader = false
            pageFocusTick++
        } else {
            tabFocusTick++
        }
    }
    // The header (tabs) is shown on the sections' pages, not on Rechercher nor an opened channel.
    val showHeader = state.opened == null && state.section != Search

    // Back: channel / playlist → its page; Rechercher → the section before; rows → the tabs;
    // tabs → Accueil; then, with a video in the small window, the last Back closes it; then
    // « Retour encore pour quitter » (3 s), as in miryo iptv: a single Back never leaves the app.
    val fullScreenPlayer = (state.playingId != null || state.playing != null) && !state.minimized
    // Into the small window and back: a short veil in the page's colour while the video changes
    // size (no jump of the picture, nor of the header that gains « Lecture en cours »). Cheaper
    // than animating the size on the box: the video's surface is resized once, under the veil.
    var miniLayout by remember { mutableStateOf(state.minimized) }
    val veil = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(state.minimized, (state.playingId != null || state.playing != null)) {
        if (state.minimized == miniLayout) return@LaunchedEffect
        if ((state.playingId == null && state.playing == null) || PictureInPicture.active) {
            miniLayout = state.minimized
            veil.snapTo(0f)
            return@LaunchedEffect
        }
        veil.animateTo(1f, tween(VEIL_IN_MS))
        miniLayout = state.minimized
        // The new size laid out and drawn (surface resized) before the veil lifts.
        withFrameNanos { }
        withFrameNanos { }
        veil.animateTo(0f, tween(VEIL_OUT_MS))
    }
    val exitActivity = androidx.compose.ui.platform.LocalContext.current.findActivity()
    var exitArmed by remember { androidx.compose.runtime.mutableStateOf(false) }
    LaunchedEffect(exitArmed) {
        if (exitArmed) {
            kotlinx.coroutines.delay(EXIT_WINDOW_MS)
            exitArmed = false
        }
    }
    LaunchedEffect(state.section, inHeader, state.opened) { if (state.section != Home || !inHeader || state.opened != null) exitArmed = false }
    BackHandler(enabled = !fullScreenPlayer) {
        when {
            state.opened != null -> {
                holdFocus()
                vm.closeOpened()
                pageFocusTick++
            }
            state.section == Search -> {
                holdFocus()
                showSection(beforeSearch)
            }
            !inHeader -> header.tryRequestFocus()
            state.section != Home -> showSection(Home, focusPage = false)
            state.minimized -> vm.stopPlaying()
            exitArmed -> exitActivity?.finish()
            else -> {
                exitArmed = true
                vm.showNotice("Retour encore pour quitter")
            }
        }
    }

    // Time of the last held-arrow step let through (event clock, ms): see HOLD_STEP_MS.
    val heldStep = remember { LongArray(1) }

    Box(
        Modifier
            .fillMaxSize()
            .miryoBackground()
            // Shortcut: the remote's play / pause key brings the small window back to full screen.
            .onPreviewKeyEvent { event ->
                // Profile switch: nothing moves under the cover (it lifts within 1.5 s).
                if (state.switchingTo != null) return@onPreviewKeyEvent true
                val native = event.nativeKeyEvent
                if (native.action != android.view.KeyEvent.ACTION_DOWN) return@onPreviewKeyEvent false
                // An arrow held down (like YouTube's TV app): the focus walks on at a steady pace,
                // one card every HOLD_STEP_MS, one row every HOLD_ROW_MS, each scroll done before the
                // next. The remote's own repeat (~20 a second) piled up scrolls the Freebox Player
                // POP couldn't keep up with. Not over the full-screen video: there, a held arrow
                // seeks faster and faster. (Réglages and the other dialogs are windows of their own:
                // they keep the remote's repeat.)
                if (native.repeatCount > 0 && native.keyCode in ARROW_KEYS && !fullScreenPlayer) {
                    val vertical = native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_UP || native.keyCode == android.view.KeyEvent.KEYCODE_DPAD_DOWN
                    val step = if (vertical) HOLD_ROW_MS else HOLD_STEP_MS
                    if (native.eventTime - heldStep[0] < step) return@onPreviewKeyEvent true
                    heldStep[0] = native.eventTime
                }
                // Search key (gamepad Select): straight to Rechercher, from any page (not over the video).
                // The remote's mic (SEARCH or VOICE_ASSIST): Rechercher, and the dictation right away.
                val voice = native.keyCode == android.view.KeyEvent.KEYCODE_VOICE_ASSIST
                if ((native.keyCode == android.view.KeyEvent.KEYCODE_SEARCH || voice) && !fullScreenPlayer && !state.kids) {
                    if (native.repeatCount == 0) {
                        if (state.section != Search || state.opened != null) showSection(Search)
                        if (voice || !native.isFromSource(android.view.InputDevice.SOURCE_GAMEPAD)) dictate = true
                    }
                    return@onPreviewKeyEvent true
                }
                // ◀◀ / ▶▶ on the remote (L1 / R1 on a gamepad): previous / next section, the focus on
                // its tab, stopping at the ends; the same keys as in miryo and miryo iptv.
                if (native.keyCode in PAGE_KEYS && showHeader && !fullScreenPlayer) {
                    if (native.repeatCount == 0) {
                        val sections = if (state.kids) listOf(Home) else dev.miraiijo.miryotube.data.Section.entries.filter { it != Search && (!it.account || state.signedIn) }
                        val forward = native.keyCode == android.view.KeyEvent.KEYCODE_BUTTON_R1 || native.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
                        val index = sections.indexOf(state.section)
                        val next = if (index < 0) null else sections.getOrNull(index + if (forward) 1 else -1)
                        if (next != null) showSection(next, focusPage = false)
                    }
                    return@onPreviewKeyEvent true
                }
                if (!state.minimized) return@onPreviewKeyEvent false
                when (native.keyCode) {
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
                    android.view.KeyEvent.KEYCODE_MEDIA_PLAY,
                    android.view.KeyEvent.KEYCODE_MEDIA_PAUSE,
                    -> {
                        vm.restore()
                        true
                    }
                    else -> false
                }
            },
    ) {
        Column(Modifier.fillMaxSize()) {
        if (showHeader) {
            TopHeader(
                section = state.section,
                signedIn = state.signedIn,
                accountName = state.accountName,
                accountAvatar = state.accountAvatar,
                minimized = miniLayout,
                modifier = Modifier.focusRequester(header),
                onFocusChange = { inHeader = it },
                // A tab: the focus stays on the tabs (Down goes to the videos); the search button: Rechercher.
                onSection = { showSection(it, focusPage = it == Search) },
                tabFocusTick = tabFocusTick,
                onAccount = vm::openAccounts,
                onSettings = vm::openSettings,
                onRestorePlayer = vm::restore,
                appearance = state.settings.appearance,
                // Jour → Nuit → Auto (→ Comme miryo when miryo is there) → Jour (0 = Auto, 1 = Jour, 2 = Nuit, 3 = Comme miryo).
                onAppearance = {
                    val next = when (state.settings.appearance) { 1 -> 2; 2 -> 0; 0 -> if (dev.miraiijo.miryotube.link.MiryoLook.current != null) 3 else 1; else -> 1 }
                    vm.updateSettings(state.settings.copy(appearance = next))
                },
                updateFound = state.update.found,
                kids = state.kids,
            )
        }
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .focusRequester(content)
                // Down from the tabs: back to the card you left.
                .focusRestorer(),
        ) {
            Box(
                Modifier
                    .size(1.dp)
                    .focusProperties { canFocus = holding }
                    .focusRequester(holder)
                    .onFocusChanged { if (!it.isFocused && holding) holding = false }
                    .focusable(),
            )
            // Each layer of the fade draws its own snapshot: the page leaving keeps its data (it
            // showed the incoming page's rows, or "Rien à afficher", for the 220 ms), and only the
            // incoming one gets new focus ticks.
            val view = PageView(state.section, state.opened, page, state.query, state.suggestions, state.searchOptions, pageFocusTick, state.focusKey)
            val pageKey = state.section to state.opened?.key
            // Économes: the page changes at once (no two pages drawn together for 220 ms).
            Crossfade(targetState = pageKey, animationSpec = if (dev.miraiijo.miryotube.system.LowEnd.reduced(androidx.compose.ui.platform.LocalContext.current)) androidx.compose.animation.core.snap() else tween(220), label = "pages") { layerKey ->
                // The current page follows the state; the one fading out keeps the last view it
                // had while it was current (a plain holder: no recomposition when it's written).
                val last = remember { arrayOfNulls<PageView>(1) }
                val current = layerKey == pageKey
                if (current) last[0] = view
                val shown = if (current) view else last[0] ?: view
                if (shown.section == dev.miraiijo.miryotube.data.Section.SEARCH && shown.opened == null) {
                    SearchPage(
                        query = shown.query,
                        page = shown.page,
                        focusTick = shown.focusTick,
                        onSearch = vm::search,
                        onSelect = select,
                        onLoadMore = vm::loadMore,
                        suggestions = shown.suggestions,
                        onTyping = vm::suggest,
                        options = shown.options,
                        onOptions = vm::setSearchOptions,
                        onLongPress = vm::openCardMenu,
                        dictate = dictate,
                        onDictated = { dictate = false },
                    )
                } else {
                    BrowsePage(
                        page = shown.page,
                        title = shown.opened?.title,
                        focusTick = shown.focusTick,
                        focusKey = shown.focusKey,
                        onSelect = select,
                        onLoadMore = vm::loadMore,
                        // The opened channel / playlist reloads itself, not the section under it.
                        onRetry = vm::retry,
                        needsAccount = shown.section.account && !state.signedIn && shown.opened == null && !state.kids,
                        onSignIn = vm::startSignIn,
                        onLongPress = vm::openCardMenu,
                        onKid = if (state.kidsExists && !state.kids) vm::toggleKidChannel else null,
                        // Kids profile: the rows come back when a parent allows a channel.
                        showRetry = !state.kids || state.kidChannels.isNotEmpty(),
                        channel = shown.opened?.channel,
                        signedIn = state.signedIn,
                        onSubscribe = vm::toggleChannelSubscription,
                        onSort = vm::chooseChannelSort,
                        // Up to the tabs before the rows arrived: the rows don't take the focus back.
                        focusElsewhere = { inHeader },
                        // Accueil: a taste of each feed once its end is reached (the view model
                        // ignores it on any other page).
                        onEndReached = { vm.loadHomeExtras() },
                        onFolder = shown.opened?.channel?.let { c -> { vm.openFolderMenu(dev.miraiijo.miryotube.data.ChannelRef(c.id, c.name)) } },
                        // Abonnements: its channels' pastilles and filters above the rows.
                        header = if (shown.section == dev.miraiijo.miryotube.data.Section.SUBSCRIPTIONS && shown.opened == null && state.signedIn && !state.kids) {
                            { first ->
                                SubscriptionsHeader(
                                    pastilles = state.pastilles,
                                    filter = state.subsFilter,
                                    folders = state.folders,
                                    onPastille = vm::choosePastille,
                                    onFilter = vm::setSubsFilter,
                                    first = first,
                                )
                            }
                        } else null,
                    )
                }
            }
        }
        }
        ProfileSwitchCover(state.switchingTo)
        // Sign-in and pairing open over the dialog they come from ("Qui regarde ?", Réglages): a
        // dialog is its own window, the one composed last is on top.
        if (state.choosingAccount || state.accountsOpen) {
            ProfileDialog(
                accounts = state.accounts,
                onChoose = vm::chooseAccount,
                onAdd = vm::startSignIn,
                onGuest = vm::chooseGuest,
                guest = !state.signedIn,
                onKids = vm::chooseKids,
                kidsExists = state.kidsExists,
                onDismiss = { if (state.choosingAccount) vm.skipAccountChoice() else vm.closeAccounts() },
                signingIn = state.signingIn,
            )
        } else if (state.settingsOpen) {
            SettingsDialog(
                settings = state.settings,
                accountName = state.accountName.takeIf { state.signedIn },
                onSettings = vm::updateSettings,
                onPair = vm::openPairing,
                onSignOut = vm::signOut,
                onDismiss = vm::closeSettings,
                onSignIn = { vm.closeSettings(); vm.startSignIn() },
                pairingOpen = state.pairing != null,
                update = state.update,
                onCheckUpdate = vm::checkUpdate,
                onInstallUpdate = vm::installUpdate,
                onWantsBeta = vm::setWantsBeta,
                kidChannels = state.kidChannels.takeIf { state.kidsExists },
                onRemoveKidChannel = vm::removeKidChannel,
                onChangeKidsPin = vm::changeKidsPin,
                kidsMiryoCode = state.kidsMiryoCode,
                onDeleteKids = vm::deleteKids,
                startOnUpdates = state.settingsOnUpdates,
                onStarted = vm::settingsOnUpdatesShown,
                hiddenChannels = state.hiddenChannels,
                onUnhideChannel = vm::unhideChannel,
                folders = state.folders,
                onDeleteFolder = vm::deleteFolder,
            )
        } else if (state.cardMenu != null) {
            val video = state.cardMenu!!
            CardMenuDialog(
                video = video,
                signedIn = state.signedIn,
                canGiveFeedback = remember(video.key) { dev.miraiijo.miryotube.data.YouTube.hasFeedback(video) },
                onPlay = {
                    vm.closeCardMenu()
                    vm.select(video)
                },
                onWatchLater = { vm.cardWatchLater(video) },
                onChannel = {
                    holdFocus()
                    vm.cardOpenChannel(video)
                    pageFocusTick++
                },
                onNotInterested = { channel ->
                    // The card is about to leave its row: the focus goes to its neighbour first
                    // (once the menu is closed and the focus is back on the card).
                    neighbourTick++
                    vm.cardNotInterested(video, channel)
                },
                queued = state.queue.size,
                onQueue = { vm.cardQueue(video) },
                onClearQueue = vm::clearQueue,
                onHideChannel = {
                    neighbourTick++
                    vm.cardHideChannel(video)
                },
                onFolder = video.channelId?.let { id -> { vm.openFolderMenu(dev.miraiijo.miryotube.data.ChannelRef(id, video.channel.ifBlank { video.title })) } },
                onDismiss = vm::closeCardMenu,
            )
        } else if (state.folderFor != null) {
            val channel = state.folderFor!!
            FolderDialog(
                channel = channel,
                folders = state.folders,
                onFile = { name -> vm.fileChannel(channel, name) },
                onDismiss = vm::closeFolderMenu,
            )
        }
        // The parental code opens over "Qui regarde ?" and Réglages (composed after them: on top).
        state.pin?.let { prompt ->
            PinDialog(prompt.title, prompt.hint, key = prompt, submit = { vm.submitPin(it) }, onDismiss = vm::closePin)
        }
        // New install: one question, the YouTube account (closes once an account is linked).
        val setupContext = androidx.compose.ui.platform.LocalContext.current
        var firstRun by remember { androidx.compose.runtime.mutableStateOf(FirstRun.pending(setupContext)) }
        val closeFirstRun = {
            FirstRun.finish(setupContext)
            firstRun = false
        }
        LaunchedEffect(state.signedIn) { if (state.signedIn && firstRun) closeFirstRun() }
        if (firstRun && !state.signingIn && state.playing == null) {
            val withMiryo = remember { dev.miraiijo.miryotube.link.MiryoLink.partner(setupContext, dev.miraiijo.miryotube.link.MiryoLink.MIRYO, dev.miraiijo.miryotube.link.MiryoLink.PROFILES_AUTHORITY) }
            FirstRunTube(
                withMiryo,
                animations = state.settings.animations,
                onAnimations = { vm.updateSettings(state.settings.copy(animations = it)) },
                onSignIn = vm::startSignIn,
                onDone = closeFirstRun,
            )
        }
        if (state.signingIn) {
            SignInDialog(code = state.signInCode, error = state.signInError, onRetry = vm::startSignIn, onDismiss = vm::cancelSignIn)
        }
        state.pairing?.let { pairing ->
            PairingDialog(pairing, onRetry = vm::openPairing, onDismiss = vm::closePairing)
        }
        // The video plays over the pages (they stay composed: Back returns to the same card).
        val playing = state.playing
        val playingId = state.playingId ?: playing?.videoId
        // "Fréquence d'affichage automatique": one for all the videos that follow each other (no
        // switch between equivalent rates); the TV's own mode comes back once no video plays.
        val activity = androidx.compose.ui.platform.LocalContext.current.findActivity()
        val frameRate = remember(activity) { activity?.let { dev.miraiijo.miryotube.player.AutoFrameRate(it) } }
        androidx.compose.runtime.DisposableEffect(frameRate, playingId != null) {
            onDispose { frameRate?.reset() }
        }
        if (playingId != null) {
            // One player screen per video: everything (PlayerView, subtitle output, listeners) starts
            // fresh for the next one ("À suivre" included).
            androidx.compose.runtime.key(playingId) { PlayerScreen(
                videoId = playingId,
                title = playing?.title,
                channel = playing?.channel,
                thumbnail = playing?.thumbnail,
                startSeconds = playing?.let { resumeSeconds(it) } ?: state.playingStart,
                item = playing?.raw,
                signedIn = state.signedIn,
                onNext = vm::playNext,
                queued = state.queue.firstOrNull(),
                onExit = {
                    AudioOnly.on = false
                    vm.stopPlaying()
                    returnFocusTick++
                    // The video ended in the system's window with nothing after it: the window goes
                    // (never the pages in a corner of the screen).
                    if (PictureInPicture.active) PictureInPicture.dismiss()
                },
                // Retour closes the player and stays in miryo tube (the bar's button gives the small window).
                onBack = {
                    AudioOnly.on = false
                    vm.stopPlaying()
                    returnFocusTick++
                },
                // In the system's window too: no controls nor panels, only the video.
                minimized = state.minimized || PictureInPicture.active,
                onMinimize = {
                    vm.minimize()
                    miniFocusTick++
                },
                onRestore = vm::restore,
                onOpenChannel = { id, name ->
                    vm.openChannelFromPlayer(id, name)
                    returnFocusTick++
                },
                onPosition = { seconds -> vm.notePosition(playingId, seconds) },
                frameRate = frameRate,
                kids = state.kids,
                modifier = if (miniLayout && !PictureInPicture.active) {
                    // A third of the screen's width, whatever the interface size (a fixed 400 dp
                    // took half the screen at 120 %), inside the TV's safe zone.
                    Modifier.align(Alignment.BottomEnd).padding(end = SafeArea.side, bottom = SafeArea.bottom).fillMaxWidth(MINI_FRACTION).aspectRatio(16f / 9f).shadow(30.dp)
                } else {
                    Modifier.fillMaxSize()
                },
            ) }
        }
        if (veil.value > 0f) Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil.value }.background(Palette.Background))
        // With the small window in the bottom right corner, the notice moves to the bottom left.
        if (playingId != null && state.minimized) {
            Notice(state.notice, Modifier.align(Alignment.BottomStart).padding(start = SafeArea.side, bottom = SafeArea.bottom))
        } else {
            Notice(state.notice, Modifier.align(Alignment.BottomCenter).padding(bottom = SafeArea.bottom))
        }
    }
    LaunchedEffect(returnFocusTick) {
        if (returnFocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!content.tryRequestFocus()) header.tryRequestFocus()
    }
    LaunchedEffect(neighbourTick) {
        if (neighbourTick == 0) return@LaunchedEffect
        // Two frames: the menu's window goes, the focus comes back to the card.
        withFrameNanos { }
        withFrameNanos { }
        if (!focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Right)) focusManager.moveFocus(androidx.compose.ui.focus.FocusDirection.Left)
    }
    LaunchedEffect(miniFocusTick) {
        if (miniFocusTick == 0) return@LaunchedEffect
        withFrameNanos { }
        if (!showHeader || !header.tryRequestFocus()) content.tryRequestFocus()
    }
}

/** Width of the in-app picture-in-picture window (16:9), a share of the screen's. */
private const val MINI_FRACTION = 0.33f

/** The veil over the video changing size (into the small window and back). */
private const val VEIL_IN_MS = 140
private const val VEIL_OUT_MS = 220

/** A held Left / Right's pace: one card every 120 ms (about 8 a second), as in miryo and miryo iptv. */
private const val HOLD_STEP_MS = 120L

/** Time to press Back again to leave the app (as in miryo iptv). */
private const val EXIT_WINDOW_MS = 3_000L

/** Previous / next section: ◀◀ / ▶▶ on the remote, L1 / R1 on a gamepad. */
private val PAGE_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_MEDIA_REWIND,
    android.view.KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
    android.view.KeyEvent.KEYCODE_BUTTON_L1,
    android.view.KeyEvent.KEYCODE_BUTTON_R1,
)

/**
 * A held Up / Down's pace: one row every 280 ms (about 3.5 a second). Slower than along a row:
 * each new row brings a whole row of thumbnails to decode, which the box needs time for.
 */
private const val HOLD_ROW_MS = 280L

private val ARROW_KEYS = setOf(
    android.view.KeyEvent.KEYCODE_DPAD_UP,
    android.view.KeyEvent.KEYCODE_DPAD_DOWN,
    android.view.KeyEvent.KEYCODE_DPAD_LEFT,
    android.view.KeyEvent.KEYCODE_DPAD_RIGHT,
)

/** What a page of the fade shows: a snapshot, so the page leaving keeps its own data. */
private data class PageView(
    val section: dev.miraiijo.miryotube.data.Section,
    val opened: dev.miraiijo.miryotube.Opened?,
    val page: dev.miraiijo.miryotube.Page,
    val query: String,
    val suggestions: List<String>,
    val options: Int,
    val focusTick: Int,
    val focusKey: String? = null,
)

/** Where a video resumes: YouTube's saved position, else its watched share (not when finished). */
private fun resumeSeconds(video: dev.miraiijo.miryotube.data.Video): Int {
    val saved = video.raw.startTimeSeconds
    if (saved > 0) return saved
    val percent = video.watchedPercent
    return if (percent in 3..94 && video.durationMs > 0) (video.durationMs / 1000 * percent / 100).toInt() else 0
}

/** The activity behind a Compose context (null if there is none). */
private tailrec fun android.content.Context.findActivity(): android.app.Activity? = when (this) {
    is android.app.Activity -> this
    is android.content.ContextWrapper -> baseContext.findActivity()
    else -> null
}
