package dev.miraiijo.lumentube

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import dev.miraiijo.lumentube.data.ACCOUNT_CHANGED_MESSAGE
import dev.miraiijo.lumentube.data.Account
import dev.miraiijo.lumentube.data.ActionResult
import dev.miraiijo.lumentube.data.DeArrow
import dev.miraiijo.lumentube.data.Section
import dev.miraiijo.lumentube.data.Video
import dev.miraiijo.lumentube.data.VideoRow
import dev.miraiijo.lumentube.data.YouTube
import dev.miraiijo.lumentube.kids.KidChannel
import dev.miraiijo.lumentube.kids.KidsStore
import dev.miraiijo.lumentube.kids.MirazukiProfile
import dev.miraiijo.lumentube.kids.PinResult
import dev.miraiijo.lumentube.player.PlayerPrefs
import dev.miraiijo.lumentube.remote.RemoteControl
import dev.miraiijo.lumentube.ui.UI_SCALE_DEFAULT
import dev.miraiijo.lumentube.update.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import io.reactivex.disposables.Disposable
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A page's content: loading, rows, or an error message. */
@androidx.compose.runtime.Immutable
data class Page(val rows: List<VideoRow> = emptyList(), val loading: Boolean = true, val error: String? = null)

/** A channel or playlist opened from a card, shown over the section. [channel]: a channel page's header. */
@androidx.compose.runtime.Immutable
data class Opened(
    val key: String,
    val title: String,
    val page: Page,
    val channel: ChannelHeader? = null,
    val from: Video? = null,
    val homeRows: List<VideoRow> = emptyList(),
    val sortRows: List<VideoRow> = emptyList(),
)

/**
 * A channel page's header: avatar, name, "S'abonner" ([subscribed] null: unknown or signed out) and
 * its orders ([sorts]: "Plus récentes", "Populaires"…; [sort] -1 = the channel's own page).
 */
@androidx.compose.runtime.Immutable
data class ChannelHeader(
    val id: String,
    val name: String,
    val avatar: String?,
    val subscribed: Boolean? = null,
    val sorts: List<String> = emptyList(),
    val sort: Int = -1,
    /** Allowed for the kids profile (null: no kids profile, no button). */
    val kidAllowed: Boolean? = null,
)

/** A step of the parental code dialog (kids profile). */
enum class PinStep { CREATE, CONFIRM, UNLOCK, CHANGE_CHECK, CHANGE_NEW, CHANGE_CONFIRM, DELETE_CHECK }

/** The parental code dialog: its step, the code typed first (to confirm it), a note instead of the hint. */
data class PinPrompt(val step: PinStep, val first: String? = null, val note: String? = null) {
    val title: String
        get() = when (step) {
            PinStep.CREATE -> "Profil enfant : choisis un code parent"
            PinStep.CONFIRM, PinStep.CHANGE_CONFIRM -> "Confirme le code parent"
            PinStep.UNLOCK -> "Quitter le profil enfant"
            PinStep.CHANGE_CHECK, PinStep.DELETE_CHECK -> "Code parent actuel"
            PinStep.CHANGE_NEW -> "Nouveau code parent"
        }
    val hint: String
        get() = note ?: when (step) {
            PinStep.CREATE, PinStep.CHANGE_NEW -> "4 chiffres. Il sera demandé pour quitter le profil enfant."
            PinStep.CONFIRM, PinStep.CHANGE_CONFIRM -> "Les mêmes 4 chiffres, encore une fois."
            else -> "Code parent à 4 chiffres"
        }
}

/** The app-wide switches of the "Compte et réglages" dialog. */
data class Settings(
    val sponsorBlock: Boolean,
    val autoplay: Boolean,
    val stableVolume: Boolean,
    val deArrow: Boolean,
    val remote: Boolean = false,
    /** 0 = Auto, 1 = Jour, 2 = Nuit, 3 = Comme miryo (ui.Appearance). */
    val appearance: Int = dev.miraiijo.lumentube.ui.APPEARANCE_DEFAULT,
    /** "Taille de l'interface", in % (ui.UI_SCALE_MIN..UI_SCALE_MAX). */
    val uiScale: Int = UI_SCALE_DEFAULT,
    /** "Recevoir même miryo tube fermé": the phone link listens in the background (remote.CastService). */
    val backgroundCast: Boolean = false,
    /** « Petite fenêtre »: when the video goes on in the system's picture-in-picture window (player.PictureInPicture). */
    val background: String = dev.miraiijo.lumentube.player.PictureInPicture.HOME,
    /** "Sons de navigation": 0 = Comme miryo (the default; on without miryo), 1 = Activés, 2 = Coupés. */
    val navSounds: Int = 0,
) {
    /** The clicks of the remote's arrows and OK are heard. */
    val navSoundsOn: Boolean
        get() = when (navSounds) {
            1 -> true
            2 -> false
            else -> dev.miraiijo.lumentube.link.MirazukiLook.current?.navSounds ?: true
        }
}

/**
 * Réglages › Mises à jour: the installed version, « Recevoir les versions bêta », and the version
 * found on the public channel ([found], null: up to date or not checked). [progress]: download 0..1.
 */
@androidx.compose.runtime.Immutable
data class UpdateUi(
    val installed: String = "",
    val wantsBeta: Boolean = false,
    val busy: Boolean = false,
    val progress: Float? = null,
    val found: Updater.UpdateInfo? = null,
    val message: String? = null,
)

/** "Lier un téléphone": the TV code to type in the phone's YouTube app (null while it's requested). */
data class Pairing(val code: String? = null, val error: String? = null)

data class UiState(
    val section: Section = Section.HOME,
    val pages: Map<Section, Page> = emptyMap(),
    val openedStack: List<Opened> = emptyList(),
    val focusKey: String? = null,
    val signedIn: Boolean = false,
    /**
     * Opaque id of the account the pages show (null: signed out or not read yet): the actions made
     * from them (subscribe, À regarder plus tard, Pas intéressé) only go to it.
     */
    val accountId: String? = null,
    val accountName: String? = null,
    val accountAvatar: String? = null,
    /** Sign-in in progress: the code to type on youtube.com/activate (null while it's requested). */
    val signInCode: String? = null,
    val signingIn: Boolean = false,
    val signInError: String? = null,
    /** Video being played (full screen). */
    val playing: Video? = null,
    /** A YouTube link opened from another app (video id), played directly. */
    val playingId: String? = null,
    /** Where that link starts (its t parameter, seconds). */
    val playingStart: Int = 0,
    /** The video plays in a small window in a corner while browsing (in-app picture-in-picture). */
    val minimized: Boolean = false,
    /** Accounts linked on this box. */
    val accounts: List<Account> = emptyList(),
    /** "Qui regarde ?" at launch (several accounts), before Accueil loads. */
    val choosingAccount: Boolean = false,
    /** "Qui regarde ?" opened from the account at the top of the menu. */
    val accountsOpen: Boolean = false,
    /** Réglages dialog open. */
    val settingsOpen: Boolean = false,
    val settings: Settings = Settings(sponsorBlock = true, autoplay = true, stableVolume = true, deArrow = false),
    /** "Reprendre" row at the top of Accueil (null: none, or not signed in). */
    val resume: VideoRow? = null,
    val query: String = "",
    /** Search results (null before the first search). */
    val search: Page? = null,
    /** DeArrow titles / thumbnails by video id (only filled when DeArrow is on). */
    val deArrow: Map<String, DeArrow> = emptyMap(),
    /** Search filters (SearchOptions flags, 0 = none) and YouTube's suggestions for what's typed. */
    val searchOptions: Int = 0,
    val suggestions: List<String> = emptyList(),
    /** Long press on a card: its menu. */
    val cardMenu: Video? = null,
    /** Short message at the bottom of the screen ("Ajoutée à À regarder plus tard"…). */
    val notice: String? = null,
    /** "Lier un téléphone" dialog open. */
    val pairing: Pairing? = null,
    /** Réglages › Mises à jour. */
    val update: UpdateUi = UpdateUi(),
    /**
     * Kids profile on: no account, only the allowed channels (Accueil, one row each), no search,
     * comments, card menus, Réglages, phone or links. [kidsLocked]: miryo's current profile is a
     * kids one, the way out is in miryo (no parental code here).
     */
    val kids: Boolean = false,
    val kidsLocked: Boolean = false,
    /** The kids profile exists (its parental code is set) and the channels it may watch. */
    val kidsExists: Boolean = false,
    val kidChannels: List<KidChannel> = emptyList(),
    /** The kids profile is guarded by miryo's parental code (the suite's), not one of its own. */
    val kidsMiryoCode: Boolean = false,
    /** Parental code dialog open. */
    val pin: PinPrompt? = null,
) {
    /**
     * The launch has something to show (the splash screen can go): Accueil's first rows or its
     * error, "Qui regarde ?", or a video opened from a link.
     */
    fun readyToShow(): Boolean {
        val home = pages[Section.HOME]
        return choosingAccount || playing != null || playingId != null || (home != null && (home.rows.isNotEmpty() || !home.loading))
    }

    val opened: Opened?
        get() = openedStack.lastOrNull()

    val page: Page
        get() = when {
            opened != null -> opened!!.page
            section == Section.SEARCH -> search ?: Page(loading = false)
            section == Section.HOME -> (pages[section] ?: Page()).let { home ->
                if (resume == null) home else home.copy(rows = listOf(resume) + home.rows)
            }
            else -> pages[section] ?: Page()
        }.withDeArrow(deArrow)

    /** Cards with DeArrow's title / thumbnail when there's one. */
    private fun Page.withDeArrow(found: Map<String, DeArrow>): Page {
        if (found.isEmpty()) return this
        return copy(
            rows = rows.map { row ->
                row.copy(
                    items = row.items.map { video ->
                        val d = video.videoId?.let(found::get) ?: return@map video
                        video.copy(title = d.title ?: video.title, customThumbnail = d.thumbnail)
                    },
                )
            },
        )
    }
}

class MainViewModel(application: Application, private val saved: SavedStateHandle) : AndroidViewModel(application) {
    private val prefs = PlayerPrefs(application)
    private val kidsStore = KidsStore(application)
    /** One kids profile change at a time (start, miryo's profile, the parental code). */
    private val kidsMutex = kotlinx.coroutines.sync.Mutex()
    private val _state = MutableStateFlow(UiState(settings = prefs.settings()))
    val state: StateFlow<UiState> = _state.asStateFlow()
    private val loads = mutableMapOf<Section, Job>()
    /** Accueil's extra rows being loaded (declared before init, which may use it). */
    private var homeExtrasJob: Job? = null

    /** miryo asked to skip "Qui regarde ?" (contract v2): the start doesn't show it. */
    private var skipPicker = false

    /** The video plays as another account than miryo tube's (a miryo launch): undone when it stops. */
    private var playingAs = false
    private var signIn: Disposable? = null
    /** Rows whose next page is being fetched (one request at a time per row). */
    private val loadingMore = mutableSetOf<String>()
    /** Declared before init (which sets it): initializers run in order. */
    private var accountListener: (() -> Unit)? = null

    init {
        RemoteControl.uiAttached = true
        dev.miraiijo.lumentube.remote.CastService.sync(getApplication(), _state.value.settings)
        // Account switched from miryo (or anywhere else): the pages reload for the new account.
        accountListener = YouTube.onAccountChanged {
            viewModelScope.launch {
                // By id, not by name (two accounts can share a name): the pages are another account's.
                val id = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.homeAccountId() }
                if (id != _state.value.accountId && !_state.value.choosingAccount) {
                    cancelLoads()
                    _state.update { it.copy(pages = emptyMap(), resume = null, search = null, openedStack = emptyList(), focusKey = null, cardMenu = null) }
                    refreshAccount()
                    show(_state.value.section.takeIf { it != Section.SEARCH } ?: Section.HOME)
                }
            }
        }
        viewModelScope.launch { RemoteControl.events.collect(::onRemote) }
        // Back after the process was killed in the background: the section, the video and the
        // answer to "Qui regarde ?" come back (not a video played as another account for miryo).
        val restored = saved.get<Boolean>(KEY_PICKED) != null
        val savedSection = saved.get<String>(KEY_SECTION)?.let { name -> Section.entries.firstOrNull { it.name == name } } ?: Section.HOME
        saved.get<String>(KEY_VIDEO)?.takeIf { VIDEO_ID.matches(it) }?.let { id ->
            _state.update { it.copy(playingId = id, playingStart = saved.get<Int>(KEY_POSITION) ?: 0) }
        }
        viewModelScope.launch {
            // A miryo launch cut short (process killed while playing as another account): back home.
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.restoreHome() } }
            kidsUi()
            // Kids profile: miryo's current profile is a kids one, or it was left on (a kid closing
            // the app comes back to it). Turned on by miryo and miryo back on an adult: off again.
            if (startKids()) {
                _state.update { it.copy(accounts = YouTube.accounts()) }
                keepSavedState()
                return@launch
            }
            val accounts = YouTube.accounts()
            val guest = accounts.isNotEmpty() && !kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.signedIn }
            // Several accounts, or "Invité" left on last time: "Qui regarde ?" first, Accueil loads
            // for the one picked (not when miryo launched a video and asked to skip it, nor when it
            // was answered before the process was killed).
            val ask = if (restored) saved.get<Boolean>(KEY_PICKED) == false else accounts.size >= 2 || guest
            if (ask && !skipPicker) {
                _state.update { it.copy(accounts = accounts, choosingAccount = true) }
            } else {
                _state.update { it.copy(accounts = accounts) }
                refreshAccount()
                show(if (restored) savedSection else Section.HOME)
            }
            keepSavedState()
        }
        initUpdates()
    }

    /** The saved state follows the UI from now on (written at each change, cheap). */
    private suspend fun keepSavedState() {
        state.collect { s ->
            saved[KEY_PICKED] = !s.choosingAccount
            saved[KEY_SECTION] = s.section.name
            val video = (s.playing?.videoId ?: s.playingId).takeIf { !playingAs }
            if (video != saved.get<String>(KEY_VIDEO)) {
                saved[KEY_VIDEO] = video
                saved[KEY_POSITION] = s.playing?.let { 0 } ?: s.playingStart
            }
        }
    }

    /** Where the video is (the player tells it when it pauses or goes to the background). */
    fun notePosition(videoId: String, seconds: Int) {
        if (saved.get<String>(KEY_VIDEO) == videoId) saved[KEY_POSITION] = seconds.coerceAtLeast(0)
    }

    /** Every section load, Accueil's extra rows included. */
    private fun cancelLoads() {
        loads.values.forEach { it.cancel() }
        homeExtrasJob?.cancel()
        homeExtrasJob = null
    }

    fun show(section: Section) {
        // Kids profile: Accueil only.
        if (_state.value.kids && section != Section.HOME) return
        channelJob?.cancel()
        _state.update { it.copy(section = section, openedStack = emptyList(), focusKey = null) }
        if (section == Section.SEARCH) return
        val current = _state.value.pages[section]
        if (current == null || current.error != null) load(section)
    }

    fun reload() = load(_state.value.section)

    /** "Réessayer" on a page: the opened channel / playlist again, else the section. */
    fun retry() {
        val opened = _state.value.opened ?: return reload()
        val channel = opened.channel
        when {
            channel != null -> openChannel(channel.id, channel.name, channel.avatar, opened.from)
            opened.from != null -> select(opened.from)
            else -> reload()
        }
    }

    private fun load(section: Section) {
        if (_state.value.kids) return loadKids()
        loads[section]?.cancel()
        if (section == Section.HOME) {
            homeExtrasJob?.cancel()
            homeExtrasJob = null
        }
        _state.update { it.copy(pages = it.pages + (section to Page(rows = it.pages[section]?.rows.orEmpty().filterNot { r -> r.key.startsWith(YouTube.HOME_EXTRA) }, loading = true))) }
        if (section == Section.HOME) loadResume()
        loads[section] = viewModelScope.launch {
            fun set(page: Page) = _state.update { it.copy(pages = it.pages + (section to page)) }
            var rows = emptyList<VideoRow>()
            val failure = runCatching {
                // Each part of the page is shown as soon as it arrives (still loading after it).
                YouTube.rows(section).collect {
                    rows = it
                    if (it.isNotEmpty()) set(Page(it, loading = true))
                    fetchDeArrow(it)
                }
            }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            // Accueil without YouTube's own rows: the feeds' rows right away (they decide the error).
            if (section == Section.HOME && rows.isEmpty()) {
                loadHomeExtras(failure?.userMessage(), fromLoad = true)
                return@launch
            }
            set(
                when {
                    rows.isNotEmpty() -> Page(rows, loading = false)
                    failure != null -> Page(loading = false, error = failure.userMessage())
                    else -> Page(loading = false, error = "Rien à afficher ici pour l’instant.")
                },
            )
        }
    }

    /**
     * Accueil's end reached (or YouTube's home empty): a taste of each feed, under its rows, once
     * per load. [error]: shown when no row at all comes (YouTube's home failed too).
     */
    fun loadHomeExtras(error: String? = null, fromLoad: Boolean = false) {
        val s = _state.value
        if (homeExtrasJob != null || s.kids) return
        // From the page: only Accueil's own (a page fading out doesn't ask).
        if (!fromLoad && (s.section != Section.HOME || s.opened != null)) return
        val home = s.pages[Section.HOME] ?: return
        if (home.rows.any { it.key.startsWith(YouTube.HOME_EXTRA) }) return
        homeExtrasJob = viewModelScope.launch {
            // From a failed load: none of the rows kept while it loaded (they'd hide its error).
            val base = if (fromLoad) emptyList() else home.rows.filterNot { it.key.startsWith(YouTube.HOME_EXTRA) }
            fun set(page: Page) = _state.update { it.copy(pages = it.pages + (Section.HOME to page)) }
            var extras = emptyList<VideoRow>()
            val failure = runCatching {
                YouTube.homeExtras().collect {
                    extras = it
                    if (it.isNotEmpty()) set(Page(base + it, loading = true))
                    fetchDeArrow(it)
                }
            }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) throw failure
            set(
                when {
                    base.isNotEmpty() || extras.isNotEmpty() -> Page(base + extras, loading = false)
                    else -> Page(loading = false, error = error ?: failure?.userMessage() ?: "Rien à afficher ici pour l’instant.")
                },
            )
        }
    }

    /** End of a row reached: its next page. */
    fun loadMore(row: VideoRow) {
        if (!row.canLoadMore || !loadingMore.add(row.key)) return
        viewModelScope.launch {
            val next = runCatching { YouTube.more(row) }.getOrNull()
            loadingMore.remove(row.key)
            if (next == null) return@launch
            _state.update { s ->
                fun Page.swap() = copy(rows = rows.map { if (it.key == row.key) next else it })
                val stack = s.openedStack.map { o ->
                    o.copy(
                        page = o.page.swap(),
                        homeRows = o.homeRows.map { if (it.key == row.key) next else it },
                        sortRows = o.sortRows.map { if (it.key == row.key) next else it },
                    )
                }
                s.copy(openedStack = stack, pages = s.pages.mapValues { (_, page) -> page.swap() }, search = s.search?.swap())
            }
            fetchDeArrow(listOf(next))
        }
    }

    /** "Reprendre" (history), next to Accueil's own loading. */
    private fun loadResume() {
        viewModelScope.launch {
            val row = runCatching { YouTube.resumeRow() }.getOrNull()
            _state.update { it.copy(resume = row) }
            row?.let { fetchDeArrow(listOf(it)) }
        }
    }

    // ---- Search -------------------------------------------------------------------------------

    private var searchJob: Job? = null

    fun search(query: String) {
        val text = query.trim()
        if (text.isEmpty() || _state.value.kids) return
        searchJob?.cancel()
        suggestJob?.cancel()
        val options = _state.value.searchOptions
        _state.update { it.copy(query = text, search = Page(loading = true), suggestions = emptyList()) }
        searchJob = viewModelScope.launch {
            val page = runCatching { YouTube.search(text, options) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucun résultat pour « $text »." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { it.copy(search = page) }
            fetchDeArrow(page.rows)
        }
    }

    private var suggestJob: Job? = null

    /** Typing in the search bar: YouTube's suggestions, once the typing pauses. */
    fun suggest(text: String) {
        suggestJob?.cancel()
        val typed = text.trim()
        if (typed.length < 2) {
            _state.update { it.copy(suggestions = emptyList()) }
            return
        }
        suggestJob = viewModelScope.launch {
            delay(SUGGEST_DELAY_MS)
            val found = YouTube.suggestions(typed)
            _state.update { it.copy(suggestions = found) }
        }
    }

    /** A filter changed (date, duration, type, order): the current search runs again with it. */
    fun setSearchOptions(options: Int) {
        _state.update { it.copy(searchOptions = options) }
        _state.value.query.takeIf { it.isNotBlank() }?.let(::search)
    }

    // ---- DeArrow ------------------------------------------------------------------------------

    /** Community titles / thumbnails for the videos of these rows not looked up yet (when DeArrow is on). */
    /** Video ids already asked to DeArrow (answered or not): each one is asked once. */
    private val deArrowAsked = mutableSetOf<String>()

    private fun fetchDeArrow(rows: List<VideoRow>) {
        if (!_state.value.settings.deArrow) return
        val ids = rows.flatMap { row -> row.items.mapNotNull { it.videoId } }.distinct().filter { it !in deArrowAsked }.take(DEARROW_BATCH)
        if (ids.isEmpty()) return
        deArrowAsked += ids
        viewModelScope.launch {
            val found = YouTube.deArrow(ids)
            if (found.isEmpty()) {
                deArrowAsked -= ids.toSet()
            } else {
                _state.update { it.copy(deArrow = it.deArrow + found) }
            }
        }
    }

    /** OK on a card: play a video, open a channel / playlist. */
    fun select(video: Video) {
        // Kids profile: the videos of its rows only (no channel, no playlist).
        if (_state.value.kids && (!video.isVideo || video.videoId == null)) return
        if (video.isVideo && video.videoId != null) {
            // The video already in the small window: back to full screen, where it was.
            val current = _state.value.let { it.playing?.videoId ?: it.playingId }
            if (_state.value.minimized && current == video.videoId) {
                restore()
                return
            }
            _state.update { it.copy(playing = video, playingId = null, minimized = false) }
            return
        }
        val channelId = video.raw.channelId
        if (video.isChannel && channelId != null) {
            openChannel(channelId, video.title, video.thumbnail, from = video)
            return
        }
        val openKey = video.raw.playlistId ?: video.key
        val entry = Opened(key = openKey, title = video.title, page = Page(), from = video)
        _state.update { s -> s.copy(openedStack = s.openedStack.pushOrReplace(entry), focusKey = null) }
        viewModelScope.launch {
            val page = runCatching { YouTube.open(video) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucune vidéo." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            _state.update { s ->
                val top = s.opened ?: return@update s
                if (top.key != openKey) return@update s
                s.copy(openedStack = s.openedStack.dropLast(1) + top.copy(page = page))
            }
            fetchDeArrow(page.rows)
        }
    }

    // ---- Channel page ---------------------------------------------------------------------------

    private var channelJob: Job? = null

    /** From the player: the video goes to the small window, the channel page opens behind it. */
    fun openChannelFromPlayer(channelId: String, name: String) {
        if (_state.value.kids) return
        _state.update { it.copy(minimized = true) }
        openChannel(channelId, name, null)
    }

    /** A channel page (from a channel card, a card's menu or the player): header, then its rows. */
    fun openChannel(channelId: String, name: String, avatar: String?, from: Video? = null) {
        if (_state.value.kids) return
        channelJob?.cancel()
        val prev = _state.value.opened?.takeIf { it.channel?.id == channelId }
        val header = ChannelHeader(
            channelId, name.ifBlank { "Chaîne" }, avatar ?: prev?.channel?.avatar,
            kidAllowed = if (kidsStore.exists) kidsStore.isAllowed(channelId) else null,
        )
        val entry = Opened(key = channelId, title = header.name, page = Page(), channel = header, from = from ?: prev?.from)
        _state.update { s -> s.copy(openedStack = s.openedStack.pushOrReplace(entry), focusKey = null, cardMenu = null) }
        channelJob = viewModelScope.launch {
            launch {
                val subscribed = if (_state.value.signedIn) YouTube.isSubscribed(channelId) else null
                updateChannel(channelId) { it.copy(subscribed = subscribed) }
            }
            launch {
                val sorts = runCatching { YouTube.channelSorts(channelId) }.getOrDefault(emptyList())
                updateChannelEntry(channelId) { o ->
                    o.copy(
                        channel = o.channel?.copy(sorts = sorts.map { row -> row.title }),
                        sortRows = sorts,
                    )
                }
            }
            val page = runCatching { YouTube.channelRows(channelId) }.fold(
                onSuccess = { Page(it, loading = false, error = if (it.isEmpty()) "Aucune vidéo." else null) },
                onFailure = { Page(loading = false, error = it.userMessage()) },
            )
            updateChannelEntry(channelId) { o ->
                val updated = o.copy(homeRows = page.rows)
                if (o.channel?.sort == -1) updated.copy(page = page) else updated
            }
            fetchDeArrow(page.rows)
        }
    }

    private fun List<Opened>.pushOrReplace(entry: Opened): List<Opened> {
        val top = lastOrNull()
        return if (top != null && top.key == entry.key) dropLast(1) + entry else this + entry
    }

    private fun updateChannelEntry(channelId: String, change: (Opened) -> Opened) = _state.update { s ->
        val top = s.opened?.takeIf { it.channel?.id == channelId } ?: return@update s
        s.copy(openedStack = s.openedStack.dropLast(1) + change(top))
    }

    private fun updateChannel(channelId: String, change: (ChannelHeader) -> ChannelHeader) =
        updateChannelEntry(channelId) { o -> o.copy(channel = o.channel?.let(change)) }

    /** "Accueil de la chaîne" (-1) or one of its orders. */
    fun chooseChannelSort(index: Int) {
        val opened = _state.value.opened ?: return
        val channel = opened.channel ?: return
        val rows = if (index < 0) opened.homeRows else listOfNotNull(opened.sortRows.getOrNull(index))
        updateChannelEntry(channel.id) { o ->
            o.copy(
                channel = o.channel?.copy(sort = index),
                page = Page(rows, loading = false, error = if (rows.isEmpty()) "Aucune vidéo." else null),
            )
        }
        fetchDeArrow(rows)
    }

    fun toggleChannelSubscription() {
        val channel = _state.value.opened?.channel ?: return
        val target = channel.subscribed != true
        val account = _state.value.accountId
        updateChannel(channel.id) { it.copy(subscribed = target) }
        viewModelScope.launch {
            val result = YouTube.setSubscribed(channel.id, target, account)
            if (result != ActionResult.DONE) {
                updateChannel(channel.id) { it.copy(subscribed = !target) }
                showNotice(if (result == ActionResult.ACCOUNT_CHANGED) ACCOUNT_CHANGED_MESSAGE else "Impossible de modifier l’abonnement.")
            }
        }
    }

    // ---- Card menu (long press) -----------------------------------------------------------------

    fun openCardMenu(video: Video) {
        if (!_state.value.kids) _state.update { it.copy(cardMenu = video) }
    }

    fun closeCardMenu() = _state.update { it.copy(cardMenu = null) }

    fun cardWatchLater(video: Video) {
        closeCardMenu()
        val id = video.videoId ?: return
        val account = _state.value.accountId
        viewModelScope.launch {
            showNotice(
                when (YouTube.watchLater(id, account)) {
                    ActionResult.DONE -> "Ajoutée à « À regarder plus tard »."
                    ActionResult.ACCOUNT_CHANGED -> ACCOUNT_CHANGED_MESSAGE
                    ActionResult.FAILED -> "Impossible d’ajouter la vidéo."
                },
            )
        }
    }

    fun cardOpenChannel(video: Video) {
        val id = video.raw.channelId ?: return closeCardMenu()
        openChannel(id, video.channel, null, from = video)
    }

    /** "Pas intéressé" / "Ne pas recommander cette chaîne": the card (or the channel's cards) leaves the rows. */
    fun cardNotInterested(video: Video, channel: Boolean) {
        closeCardMenu()
        val account = _state.value.accountId
        viewModelScope.launch {
            val result = YouTube.notInterested(video, channel, account)
            if (result != ActionResult.DONE) {
                showNotice(if (result == ActionResult.ACCOUNT_CHANGED) ACCOUNT_CHANGED_MESSAGE else "YouTube n’a pas pris en compte la demande.")
                return@launch
            }
            val channelId = video.raw.channelId
            removeCards { it.key == video.key || (channel && channelId != null && it.raw.channelId == channelId) }
            showNotice(if (channel) "Cette chaîne ne sera plus recommandée." else "Tu ne verras plus cette vidéo.")
        }
    }

    private fun removeCards(matches: (Video) -> Boolean) = _state.update { s ->
        fun Page.without() = copy(rows = rows.map { row -> row.copy(items = row.items.filterNot(matches)) }.filter { it.items.isNotEmpty() })
        s.copy(
            pages = s.pages.mapValues { (_, page) -> page.without() },
            search = s.search?.without(),
            openedStack = s.openedStack.map { it.copy(page = it.page.without()) },
            resume = s.resume?.let { row -> row.copy(items = row.items.filterNot(matches)).takeIf { it.items.isNotEmpty() } },
        )
    }

    private var noticeJob: Job? = null

    fun showNotice(text: String) {
        noticeJob?.cancel()
        _state.update { it.copy(notice = text) }
        noticeJob = viewModelScope.launch {
            delay(NOTICE_MS)
            _state.update { it.copy(notice = null) }
        }
    }

    // ---- "Regarder sur la TV" (phone remote) ------------------------------------------------------

    private var foreground = false

    /** The activity is on screen (or not): commands are listened to only then, when it's enabled. */
    fun onForeground(on: Boolean) {
        foreground = on
        if (on) checkMirazukiProfile()
        RemoteControl.uiForeground = on
        // Off screen, the background service (when enabled) keeps listening.
        if (on && _state.value.settings.remote) RemoteControl.start(getApplication())
        else if (!dev.miraiijo.lumentube.remote.CastService.running) RemoteControl.stop()
    }

    /** "Lier un téléphone": the TV code; listening starts so the phone finds the TV right away. */
    fun openPairing() {
        // Over Réglages (they stay behind it, on Téléphone).
        _state.update { it.copy(pairing = Pairing()) }
        if (!_state.value.settings.remote) updateSettings(_state.value.settings.copy(remote = true))
        viewModelScope.launch {
            val code = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching { RemoteControl.pairingCode().timeout(20, java.util.concurrent.TimeUnit.SECONDS).blockingFirst() }.getOrNull()
            }
            _state.update { s ->
                if (s.pairing == null) s
                else s.copy(pairing = if (code.isNullOrBlank()) Pairing(error = "Code indisponible. Réessaie.") else Pairing(code = code.chunked(3).joinToString(" ")))
            }
        }
    }

    fun closePairing() = _state.update { it.copy(pairing = null) }

    private fun onRemote(event: RemoteControl.Event) {
        when (event) {
            is RemoteControl.Event.Open -> {
                val s = _state.value
                if (s.kids) {
                    showNotice("Profil enfant : les vidéos envoyées par un téléphone ne sont pas lues.")
                    return
                }
                val current = s.playing?.videoId ?: s.playingId
                // The video already playing: only its position (the player handles the Seek).
                if (current == event.videoId) {
                    if (s.minimized) restore()
                } else if (playingAs) {
                    // A miryo launch was playing as another account: the phone's video plays with miryo tube's.
                    playLink(event.videoId, (event.startMs / 1000).toInt())
                } else {
                    closeDialogs()
                    _state.update { it.copy(playing = null, playingId = event.videoId, playingStart = (event.startMs / 1000).toInt(), minimized = false, cardMenu = null) }
                }
            }
            RemoteControl.Event.Stop -> if (_state.value.let { it.playing != null || it.playingId != null }) stopPlaying()
            RemoteControl.Event.StateRequest -> if (_state.value.let { it.playing == null && it.playingId == null }) RemoteControl.reportIdle()
            is RemoteControl.Event.Connected -> {
                showNotice("${event.device?.takeIf { it.isNotBlank() } ?: "Un téléphone"} est connecté.")
                if (_state.value.pairing != null) closePairing()
            }
            RemoteControl.Event.Disconnected -> showNotice("Téléphone déconnecté.")
            else -> Unit // seek, play, pause, next: the player screen
        }
    }

    /** "À suivre" from the player: the next video replaces the current one. */
    fun playNext(video: Video) {
        if (_state.value.kids) return stopPlaying()
        _state.update { it.copy(playing = video, playingId = null) }
    }

    fun closeOpened() = _state.update { s ->
        channelJob?.cancel()
        if (s.openedStack.isEmpty()) s
        else s.copy(openedStack = s.openedStack.dropLast(1), focusKey = s.openedStack.last().from?.key)
    }

    /**
     * [accountId] (miryo, contract v2): play as that account, for this playback only (history and
     * position go to it); miryo tube's own account comes back when the video stops. Unknown or the
     * same: the selected account. [skipPickerAsked]: no "Qui regarde ?".
     */
    fun playLink(videoId: String, startSeconds: Int = 0, accountId: String? = null, skipPickerAsked: Boolean = false) {
        // Kids profile: only its allowed channels play, never a link from elsewhere.
        if (_state.value.kids || kidsStore.active) {
            showNotice("Profil enfant : seules les chaînes autorisées se regardent ici.")
            return
        }
        // A launch for an account plays at once: "Qui regarde ?" never covers it.
        if (skipPickerAsked || accountId != null) {
            skipPicker = true
            if (_state.value.choosingAccount) skipAccountChoice()
        }
        closeDialogs()
        // No account and nothing played as another one: as before, at once.
        if (accountId == null && !playingAs) {
            _state.update { it.copy(playing = null, playingId = videoId, playingStart = startSeconds, minimized = false) }
            return
        }
        viewModelScope.launch {
            // The video playing goes first: its last position is saved to its own account.
            val hadVideo = _state.value.let { it.playing != null || it.playingId != null }
            if (hadVideo) _state.update { it.copy(playing = null, playingId = null) }
            // One account change at a time, in order (a stop then a new launch never cross).
            playAsMutex.withLock {
                val was = playingAs
                if (was || hadVideo) awaitHistoryWrites()
                // An account given: play as it; none (or unknown, or miryo tube's own): back to miryo tube's.
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.beginPlayAs(accountId) } }
                playingAs = YouTube.playingAsOther
                if (was && !playingAs) reloadHome()
            }
            _state.update { it.copy(playing = null, playingId = videoId, playingStart = startSeconds, minimized = false) }
        }
    }

    /** One account change at a time (begin / end of a play-as). */
    private val playAsMutex = kotlinx.coroutines.sync.Mutex()

    /** The last position save goes to the account played: waits for it (dispose, then the write). */
    private suspend fun awaitHistoryWrites() {
        delay(HISTORY_SETTLE_MS)
        var waited = 0L
        while (YouTube.historyWrites.get() > 0 && waited < HISTORY_WAIT_MS) {
            delay(100)
            waited += 100
        }
    }

    /** After a miryo launch played as another account: miryo tube's own account and pages again. */
    private fun endPlayingAs() {
        if (!playingAs) return
        viewModelScope.launch {
            playAsMutex.withLock {
                if (!playingAs) return@withLock
                awaitHistoryWrites()
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.endPlayAs() } }
                playingAs = false
                reloadHome()
                // "Reprendre" of miryo tube's account (not the one just played as).
                loadResume()
            }
        }
    }

    /** Pages loaded meanwhile may be the other account's: reloaded for miryo tube's own. */
    private fun reloadHome() {
        cancelLoads()
        channelJob?.cancel()
        _state.update { it.copy(pages = emptyMap(), resume = null, search = null, openedStack = emptyList(), focusKey = null) }
        refreshAccount()
        show(_state.value.section.takeIf { it != Section.SEARCH } ?: Section.HOME)
    }

    /**
     * A video arriving from the phone or a link: the dialogs close (each is its own window, they
     * stayed over the player and the remote drove them instead of the video).
     */
    private fun closeDialogs() {
        val s = _state.value
        if (s.signingIn) cancelSignIn()
        if (s.choosingAccount) skipAccountChoice()
        _state.update { it.copy(settingsOpen = false, accountsOpen = false, pairing = null, cardMenu = null) }
    }

    /** Small window in a corner; browsing goes on. */
    fun minimize() = _state.update { it.copy(minimized = true) }

    /** Back to full screen. */
    fun restore() = _state.update { it.copy(minimized = false) }

    fun stopPlaying() {
        _state.update { it.copy(playing = null, playingId = null, minimized = false) }
        // A miryo launch played as another account: its end reloads miryo tube's (Reprendre included).
        if (playingAs) {
            endPlayingAs()
            return
        }
        // "Reprendre" follows what was just watched (YouTube needs a moment to record the position).
        if (_state.value.signedIn) {
            viewModelScope.launch {
                delay(RESUME_REFRESH_DELAY_MS)
                loadResume()
            }
        }
    }

    // ---- Kids profile ---------------------------------------------------------------------------

    private fun kidsUi() = _state.update { it.copy(kidsExists = kidsStore.exists, kidChannels = kidsStore.channels, kidsMiryoCode = kidsStore.usesMiryoCode) }

    /** At start: true when the kids profile is on (miryo's current profile, or left on here). */
    private suspend fun startKids(): Boolean {
        // miryo's parental code (the suite's) may guard the kids profile: read whether it has one; and its look.
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kidsStore.refreshSuite(); dev.miraiijo.lumentube.link.MirazukiLook.refresh(getApplication()) }
        kidsUi()
        val mirazukiKids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MirazukiProfile.currentIsKids(getApplication()) }
        return kidsMutex.withLock {
            when {
                mirazukiKids == true -> beginKids(fromMirazuki = true)
                // Turned on by miryo, and miryo is on an adult profile now: off, without a code.
                kidsStore.active && kidsStore.fromMirazuki && mirazukiKids == false -> {
                    endKidsLocked()
                    return@withLock false
                }
                kidsStore.active -> beginKids(fromMirazuki = kidsStore.fromMirazuki)
                else -> return@withLock false
            }
            true
        }
    }

    /** Back on screen: miryo's profile may have changed meanwhile (kids ↔ adult). */
    private fun checkMirazukiProfile() {
        viewModelScope.launch {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { kidsStore.refreshSuite(); dev.miraiijo.lumentube.link.MirazukiLook.refresh(getApplication()) }
            kidsUi()
            val mirazukiKids = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { MirazukiProfile.currentIsKids(getApplication()) } ?: return@launch
            kidsMutex.withLock {
                val s = _state.value
                if (mirazukiKids && !(s.kids && s.kidsLocked)) {
                    beginKids(fromMirazuki = true)
                } else if (!mirazukiKids && s.kids && s.kidsLocked) {
                    endKidsLocked()
                    reloadAfterKids()
                }
            }
        }
    }

    /**
     * Kids profile on (under [kidsMutex]): YouTube without account (the account in use is kept
     * to come back to), only the allowed channels; a video playing stops (it was an adult's).
     */
    private suspend fun beginKids(fromMirazuki: Boolean) {
        if (!_state.value.kids) {
            // A miryo launch playing as another account: that account goes first (the engine is
            // miryo tube's again), then no account at all.
            if (playingAs) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.endPlayAs() } }
                playingAs = false
            }
            kidsStore.previousAccount = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { YouTube.homeAccountId() }
            if (!YouTube.useGuest()) YouTube.useGuest()
        }
        kidsStore.active = true
        kidsStore.fromMirazuki = fromMirazuki
        if (signIn != null) cancelSignIn()
        cancelLoads()
        channelJob?.cancel()
        _state.update {
            it.copy(
                kids = true, kidsLocked = fromMirazuki, pin = null,
                choosingAccount = false, accountsOpen = false, settingsOpen = false, pairing = null, cardMenu = null,
                playing = null, playingId = null, minimized = false,
                pages = emptyMap(), resume = null, search = null, section = Section.HOME, openedStack = emptyList(), focusKey = null,
            )
        }
        kidsUi()
        refreshAccount()
        show(Section.HOME)
    }

    /** Kids profile off (under [kidsMutex]): the account used before comes back. */
    private suspend fun endKidsLocked() {
        kidsStore.active = false
        kidsStore.fromMirazuki = false
        val previous = kidsStore.previousAccount
        kidsStore.previousAccount = null
        if (previous != null) kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { YouTube.selectFeedAccount(previous) } }
        _state.update { it.copy(kids = false, kidsLocked = false, pin = null) }
    }

    private fun reloadAfterKids() {
        cancelLoads()
        _state.update { it.copy(pages = emptyMap(), resume = null, search = null, section = Section.HOME, openedStack = emptyList(), focusKey = null, playing = null, playingId = null, minimized = false) }
        refreshAccount()
        show(Section.HOME)
    }

    /** "Enfant" in "Qui regarde ?": the profile, or its creation (a parental code first). */
    fun chooseKids() {
        if (_state.value.kids) return
        if (!kidsStore.exists) {
            // miryo's parental code is the suite's: no second code to choose.
            if (kidsStore.usesMiryoCode) {
                kidsStore.createWithMiryoCode()
                kidsUi()
            } else {
                _state.update { it.copy(pin = PinPrompt(PinStep.CREATE)) }
                return
            }
        }
        viewModelScope.launch { kidsMutex.withLock { beginKids(fromMirazuki = false) } }
    }

    /** Réglages › Enfant. */
    fun changeKidsPin() {
        if (kidsStore.usesMiryoCode) return showNotice("C’est le code parental de miryo : il se change dans miryo › Réglages › Contrôle parental.")
        _state.update { it.copy(pin = PinPrompt(PinStep.CHANGE_CHECK)) }
    }

    fun deleteKids() = _state.update { it.copy(pin = PinPrompt(PinStep.DELETE_CHECK)) }

    fun removeKidChannel(id: String) {
        if (_state.value.kids) return
        kidsStore.disallow(id)
        kidsUi()
    }

    fun closePin() = _state.update { it.copy(pin = null) }

    /** The 4 digits typed in the parental code dialog: an error to show, or null (the dialog moves on). */
    suspend fun submitPin(code: String): String? {
        val prompt = _state.value.pin ?: return null
        val io = kotlinx.coroutines.Dispatchers.IO
        return when (prompt.step) {
            PinStep.CREATE, PinStep.CHANGE_NEW -> {
                val confirm = if (prompt.step == PinStep.CREATE) PinStep.CONFIRM else PinStep.CHANGE_CONFIRM
                _state.update { it.copy(pin = PinPrompt(confirm, first = code)) }
                null
            }
            PinStep.CONFIRM, PinStep.CHANGE_CONFIRM -> {
                if (code != prompt.first) {
                    val again = if (prompt.step == PinStep.CONFIRM) PinStep.CREATE else PinStep.CHANGE_NEW
                    _state.update { it.copy(pin = PinPrompt(again, note = "Les deux codes étaient différents : recommence.")) }
                    return null
                }
                kotlinx.coroutines.withContext(io) { kidsStore.setPin(code) }
                _state.update { it.copy(pin = null) }
                kidsUi()
                if (prompt.step == PinStep.CONFIRM) {
                    kidsMutex.withLock { beginKids(fromMirazuki = false) }
                } else {
                    showNotice("Code parent changé.")
                }
                null
            }
            PinStep.UNLOCK, PinStep.CHANGE_CHECK, PinStep.DELETE_CHECK -> when (val result = kotlinx.coroutines.withContext(io) { kidsStore.verify(code) }) {
                PinResult.Ok -> {
                    when (prompt.step) {
                        PinStep.UNLOCK -> {
                            kidsMutex.withLock { endKidsLocked() }
                            reloadAfterKids()
                            // Who watches now: the accounts, "Invité" and "Enfant" again.
                            _state.update { it.copy(accountsOpen = true) }
                        }
                        PinStep.CHANGE_CHECK -> _state.update { it.copy(pin = PinPrompt(PinStep.CHANGE_NEW)) }
                        else -> {
                            kidsStore.delete()
                            _state.update { it.copy(pin = null) }
                            kidsUi()
                            showNotice("Profil enfant supprimé.")
                        }
                    }
                    null
                }
                PinResult.Wrong -> "Code incorrect."
                is PinResult.Locked -> "Trop d’essais : réessaie dans ${result.minutes} min."
                PinResult.Unavailable -> "Code non vérifié, réessaie dans un instant."
            }
        }
    }

    /** "Pour Enfant" on a channel page: the kids profile may watch it (or not anymore). */
    fun toggleKidChannel() {
        val channel = _state.value.opened?.channel ?: return
        if (_state.value.kids || !kidsStore.exists) return
        val allowed = kidsStore.isAllowed(channel.id)
        if (allowed) kidsStore.disallow(channel.id) else kidsStore.allow(KidChannel(channel.id, channel.name, channel.avatar))
        updateChannel(channel.id) { it.copy(kidAllowed = !allowed) }
        kidsUi()
        showNotice(if (allowed) "${channel.name} retirée du profil enfant." else "${channel.name} autorisée pour le profil enfant.")
    }

    /**
     * Accueil of the kids profile: one row per allowed channel, its latest videos (its own
     * "Vidéos" order, so never another channel's), at most [KIDS_PARALLEL] channels at a time.
     */
    private fun loadKids() {
        loads[Section.HOME]?.cancel()
        val channels = kidsStore.channels
        _state.update { it.copy(pages = it.pages + (Section.HOME to Page(loading = true)), resume = null) }
        loads[Section.HOME] = viewModelScope.launch {
            fun set(page: Page) = _state.update { it.copy(pages = it.pages + (Section.HOME to page)) }
            if (channels.isEmpty()) {
                set(Page(loading = false, error = KIDS_EMPTY))
                return@launch
            }
            val rows = arrayOfNulls<VideoRow>(channels.size)
            val gate = kotlinx.coroutines.sync.Semaphore(KIDS_PARALLEL)
            kotlinx.coroutines.coroutineScope {
                channels.forEachIndexed { index, channel ->
                    launch {
                        val row = gate.withPermit { runCatching { YouTube.channelSorts(channel.id) }.getOrNull() }?.firstOrNull() ?: return@launch
                        val videos = row.items.filter { it.isVideo && it.videoId != null && (it.raw.channelId == null || it.raw.channelId == channel.id) }
                        if (videos.isEmpty()) return@launch
                        rows[index] = row.copy(key = "$KIDS_ROW${channel.id}", title = channel.name, items = videos, canLoadMore = false)
                        set(Page(rows.filterNotNull(), loading = true))
                    }
                }
            }
            val shown = rows.filterNotNull()
            set(if (shown.isEmpty()) Page(loading = false, error = "Les chaînes autorisées ne répondent pas pour l’instant.") else Page(shown, loading = false))
            fetchDeArrow(shown)
        }
    }

    // ---- Self-update (Réglages › Mises à jour) -------------------------------------------------

    /** The installed version, then the silent daily check (a notice when a version is ready). */
    private fun initUpdates() {
        val app = getApplication<Application>()
        _state.update {
            it.copy(
                update = UpdateUi(
                    installed = runCatching { Updater.installedVersionName(app) }.getOrDefault(""),
                    wantsBeta = runCatching { Updater.wantsBeta(app) }.getOrDefault(false),
                ),
            )
        }
        viewModelScope.launch {
            if (!runCatching { Updater.dailyCheckDue(app) }.getOrDefault(false)) return@launch
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updater.check(app, UPDATE_APP) } }
            // Recorded only once the channel answered: offline at start-up, the next start checks again.
            if (result.isSuccess) runCatching { Updater.markChecked(app) }
            val found = result.getOrNull()
            if (found != null) {
                _state.update { it.copy(update = it.update.copy(found = found)) }
                showNotice("Mise à jour disponible : ${found.versionName} (Réglages › Mises à jour).")
            }
        }
    }

    fun setWantsBeta(on: Boolean) {
        runCatching { Updater.setWantsBeta(getApplication(), on) }
        _state.update { it.copy(update = it.update.copy(wantsBeta = on, found = null, message = null)) }
        checkUpdate()
    }

    fun checkUpdate() {
        if (_state.value.update.busy) return
        val app = getApplication<Application>()
        _state.update { it.copy(update = it.update.copy(busy = true, message = null)) }
        viewModelScope.launch {
            val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { runCatching { Updater.check(app, UPDATE_APP) } }
            _state.update {
                it.copy(
                    update = it.update.copy(
                        busy = false,
                        found = result.getOrNull(),
                        message = result.fold(
                            { found -> if (found == null) "miryo tube est à jour." else "Nouvelle version : ${found.versionName}." },
                            { e -> e.message ?: "Vérification impossible." },
                        ),
                    ),
                )
            }
        }
    }

    fun installUpdate() {
        val current = _state.value.update
        val found = current.found ?: return
        if (current.busy || Updater.isDownloading()) return
        val app = getApplication<Application>()
        if (!runCatching { Updater.canInstall(app) }.getOrDefault(false)) {
            val opened = Updater.openInstallPermissionSettings(app)
            val msg = if (opened) {
                "Autorise miryo tube à installer des applis, puis relance l’installation."
            } else {
                "Autorise l'installation d'applis pour miryo tube dans Paramètres Android › Sécurité"
            }
            _state.update { it.copy(update = it.update.copy(message = msg)) }
            return
        }
        _state.update { it.copy(update = it.update.copy(busy = true, progress = 0f, message = null)) }
        viewModelScope.launch {
            var shown = 0f
            val apk = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    Updater.download(app, found) { p ->
                        // A step every 2 %: no state update per 64 KB block.
                        if (p - shown >= 0.02f || p >= 1f) {
                            shown = p
                            _state.update { it.copy(update = it.update.copy(progress = p.coerceIn(0f, 1f))) }
                        }
                    }
                }
            }
            _state.update { it.copy(update = it.update.copy(busy = false, progress = null)) }
            apk.fold(
                { file ->
                    runCatching { Updater.install(app, file) }
                        .onFailure { e -> _state.update { it.copy(update = it.update.copy(message = "Installation impossible : ${e.message}")) } }
                },
                { e -> _state.update { it.copy(update = it.update.copy(message = "Échec : ${e.message ?: "téléchargement impossible"}")) } },
            )
        }
    }

    // ---- Settings -----------------------------------------------------------------------------

    fun openSettings() {
        if (!_state.value.kids) _state.update { it.copy(settingsOpen = true) }
    }

    fun openAccounts() {
        val s = _state.value
        when {
            // miryo's kids profile: the way out is in miryo, behind its own parental code.
            s.kidsLocked -> showNotice("Profil enfant de miryo : change de profil dans miryo.")
            // miryo tube's kids profile: the parental code first.
            s.kids -> _state.update { it.copy(pin = PinPrompt(PinStep.UNLOCK)) }
            else -> {
                _state.update { it.copy(accountsOpen = true) }
                refreshAccount()
            }
        }
    }

    fun closeAccounts() = _state.update { it.copy(accountsOpen = false) }

    fun closeSettings() = _state.update { it.copy(settingsOpen = false) }

    fun updateSettings(settings: Settings) {
        prefs.sponsorBlock = settings.sponsorBlock
        prefs.autoplay = settings.autoplay
        prefs.stableVolume = settings.stableVolume
        prefs.deArrow = settings.deArrow
        prefs.remote = settings.remote
        prefs.appearance = settings.appearance
        prefs.uiScale = settings.uiScale
        prefs.backgroundCast = settings.backgroundCast
        prefs.background = settings.background
        prefs.navSounds = settings.navSounds
        if (settings.remote != _state.value.settings.remote) {
            if (settings.remote && foreground) RemoteControl.start(getApplication()) else if (!settings.remote) RemoteControl.stop()
        }
        val deArrowTurnedOn = settings.deArrow && !_state.value.settings.deArrow
        if (!settings.deArrow) deArrowAsked.clear()
        _state.update { it.copy(settings = settings, deArrow = if (settings.deArrow) it.deArrow else emptyMap()) }
        dev.miraiijo.lumentube.remote.CastService.sync(getApplication(), settings)
        if (deArrowTurnedOn) {
            val s = _state.value
            fetchDeArrow(listOfNotNull(s.resume) + s.pages.values.flatMap { it.rows } + s.search?.rows.orEmpty())
        }
    }
    // ---- Account ------------------------------------------------------------------------------

    fun startSignIn() {
        if (_state.value.kids) return
        signIn?.dispose()
        _state.update { it.copy(signingIn = true, signInCode = null, signInError = null) }
        signIn = YouTube.signIn(
            onCode = { code -> _state.update { it.copy(signInCode = code) } },
            onError = { message -> _state.update { it.copy(signInError = message) } },
            onDone = {
                // A new account is the selected one: everything reloads for it.
                _state.update { it.copy(signingIn = false, signInCode = null, choosingAccount = false, accountsOpen = false, settingsOpen = false, pages = emptyMap(), resume = null) }
                refreshAccount()
                show(Section.HOME)
            },
        )
    }

    /** Account picked at launch ("Qui regarde ?") or in the settings: Accueil reloads for it. */
    fun chooseAccount(account: Account) {
        if (_state.value.kids) return
        viewModelScope.launch {
            if (!account.selected) YouTube.selectAccount(account)
            // Playing as another account for miryo: the new home loads when that video stops.
            if (playingAs) {
                _state.update { it.copy(choosingAccount = false, accountsOpen = false, settingsOpen = false) }
                refreshAccount()
                return@launch
            }
            cancelLoads()
            channelJob?.cancel()
            _state.update { it.copy(choosingAccount = false, accountsOpen = false, settingsOpen = false, pages = emptyMap(), resume = null, search = null, section = Section.HOME, openedStack = emptyList(), focusKey = null) }
            refreshAccount()
            show(Section.HOME)
        }
    }

    /** "Invité" in "Qui regarde ?": signed-out YouTube, the linked accounts kept. */
    fun chooseGuest() {
        if (_state.value.kids) return
        viewModelScope.launch {
            if (!YouTube.useGuest()) {
                _state.update { it.copy(choosingAccount = false, accountsOpen = false) }
                refreshAccount()
                return@launch
            }
            cancelLoads()
            channelJob?.cancel()
            _state.update { it.copy(choosingAccount = false, accountsOpen = false, settingsOpen = false, pages = emptyMap(), resume = null, search = null, section = Section.HOME, openedStack = emptyList(), focusKey = null) }
            refreshAccount()
            show(Section.HOME)
        }
    }

    /** "Qui regarde ?" dismissed: the account already selected stays. */
    fun skipAccountChoice() {
        _state.update { it.copy(choosingAccount = false) }
        refreshAccount()
        show(Section.HOME)
    }

    fun cancelSignIn() {
        signIn?.dispose()
        _state.update { it.copy(signingIn = false, signInCode = null, signInError = null) }
    }

    fun signOut() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            YouTube.signOut()
            _state.update { it.copy(pages = emptyMap(), resume = null, settingsOpen = false, section = Section.HOME) }
            refreshAccount()
            launch(kotlinx.coroutines.Dispatchers.Main) { show(Section.HOME) }
        }
    }

    private fun refreshAccount() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val signed = YouTube.signedIn
            val accounts = YouTube.accounts()
            // miryo tube's own account (its pages), not one a miryo launch plays as.
            val id = if (signed) YouTube.homeAccountId() else null
            _state.update {
                it.copy(
                    signedIn = signed,
                    accountId = id,
                    // Kids profile: no account, its own name on the header's round button.
                    accountName = if (it.kids) "Enfant" else if (signed) YouTube.accountName else null,
                    accountAvatar = if (signed && !it.kids) YouTube.accountAvatar else null,
                    accounts = accounts,
                )
            }
        }
    }

    private companion object {
        /** Saved state (survives the process being killed in the background). */
        const val KEY_SECTION = "section"
        const val KEY_VIDEO = "video"
        const val KEY_POSITION = "position"
        /** "Qui regarde ?" answered (false: still on screen). Absent: a fresh start. */
        const val KEY_PICKED = "picked"
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        const val DEARROW_BATCH = 50
        const val RESUME_REFRESH_DELAY_MS = 1_500L
        const val SUGGEST_DELAY_MS = 300L
        const val NOTICE_MS = 3_000L
        /** This app's file on the update channel (miryo-releases › channel › lumentube.json). */
        const val UPDATE_APP = "lumentube"
        /** After a play-as video stops: the player's last position save starts (dispose)… */
        const val HISTORY_SETTLE_MS = 400L
        /** …and is waited for, at most this long, before miryo tube's account comes back. */
        const val HISTORY_WAIT_MS = 5_000L
        /** Kids profile: Accueil's rows' keys, and channels read at once (the Freebox Player POP is slow). */
        const val KIDS_ROW = "kids-"
        const val KIDS_PARALLEL = 3
        const val KIDS_EMPTY = "Aucune chaîne autorisée pour l’instant. Un parent en choisit depuis un profil adulte : « Pour Enfant » sur la page d’une chaîne."
    }

    override fun onCleared() {
        RemoteControl.uiAttached = false
        // Leaving while a miryo launch played as another account: miryo tube's own one again.
        if (playingAs) {
            AppScope.launch {
                delay(HISTORY_SETTLE_MS)
                var waited = 0L
                while (YouTube.historyWrites.get() > 0 && waited < HISTORY_WAIT_MS) {
                    delay(100)
                    waited += 100
                }
                runCatching { YouTube.endPlayAs() }
            }
        }
        accountListener?.let(YouTube::removeAccountListener)
        signIn?.dispose()
    }

    // RxJava's blocking calls wrap checked exceptions (TimeoutException…) in a RuntimeException.
    private fun Throwable.userMessage(): String = when (val e = if (this.javaClass == RuntimeException::class.java) cause ?: this else this) {
        is java.net.UnknownHostException, is java.net.ConnectException -> "Pas de connexion à Internet."
        is java.net.SocketTimeoutException, is java.util.concurrent.TimeoutException -> "YouTube ne répond pas. Réessaie."
        else -> "Impossible de charger cette page (${e::class.java.simpleName})."
    }
}
