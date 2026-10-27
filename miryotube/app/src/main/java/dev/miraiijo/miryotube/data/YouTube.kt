package dev.miraiijo.miryotube.data

import com.liskovsoft.mediaserviceinterfaces.ServiceManager
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemMetadata
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import com.liskovsoft.mediaserviceinterfaces.data.SponsorSegment
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import dev.miraiijo.miryotube.feed.FeedCache
import dev.miraiijo.miryotube.ui.MiryoIcon
import io.reactivex.Observable
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/** The side menu's pages. [account]: only shown once signed in. */
enum class Section(val label: String, val icon: MiryoIcon, val account: Boolean = false) {
    // No "Tendances": YouTube removed its Trending page in 2025, the feed never answers anymore.
    SEARCH("Rechercher", MiryoIcon.SEARCH),
    HOME("Accueil", MiryoIcon.HOME),
    SUBSCRIPTIONS("Abonnements", MiryoIcon.SUBSCRIPTIONS, account = true),
    MUSIC("Musique", MiryoIcon.MUSIC),
    GAMING("Gaming", MiryoIcon.GAMEPAD),
    NEWS("Actus", MiryoIcon.NEWS),
    SPORTS("Sport", MiryoIcon.TROPHY),
    LIVE("Direct", MiryoIcon.LIVE),
    HISTORY("Historique", MiryoIcon.HISTORY, account = true),
    PLAYLISTS("Playlists", MiryoIcon.PLAYLIST, account = true),
}

/** A card: video, channel or playlist (the engine's MediaItem, kept to open it). */
@androidx.compose.runtime.Immutable
data class Video(
    val key: String,
    val videoId: String?,
    val title: String,
    val channel: String,
    val thumbnail: String?,
    val durationMs: Long,
    /** 0..100, from the account's history. */
    val watchedPercent: Int,
    val live: Boolean,
    val upcoming: Boolean,
    val badge: String?,
    val kind: Int,
    val raw: MediaItem,
    /** Picture replacing the thumbnail (DeArrow), shown instead of the HD one. */
    val customThumbnail: String? = null,
    /** When it was published, as YouTube says it (« il y a 3 jours »); null when unknown. */
    val published: String? = null,
    /** Its channel (null: unknown, or a playlist). */
    val channelId: String? = null,
) {
    val isChannel get() = kind == MediaItem.TYPE_CHANNEL
    val isVideo get() = kind == MediaItem.TYPE_VIDEO || kind == MediaItem.TYPE_MUSIC || kind == MediaItem.TYPE_UNDEFINED && videoId != null
}

/** A SponsorBlock segment the player skips. */
data class Segment(val startMs: Long, val endMs: Long, val category: String) {
    val label: String
        get() = when (category) {
            SponsorSegment.CATEGORY_SPONSOR -> "Sponsor"
            SponsorSegment.CATEGORY_SELF_PROMO -> "Autopromo"
            SponsorSegment.CATEGORY_INTERACTION -> "Rappel d’abonnement"
            SponsorSegment.CATEGORY_INTRO -> "Intro"
            SponsorSegment.CATEGORY_OUTRO -> "Générique de fin"
            else -> "Segment"
        }
}

/** A chapter: its title, start and YouTube's picture of it (null: none). */
data class Chapter(val title: String, val startMs: Long, val image: String? = null)

/** What the player shows next to a video: description, actions' state, chapters, next video. */
@androidx.compose.runtime.Immutable
data class Details(
    val description: String,
    val views: String?,
    val published: String?,
    val likes: String?,
    val liked: Boolean,
    val disliked: Boolean,
    /** Key of the video's comments (CommentsService), null when they're off. */
    val commentsKey: String?,
    val subscribed: Boolean,
    val channelId: String?,
    val channel: String?,
    val chapters: List<Chapter>,
    /** Next video: the playlist's next item, else YouTube's "À suivre". */
    val next: Video?,
    /** A live's chat (LiveChatService), null when it has none. */
    val liveChatKey: String? = null,
)

/** A message of a live's chat (read only: nothing is ever written from the box). */
@androidx.compose.runtime.Immutable
data class ChatMessage(val id: String, val author: String, val text: String)

/** A YouTube account linked on this box. */
data class Account(val name: String, val avatar: String?, val selected: Boolean, val raw: com.liskovsoft.mediaserviceinterfaces.oauth.Account)

/** A comment: author, relative date ("il y a 2 jours"), text, likes, replies label; [repliesKey] loads its replies. */
@androidx.compose.runtime.Immutable
data class Comment(
    val id: String,
    val author: String,
    val avatar: String?,
    val published: String?,
    val text: String,
    val likes: String?,
    val replies: String?,
    val repliesKey: String?,
)

data class CommentPage(val comments: List<Comment>, val next: String?)

/** DeArrow's community title / thumbnail for a video (each may be missing). */
@androidx.compose.runtime.Immutable
data class DeArrow(val title: String?, val thumbnail: String?)

/** Outcome of an action made with an account (like, subscription, À regarder plus tard…). */
enum class ActionResult {
    DONE,
    FAILED,
    /** The engine is no longer on the account the screen showed (switched by miryo): nothing sent. */
    ACCOUNT_CHANGED,
}

/** Shown when an action was dropped because the account changed under it. */
const val ACCOUNT_CHANGED_MESSAGE = "Le compte YouTube a changé : action annulée."

/** A row of cards; [raw] loads the next page (continueGroup). */
@androidx.compose.runtime.Immutable
data class VideoRow(val key: String, val title: String, val items: List<Video>, val raw: MediaGroup, val canLoadMore: Boolean = true)

/**
 * SmartTube's engine (MediaServiceCore) behind suspend functions. Every call blocks on the network,
 * so it runs on [Dispatchers.IO].
 */
object YouTube {
    private val service: ServiceManager get() = YouTubeServiceManager.instance()
    private const val OBSERVE_TIMEOUT_S = 30L

    /**
     * A section's rows, as they arrive: YouTube sends Accueil (and the other feeds) in several
     * parts, each one is shown as soon as it's here instead of waiting for the whole page.
     * Every emission is the full list so far.
     */
    fun rows(section: Section): Flow<List<VideoRow>> = flow {
        val content = service.contentService
        val rows = mutableListOf<VideoRow>()
        var index = 0

        /** Adds the groups of [parts] as they arrive; [limit] rows at most, [titled] names untitled ones. */
        suspend fun addFeed(parts: Observable<List<MediaGroup>>, limit: Int = Int.MAX_VALUE, titled: String? = null) {
            var added = 0
            val iterator = parts.timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).blockingIterable().iterator()
            try {
                while (iterator.hasNext()) {
                    for (group in iterator.next().filterNotNull()) {
                        if (added >= limit) break
                        val row = group.toRow("${section.name}-${index++}", titled) ?: continue
                        rows += row
                        added++
                    }
                    emit(rows.toList())
                    if (added >= limit) break
                }
            } finally {
                // Stops the feed's remaining requests (continuations nobody will show).
                (iterator as? Disposable)?.dispose()
            }
        }

        when (section) {
            Section.SEARCH -> emit(emptyList())
            // YouTube's own home only: the taste of each feed comes later, when the page's end is
            // reached ([homeExtras]), not 5 more requests at every start.
            Section.HOME -> safely { addFeed(content.homeObserve) }
            // One long feed each: cut into rows (by date / by progress) instead of one endless row.
            Section.SUBSCRIPTIONS -> subscriptionsForFeed()?.let {
                rows += it.bucketed("SUBSCRIPTIONS", SUBSCRIPTION_BUCKETS) { v -> subscriptionBucket(v) }
                emit(rows.toList())
            }
            Section.MUSIC -> addFeed(content.musicObserve)
            Section.GAMING -> addFeed(content.gamingObserve)
            Section.NEWS -> addFeed(content.newsObserve)
            Section.SPORTS -> addFeed(content.sportsObserve)
            Section.LIVE -> addFeed(content.liveObserve)
            Section.HISTORY -> historyForFeed()?.let {
                rows += it.bucketed("HISTORY", HISTORY_BUCKETS) { v -> if (v.watchedPercent in 1..RESUME_MAX_PERCENT) "À reprendre" else "Déjà vues" }
                emit(rows.toList())
            }
            Section.PLAYLISTS -> addFeed(content.playlistRowsObserve)
        }
        if (rows.isEmpty()) emit(emptyList())
    }.flowOn(Dispatchers.IO)

    /**
     * Accueil's extra rows (a taste of each feed), loaded once the end of YouTube's home is reached:
     * one feed after the other, each one's first part only, [HOME_EXTRA_ROWS] rows of it (the rest
     * of its requests are disposed). Every emission is the extra rows so far.
     */
    fun homeExtras(): Flow<List<VideoRow>> = flow {
        val content = service.contentService
        val rows = mutableListOf<VideoRow>()
        var index = 0
        // Cold observables (as before): nothing is requested until each one is iterated.
        val feeds = buildList<Pair<Observable<List<MediaGroup>>, String>> {
            if (signedIn) add(Observable.fromCallable { listOfNotNull(subscriptionsForFeed()) } to "Nouveautés des abonnements")
            add(content.musicObserve to Section.MUSIC.label)
            add(content.gamingObserve to Section.GAMING.label)
            add(content.newsObserve to Section.NEWS.label)
            add(content.sportsObserve to Section.SPORTS.label)
            add(content.liveObserve to Section.LIVE.label)
        }
        // One feed failing (or slow) never empties Accueil.
        for ((parts, title) in feeds) safely {
            val iterator = parts.timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).blockingIterable().iterator()
            try {
                var added = 0
                while (added < HOME_EXTRA_ROWS && iterator.hasNext()) {
                    for (group in iterator.next().filterNotNull()) {
                        if (added >= HOME_EXTRA_ROWS) break
                        rows += group.toRow("$HOME_EXTRA${index++}", title) ?: continue
                        added++
                    }
                }
            } finally {
                (iterator as? Disposable)?.dispose()
            }
            emit(rows.toList())
        }
        if (rows.isEmpty()) emit(emptyList())
    }.flowOn(Dispatchers.IO)

    /** Key prefix of Accueil's extra rows (told apart from YouTube's home rows). */
    const val HOME_EXTRA = "HOME-x-"
    private const val HOME_EXTRA_ROWS = 2

    // ---- miryo's feed (FeedProvider) -----------------------------------------------------------

    /** The account's subscriptions feed, also written to miryo's feed cache. Blocking. */
    private fun subscriptionsForFeed(): MediaGroup? {
        // The account it's fetched for, taken before: a switch meanwhile never mixes accounts.
        val epoch = switchEpoch
        val accountId = currentAccountId()
        return service.contentService.subscriptions?.also { storeIfSameEpoch(FeedCache.SUBSCRIPTIONS, it.mediaItems.orEmpty(), accountId, epoch) }
    }

    /** The account's history, also written to miryo's feed cache ("continue"). Blocking. */
    private fun historyForFeed(): MediaGroup? {
        val epoch = switchEpoch
        val accountId = currentAccountId()
        return service.contentService.history?.also { storeIfSameEpoch(FeedCache.CONTINUE, it.mediaItems.orEmpty(), accountId, epoch) }
    }

    /** An account as miryo sees it: opaque id, display name, https avatar. */
    data class FeedAccount(val id: String, val name: String, val avatarUrl: String?, val selected: Boolean)

    /**
     * What identifies an account in the engine (never sent anywhere: only its HMAC leaves, see
     * FeedCache.accountId). The e-mail when known, else the account's refresh token, else its name.
     */
    private fun identity(account: com.liskovsoft.mediaserviceinterfaces.oauth.Account): String? {
        val token = (account as? com.liskovsoft.googlecommon.service.oauth.YouTubeAccount)?.refreshToken
        return account.email?.takeIf { it.isNotBlank() } ?: token?.takeIf { it.isNotBlank() } ?: account.name?.takeIf { it.isNotBlank() }
    }

    private fun feedId(account: com.liskovsoft.mediaserviceinterfaces.oauth.Account): String? = identity(account)?.let(FeedCache::accountId)

    /** Opaque id of the selected account (null: signed out). Blocking (prefs). */
    fun currentAccountId(): String? = runCatching {
        if (!service.signInService.isSigned) null else service.signInService.selectedAccount?.let(::feedId)
    }.getOrNull()

    fun feedAccounts(): List<FeedAccount> {
        // "Selected" = miryo tube's own account, even while the engine plays as another one for miryo.
        val home = homeAccountId()
        return runCatching { service.signInService.accounts }.getOrNull().orEmpty()
            .filterNotNull()
            .filter { !it.isEmpty }
            .mapNotNull { account ->
                val id = feedId(account) ?: return@mapNotNull null
                FeedAccount(id, account.name?.takeIf { it.isNotBlank() } ?: "Compte", httpsAvatar(account.avatarImageUrl), id == home)
            }
    }

    // ---- miryo contract v2: an account's data without switching miryo tube's ------------------
    //
    // The engine has one active account for all its requests. To read (or play as) another one, it
    // is switched for the time of the request, then put back. Rules:
    //  - switches take the write lock; reads made for an account the engine is already on take the
    //    read lock (several at once), so the engine never changes under a request made through here;
    //  - the engine tells its listeners asynchronously (main thread): each real switch is counted
    //    and its notification swallowed, so neither the app nor miryo reloads for it;
    //  - the account to come back to is written on disk before any switch: a process killed
    //    meanwhile goes back to it at the next start ([restoreHome]);
    //  - feed caches are only written when no switch happened during the fetch ([switchEpoch]).

    private val accountLock = java.util.concurrent.locks.ReentrantReadWriteLock()

    /** Engine notifications still to come for switches made here: swallowed by the listener. */
    private val silentNotifications = java.util.concurrent.atomic.AtomicInteger()

    /** Incremented on every switch made here: a fetch that saw it change doesn't write the cache. */
    @Volatile
    private var switchEpoch = 0

    /** miryo tube's own account while the engine plays a miryo launch as another one ([beginPlayAs]). */
    @Volatile
    private var playAsHome: String? = null

    /** The account to come back to during one of miryo's read switches (in memory). */
    @Volatile
    private var swapHomeMem: String? = null

    /** miryo tube's own account (what its screens and miryo's "selected" mean). Blocking. */
    fun homeAccountId(): String? = playAsHome ?: swapHomeMem ?: currentAccountId()

    /** A linked account's opaque id (unknown ids are never acted on). */
    fun isKnownAccount(id: String): Boolean = id.length <= MAX_ACCOUNT_ID && engineAccount(id) != null

    private fun engineAccount(id: String): com.liskovsoft.mediaserviceinterfaces.oauth.Account? =
        runCatching { service.signInService.accounts }.getOrNull().orEmpty().filterNotNull().firstOrNull { !it.isEmpty && feedId(it) == id }

    /** Switch made here (write lock held): its notification is swallowed, the epoch moves. */
    private fun selectSilently(account: com.liskovsoft.mediaserviceinterfaces.oauth.Account) {
        val now = runCatching { service.signInService.selectedAccount }.getOrNull()
        // The engine doesn't notify a re-selection of the same account: nothing to swallow then.
        if (now != null && feedId(now) == feedId(account)) return
        silentNotifications.incrementAndGet()
        switchEpoch++
        try {
            service.signInService.selectAccount(account)
        } catch (t: Throwable) {
            silentNotifications.decrementAndGet()
            switchEpoch++
            throw t
        }
        service.invalidateCache()
        // Again after: a read that saw the first increment but still the old account can't store.
        switchEpoch++
    }

    /** True (and consumed) when an engine notification comes from a switch made here. */
    private fun swallowSilent(): Boolean {
        while (true) {
            val n = silentNotifications.get()
            if (n <= 0) return false
            if (silentNotifications.compareAndSet(n, n - 1)) return true
        }
    }

    /**
     * Runs [block] with the engine on account [id]: as it is when it's already there (read lock),
     * else switched for the time of [block] and put back (write lock). Without [allowSwitch]
     * (miryo tube on screen: its own requests would go out as that account), only the first case.
     * Null: unknown account, not run, or the engine busy for more than [waitS] s. Blocking.
     */
    fun <T> asAccount(id: String, allowSwitch: Boolean, waitS: Long = LOCK_WAIT_S, block: () -> T): T? {
        val read = accountLock.readLock()
        if (!read.tryLock(waitS, java.util.concurrent.TimeUnit.SECONDS)) return null
        try {
            if (currentAccountId() == id) return block()
        } finally {
            read.unlock()
        }
        if (!allowSwitch) return null
        val write = accountLock.writeLock()
        if (!write.tryLock(waitS, java.util.concurrent.TimeUnit.SECONDS)) return null
        try {
            val now = runCatching { service.signInService.selectedAccount }.getOrNull() ?: return null
            if (feedId(now) == id) return block()
            val target = engineAccount(id) ?: return null
            // Written before the switch: a process killed meanwhile comes back to it.
            val nowId = feedId(now)
            if (FeedCache.swapHome == null) FeedCache.swapHome = nowId
            swapHomeMem = playAsHome ?: nowId
            selectSilently(target)
            try {
                return block()
            } finally {
                runCatching { selectSilently(now) }
                swapHomeMem = null
                FeedCache.swapHome = null
            }
        } finally {
            write.unlock()
        }
    }

    /**
     * A video launched from miryo with an account: the engine plays as it (history and position
     * go to it) until [endPlayAs]; miryo tube's own account stays its home. False: same account as
     * miryo tube's, unknown, or signed out — then any play-as in progress ends too (the video plays
     * with miryo tube's account). Blocking.
     */
    fun beginPlayAs(id: String?): Boolean {
        val write = accountLock.writeLock()
        // Waits as long as needed (on IO): giving up would leave the view model unsure of the account.
        write.lock()
        try {
            restoreHomeLocked()
            val home = playAsHome ?: currentAccountId()
            val target = id?.let(::engineAccount)
            if (home == null || id == null || id == home || target == null) {
                endPlayAsLocked()
                return false
            }
            if (playAsHome == null) {
                playAsHome = home
                FeedCache.playAsHome = home
            }
            selectSilently(target)
            // Not told by the (swallowed) notification: the subscribed channels are the new account's.
            subscribedChannels = null
            return true
        } finally {
            write.unlock()
        }
    }

    /** Back to miryo tube's own account after a miryo launch. Blocking. */
    fun endPlayAs() {
        val write = accountLock.writeLock()
        write.lock()
        try {
            endPlayAsLocked()
        } finally {
            write.unlock()
        }
    }

    private fun endPlayAsLocked() {
        val home = playAsHome ?: FeedCache.playAsHome ?: return
        engineAccount(home)?.let { runCatching { selectSilently(it) } }
        playAsHome = null
        FeedCache.playAsHome = null
        subscribedChannels = null
    }

    /** True while a miryo launch plays as another account than miryo tube's. */
    val playingAsOther: Boolean get() = playAsHome != null

    /** A switch cut short (process killed): back to miryo tube's account at the next start. Blocking. */
    fun restoreHome() {
        // Nothing to restore (the usual case): no lock taken, every query of the provider goes on.
        if (playAsHome != null || (FeedCache.playAsHome == null && FeedCache.swapHome == null)) return
        val write = accountLock.writeLock()
        if (!write.tryLock(LOCK_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) return
        try {
            restoreHomeLocked()
        } finally {
            write.unlock()
        }
    }

    private fun restoreHomeLocked() {
        if (playAsHome != null) return
        // The play-as home first: a read switch made during a play-as had the played account as "now".
        val home = FeedCache.playAsHome ?: FeedCache.swapHome ?: return
        engineAccount(home)?.let { runCatching { selectSilently(it) } }
        FeedCache.swapHome = null
        FeedCache.playAsHome = null
    }

    /** Writes made from a fetch for [accountId] started at [epoch]: only if no switch happened since. */
    private fun storeIfSameEpoch(list: String, items: List<MediaItem?>, accountId: String?, epoch: Int) {
        if (accountId != null && epoch == switchEpoch) FeedCache.store(list, FeedCache.itemsFrom(items, list), accountId)
    }

    private const val LOCK_WAIT_S = 8L
    private const val MAX_ACCOUNT_ID = 128

    /** miryo's account switch: true when [accountId] is a linked account. */
    fun selectFeedAccount(accountId: String): Boolean {
        val account = engineAccount(accountId) ?: return false
        val write = accountLock.writeLock()
        if (!write.tryLock(LOCK_WAIT_S, java.util.concurrent.TimeUnit.SECONDS)) return false
        try {
            // A miryo launch is playing as another account: miryo tube's own account (its home)
            // changes, the engine stays on the one playing until the video stops.
            if (playAsHome != null) {
                playAsHome = accountId
                FeedCache.playAsHome = accountId
                FeedCache.notifyReaders()
                return true
            }
            return runCatching {
                service.signInService.selectAccount(account)
                service.invalidateCache()
            }.isSuccess
        } finally {
            write.unlock()
        }
    }

    private fun httpsAvatar(url: String?): String? = when {
        url.isNullOrBlank() -> null
        url.startsWith("//") -> "https:$url"
        url.startsWith("https://") -> url
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        else -> null
    }

    /** Held here: the engine keeps its listeners weakly. */
    private var accountListener: com.liskovsoft.mediaserviceinterfaces.SignInService.OnAccountChange? = null
    private val appAccountListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    /**
     * Account switched, added or removed (in miryo tube or by miryo): miryo is told
     * (content://…feed/ and its paths), and so is the app ([onAccountChanged]).
     */
    @Synchronized
    fun watchAccountChanges() {
        if (accountListener != null) return
        val listener = com.liskovsoft.mediaserviceinterfaces.SignInService.OnAccountChange {
            // A switch made for miryo (and undone right after): neither the app nor miryo reloads.
            if (swallowSilent()) return@OnAccountChange
            subscribedChannels = null
            FeedCache.notifyReaders()
            appAccountListeners.forEach { it() }
        }
        accountListener = listener
        runCatching { service.signInService.addOnAccountChange(listener) }
    }

    /** The app's own reaction to an account change made elsewhere (miryo). */
    fun onAccountChanged(listener: () -> Unit): () -> Unit {
        watchAccountChanges()
        appAccountListeners += listener
        return listener
    }

    fun removeAccountListener(listener: () -> Unit) {
        appAccountListeners -= listener
    }

    /** Refresh of one feed list for the provider (off the main thread). True when it was written. */
    fun refreshFeed(list: String): Boolean = runCatching {
        if (!signedIn) return false
        when (list) {
            FeedCache.CONTINUE -> historyForFeed()
            FeedCache.SUBSCRIPTIONS -> subscriptionsForFeed()
            else -> null
        } != null
    }.getOrDefault(false)

    /** Runs [block], ignoring its failure (not a cancellation: the page was left, stop there). */
    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // This feed is skipped, the rest of the page still loads.
        }
    }

    /** "Reprendre": videos started and not finished, from the account's history (null if none). */
    suspend fun resumeRow(): VideoRow? = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext null
        val history = runCatching { historyForFeed() }.getOrNull() ?: return@withContext null
        val started = history.mediaItems.orEmpty().filterNotNull()
            .filter { !it.isShorts && !it.isLive && it.videoId != null && it.percentWatched in 3..94 && it.isAvailableVideo() }
            .map { it.toVideo() }
            .distinctBy { it.key }
            .take(20)
        if (started.isEmpty()) null else VideoRow("resume", "Reprendre", started, history, canLoadMore = false)
    }

    /**
     * « En direct maintenant »: the subscriptions live right now, with their viewers when YouTube
     * says it (« 1,2 k spectateurs »); null when none (or signed out). One request (the
     * subscriptions' first page, also given to miryo's feed).
     */
    suspend fun liveNowRow(): VideoRow? = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext null
        val subs = runCatching { subscriptionsForFeed() }.getOrNull() ?: return@withContext null
        val live = subs.mediaItems.orEmpty().filterNotNull()
            .filter { it.isLive && !it.isShorts && it.videoId != null }
            .map { item -> item.toVideo().copy(published = item.viewersText()) }
            .distinctBy { it.key }
            .take(20)
        if (live.isEmpty()) null else VideoRow(LIVE_NOW, "En direct maintenant", live, subs, canLoadMore = false)
    }

    const val LIVE_NOW = "live-now"

    /** « 1,2 k spectateurs » / « 3 k watching »: the part of the second line that says it. */
    private fun MediaItem.viewersText(): String? =
        secondTitle?.toString().orEmpty().split('•', '·').map { it.trim() }
            .firstOrNull { part -> VIEWERS.any { part.contains(it, ignoreCase = true) } }

    private val VIEWERS = listOf("spectateur", "regardent", "watching", "viewers")

    /** Next page of a row (null when there's no more). */
    suspend fun more(row: VideoRow): VideoRow? = withContext(Dispatchers.IO) {
        val next = runCatching { service.contentService.continueGroup(row.raw) }.getOrNull()
        val page = next?.toRow(row.key)?.let { if (row.key.startsWith(CHANNEL_ROW)) it.withoutAuthor() else it }
        val added = page?.items.orEmpty().filter { new -> row.items.none { it.key == new.key } }
        if (next == null || added.isEmpty()) row.copy(canLoadMore = false) else row.copy(items = row.items + added, raw = next)
    }

    /** A channel or playlist card opened: its videos. */
    suspend fun open(item: Video): List<VideoRow> = withContext(Dispatchers.IO) {
        val content = service.contentService
        val groups = if (item.kind == MediaItem.TYPE_CHANNEL) content.getChannelObserve(item.raw).collectAll() else listOf(content.getGroup(item.raw))
        groups.filterNotNull().mapIndexedNotNull { index, group -> group.toRow("open-${item.key}-$index") }
    }

    /** [options]: SearchOptions flags (date, duration, type, sort), 0 = none. */
    suspend fun search(query: String, options: Int = 0): List<VideoRow> = withContext(Dispatchers.IO) {
        val content = service.contentService
        (if (options == 0) content.getSearch(query) else content.getSearch(query, options)).orEmpty()
            .mapIndexedNotNull { index, group -> group.toRow("search-$index") }
    }

    /** miryo's /search: the engine's raw results (blocking, called off the main thread by the provider). */
    fun searchItems(query: String): List<MediaItem?> =
        service.contentService.getSearch(query).orEmpty().filterNotNull().flatMap { it.mediaItems.orEmpty() }

    /** Search suggestions while typing (YouTube's own). */
    suspend fun suggestions(text: String): List<String> = withContext(Dispatchers.IO) {
        runCatching {
            service.contentService.getSearchTagsObserve(text).timeout(5, TimeUnit.SECONDS).blockingFirst(emptyList())
        }.getOrDefault(emptyList()).filterNotNull().filter { it.isNotBlank() }.take(8)
    }

    // ---- Channels ---------------------------------------------------------------------------------

    /** A channel page: its rows (uploads, lives, playlists…), as YouTube orders them. */
    suspend fun channelRows(channelId: String): List<VideoRow> = withContext(Dispatchers.IO) {
        service.contentService.getChannelObserve(channelId).collectAll().filterNotNull()
            .mapIndexedNotNull { index, group -> group.toRow("$CHANNEL_ROW$channelId-$index")?.withoutAuthor() }
    }

    /** The channel's videos in each available order ("Plus récentes", "Populaires"…), may be empty. */
    suspend fun channelSorts(channelId: String): List<VideoRow> = withContext(Dispatchers.IO) {
        runCatching { service.contentService.getChannelSortingOptionsObserve(channelId)?.collectAll() }.getOrNull().orEmpty()
            .filterNotNull().mapIndexedNotNull { index, group -> group.toRow("${CHANNEL_ROW}sort-$channelId-$index")?.withoutAuthor() }
    }

    /** Keys of a channel page's rows (their cards show the date, not the channel's name again). */
    private const val CHANNEL_ROW = "channel-"

    /**
     * On a channel page, a card's second line without the channel (its name, its @handle): what's
     * left of YouTube's line ("12 k vues • il y a 3 jours"), empty when nothing is.
     */
    private fun VideoRow.withoutAuthor(): VideoRow = copy(
        items = items.map { video ->
            if (!video.isVideo) return@map video
            val author = video.raw.author?.trim()
            val parts = video.raw.secondTitle?.toString().orEmpty().split('•', '·')
                .map { it.trim() }
                .filter { it.isNotEmpty() && it != author && !it.startsWith("@") }
            video.copy(channel = parts.joinToString(" • "))
        },
    )

    /** Ids of the subscribed channels, fetched once per account (and kept up to date by [setSubscribed]). */
    @Volatile
    private var subscribedChannels: MutableSet<String>? = null

    suspend fun isSubscribed(channelId: String): Boolean = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext false
        val known = subscribedChannels ?: runCatching {
            java.util.concurrent.ConcurrentHashMap.newKeySet<String>().apply {
                addAll(service.contentService.subscribedChannels?.mediaItems.orEmpty().mapNotNull { it?.channelId })
            }
        }.getOrNull()?.also { subscribedChannels = it }
        known?.contains(channelId) == true
    }

    // ---- Card menu --------------------------------------------------------------------------------

    /** "Pas intéressé" ([channel] = false) or "Ne pas recommander la chaîne" can be offered for this card. */
    fun hasFeedback(video: Video): Boolean = video.raw.feedbackToken != null || video.raw.feedbackToken2 != null || video.raw.feedbackEndpoint != null

    /**
     * Tells YouTube (as SmartTube does): this video, or this channel, no more in the
     * recommendations — as [account] only.
     */
    suspend fun notInterested(video: Video, channel: Boolean, account: String?): ActionResult = withContext(Dispatchers.IO) {
        val item = video.raw
        val endpoint = item.feedbackEndpoint
        val token = runCatching {
            if (endpoint != null) {
                service.mediaItemService.getFeedbackTokensObserve(endpoint).timeout(10, TimeUnit.SECONDS).blockingFirst(emptyList())
                    .getOrNull(if (channel) 1 else 0)
            } else {
                if (channel) item.feedbackToken2 else item.feedbackToken
            }
        }.getOrNull() ?: return@withContext ActionResult.FAILED
        onAccount(account) { service.mediaItemService.markAsNotInterested(token) }
    }

    suspend fun formatInfo(videoId: String): MediaItemFormatInfo? = withContext(Dispatchers.IO) {
        service.mediaItemService.getFormatInfo(videoId)
    }

    /** Seek preview pictures (YouTube's storyboard), null when the video has none. */
    suspend fun storyboard(info: MediaItemFormatInfo): MediaItemStoryboard? = withContext(Dispatchers.IO) {
        runCatching { info.createStoryboard()?.also { it.groupSize } }.getOrNull()?.takeIf { it.groupDurationMS > 0 }
    }

    /** Description, like / subscription state, chapters and next video. [item]: the card played (playlist context). */
    suspend fun details(videoId: String, item: MediaItem?): Details? = withContext(Dispatchers.IO) {
        val meta = runCatching { if (item != null) service.mediaItemService.getMetadata(item) else service.mediaItemService.getMetadata(videoId) }.getOrNull()
            ?: return@withContext null
        Details(
            description = meta.description.orEmpty(),
            views = meta.viewCount?.takeIf { it.isNotBlank() },
            published = meta.publishedDate?.takeIf { it.isNotBlank() },
            likes = meta.likeCount?.takeIf { it.isNotBlank() },
            liked = meta.likeStatus == MediaItemMetadata.LIKE_STATUS_LIKE,
            disliked = meta.likeStatus == MediaItemMetadata.LIKE_STATUS_DISLIKE,
            commentsKey = meta.commentsKey?.takeIf { it.isNotBlank() },
            liveChatKey = meta.liveChatKey?.takeIf { it.isNotBlank() },
            subscribed = meta.isSubscribed,
            channelId = meta.channelId,
            channel = meta.author,
            chapters = meta.chapters.orEmpty().filterNotNull().map { Chapter(it.title.orEmpty(), it.startTimeMs, it.cardImageUrl?.takeIf { url -> url.startsWith("http") }) }.sortedBy { it.startMs },
            next = meta.nextVideo?.takeIf { it.videoId != null && it.videoId != videoId }?.toVideo(),
        )
    }

    /**
     * The engine's account right now (opaque id, null: signed out): what account-bound actions of a
     * screen are checked against ([onAccount]). Blocking-free for the caller (IO).
     */
    suspend fun activeAccountId(): String? = withContext(Dispatchers.IO) { currentAccountId() }

    /**
     * An account-bound request ([block], blocking): under the same lock as every account switch
     * (read side: switches wait for it, it waits for them), and only if the engine is still on
     * [expected] — the account the screen showed when the action was asked. Otherwise (miryo
     * switched the account meanwhile, or the engine is busy for more than 8 s) nothing is sent.
     */
    private fun onAccount(expected: String?, block: () -> Unit): ActionResult {
        val read = accountLock.readLock()
        if (!read.tryLock(LOCK_WAIT_S, TimeUnit.SECONDS)) return ActionResult.ACCOUNT_CHANGED
        try {
            // Checked last thing before sending: a switch can't happen while the read lock is held.
            if (expected == null || currentAccountId() != expected) return ActionResult.ACCOUNT_CHANGED
            return if (runCatching(block).isSuccess) ActionResult.DONE else ActionResult.FAILED
        } finally {
            read.unlock()
        }
    }

    /** Like (or remove the like), as [account] only. */
    suspend fun setLiked(videoId: String, item: MediaItem?, liked: Boolean, account: String?): ActionResult = withContext(Dispatchers.IO) {
        onAccount(account) {
            val target = item ?: itemFor(videoId)
            if (liked) service.mediaItemService.setLike(target) else service.mediaItemService.removeLike(target)
        }
    }

    /** "Je n'aime pas" (or remove it), as [account] only. */
    suspend fun setDisliked(videoId: String, item: MediaItem?, disliked: Boolean, account: String?): ActionResult = withContext(Dispatchers.IO) {
        onAccount(account) {
            val target = item ?: itemFor(videoId)
            if (disliked) service.mediaItemService.setDislike(target) else service.mediaItemService.removeDislike(target)
        }
    }

    /** Estimated dislikes (Return YouTube Dislike, asked by the engine), e.g. "1,2 k"; null if unknown. */
    suspend fun dislikes(videoId: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            service.mediaItemService.getDislikeDataObserve(videoId).timeout(10, TimeUnit.SECONDS).blockingFirst(null)?.dislikeCount
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /** A page of comments (or of a comment's replies) and the key of the next one. */
    suspend fun comments(key: String): CommentPage? = withContext(Dispatchers.IO) {
        val group = runCatching {
            service.commentsService.getCommentsObserve(key).timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).blockingFirst(null)
        }.getOrNull() ?: return@withContext null
        CommentPage(
            // The engine's isEmpty() means "no replies", not an empty comment.
            comments = group.comments.orEmpty().filterNotNull().filter { !it.message.isNullOrBlank() }.map { c ->
                Comment(
                    id = c.id ?: "${c.authorName}:${c.message?.hashCode()}",
                    author = c.authorName.orEmpty(),
                    avatar = c.authorPhoto,
                    published = c.publishedDate,
                    text = c.message.orEmpty(),
                    likes = c.likeCount?.takeIf { it.isNotBlank() && it != "0" },
                    // YouTube's label ("12 réponses"), shown as is.
                    replies = c.replyCount?.takeIf { !c.isEmpty && it.isNotBlank() },
                    repliesKey = c.nestedCommentsKey?.takeIf { !c.isEmpty && it.isNotBlank() },
                )
            },
            next = group.nextCommentsKey?.takeIf { it.isNotBlank() },
        )
    }

    /** A live's chat as it comes (read only); ends with the flow's collector. */
    fun liveChat(key: String): Flow<ChatMessage> = kotlinx.coroutines.flow.callbackFlow {
        val subscription = service.liveChatService.openLiveChatObserve(key)
            .subscribeOn(Schedulers.io())
            .subscribe(
                { item ->
                    val text = item?.message?.trim().orEmpty()
                    if (text.isNotEmpty()) trySend(ChatMessage(item.id ?: "${item.authorName}:${text.hashCode()}", item.authorName.orEmpty(), text))
                },
                { close() },
                { close() },
            )
        awaitClose { subscription.dispose() }
    }

    /** Adds the video to "À regarder plus tard" (YouTube's WL playlist), as [account] only. */
    suspend fun watchLater(videoId: String, account: String?): ActionResult = withContext(Dispatchers.IO) {
        onAccount(account) { service.mediaItemService.addToPlaylistObserve(WATCH_LATER, videoId).blockingSubscribe() }
    }

    /** Subscribes to (or unsubscribes from) a channel, as [account] only. */
    suspend fun setSubscribed(channelId: String, subscribed: Boolean, account: String?): ActionResult = withContext(Dispatchers.IO) {
        onAccount(account) {
            if (subscribed) service.mediaItemService.subscribe(channelId) else service.mediaItemService.unsubscribe(channelId)
            subscribedChannels?.let { if (subscribed) it += channelId else it -= channelId }
        }
    }

    /** DeArrow (sponsor.ajay.app) titles and thumbnails for these videos, by video id. */
    suspend fun deArrow(videoIds: List<String>): Map<String, DeArrow> = withContext(Dispatchers.IO) {
        if (videoIds.isEmpty()) return@withContext emptyMap()
        runCatching {
            service.mediaItemService.getDeArrowDataObserve(videoIds)
                .timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS)
                .toList().blockingGet()
                .filter { it.videoId != null }
                .associate { it.videoId to DeArrow(it.title?.takeIf { t -> t.isNotBlank() }, it.thumbnailUrl?.takeIf { u -> u.isNotBlank() }) }
        }.getOrDefault(emptyMap())
    }

    /** SponsorBlock segments to skip (sponsors, self-promotion, reminders, intro, end credits). */
    suspend fun skipSegments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        runCatching { service.mediaItemService.getSponsorSegments(videoId, SKIPPED_CATEGORIES) }.getOrNull().orEmpty()
            .filter { it.action == null || it.action == SponsorSegment.ACTION_SKIP }
            .filter { it.endMs > it.startMs }
            .map { Segment(it.startMs, it.endMs, it.category.orEmpty()) }
            .sortedBy { it.startMs }
    }

    private val SKIPPED_CATEGORIES = setOf(
        SponsorSegment.CATEGORY_SPONSOR,
        SponsorSegment.CATEGORY_SELF_PROMO,
        SponsorSegment.CATEGORY_INTERACTION,
        SponsorSegment.CATEGORY_INTRO,
        SponsorSegment.CATEGORY_OUTRO,
    )

    /** Keeps YouTube's history (and "resume where you stopped") up to date. */
    suspend fun savePosition(videoId: String, seconds: Float) = withContext(Dispatchers.IO) {
        historyWrites.incrementAndGet()
        val read = accountLock.readLock()
        try {
            // Not during one of miryo's read switches (it would go to that account): waits for it.
            if (read.tryLock(LOCK_WAIT_S, TimeUnit.SECONDS)) {
                try {
                    runCatching { service.mediaItemService.updateHistoryPosition(videoId, seconds) }
                } finally {
                    read.unlock()
                }
            }
        } finally {
            historyWrites.decrementAndGet()
        }
    }

    /** History writes in flight: a play-as ends only once they're done (they go to its account). */
    val historyWrites = java.util.concurrent.atomic.AtomicInteger()

    val signedIn: Boolean get() = runCatching { service.signInService.isSigned }.getOrDefault(false)

    /** Accounts linked on this box (sign in again to add one). */
    suspend fun accounts(): List<Account> = withContext(Dispatchers.IO) {
        // "Selected" = miryo tube's own account, even while a miryo launch plays as another one.
        val home = homeAccountId()
        runCatching { service.signInService.accounts }.getOrNull().orEmpty().filterNotNull()
            .filter { !it.isEmpty }
            .map { Account(it.name ?: it.email ?: "Compte", it.avatarImageUrl, feedId(it) == home, it) }
    }

    suspend fun selectAccount(account: Account) {
        withContext(Dispatchers.IO) {
            // Through the same lock as miryo's switches (and as its home while a miryo launch plays).
            val id = feedId(account.raw)
            if (id != null) {
                selectFeedAccount(id)
                return@withContext
            }
            // No id (no identity at all): still through the switches' lock (never under an action).
            val write = accountLock.writeLock()
            if (!write.tryLock(LOCK_WAIT_S, TimeUnit.SECONDS)) return@withContext
            try {
                runCatching {
                    service.signInService.selectAccount(account.raw)
                    service.invalidateCache()
                }
            } finally {
                write.unlock()
            }
        }
    }

    /**
     * Invité: no account selected (the linked ones stay on the box). YouTube then serves its
     * signed-out pages: nothing is added to anyone's history or recommendations. False while a
     * miryo launch plays as another account (the engine isn't miryo tube's to change then).
     */
    suspend fun useGuest(): Boolean = withContext(Dispatchers.IO) {
        val write = accountLock.writeLock()
        if (!write.tryLock(LOCK_WAIT_S, TimeUnit.SECONDS)) return@withContext false
        try {
            if (playAsHome != null) return@withContext false
            runCatching {
                service.signInService.selectAccount(null)
                service.invalidateCache()
            }.isSuccess
        } finally {
            write.unlock()
        }
    }

    /** miryo tube's own account (not the one a miryo launch plays as). */
    private fun homeAccount(): com.liskovsoft.mediaserviceinterfaces.oauth.Account? =
        (playAsHome ?: swapHomeMem)?.let(::engineAccount) ?: runCatching { service.signInService.selectedAccount }.getOrNull()

    val accountName: String? get() = runCatching { homeAccount()?.name }.getOrNull()

    val accountAvatar: String? get() = runCatching { homeAccount()?.avatarImageUrl }.getOrNull()

    /**
     * Sign-in with a code typed on youtube.com/activate (from a phone or computer).
     * [onCode] gets the code to show, [onDone] runs once the account is linked.
     */
    fun signIn(onCode: (String) -> Unit, onError: (String) -> Unit, onDone: () -> Unit): Disposable =
        service.signInService.signInObserve()
            .subscribeOn(Schedulers.io())
            .observeOn(io.reactivex.android.schedulers.AndroidSchedulers.mainThread())
            .subscribe({ onCode(it) }, { onError(it.message ?: "Connexion impossible") }, {
                service.invalidateCache()
                onDone()
            })

    fun signOut() {
        val write = accountLock.writeLock()
        write.lock()
        try {
            signOutLocked()
        } finally {
            write.unlock()
        }
    }

    private fun signOutLocked() {
        // A miryo launch playing as another account: back to miryo tube's first, it's the one signed out.
        endPlayAsLocked()
        runCatching {
            val signIn = service.signInService
            signIn.selectedAccount?.let { account ->
                // That account's feed cache goes with it.
                feedId(account)?.let(FeedCache::delete)
                signIn.removeAccount(account)
            }
            service.invalidateCache()
        }
    }

    /** Every emission of a (finite) observable, flattened: some sections arrive in several parts. */
    private fun Observable<List<MediaGroup>>.collectAll(): List<MediaGroup?> =
        timeout(OBSERVE_TIMEOUT_S, TimeUnit.SECONDS).toList().blockingGet().flatten()

    /**
     * A long feed cut into rows: its first [BUCKET_PAGES] pages, each video in the row [bucket]
     * names, rows in [order] (empty ones left out). The last row keeps the feed's continuation, so
     * scrolling it loads the rest (older videos come after anyway). Blocking.
     */
    private fun MediaGroup.bucketed(keyPrefix: String, order: List<String>, bucket: (Video) -> String): List<VideoRow> {
        val pages = mutableListOf(this)
        while (pages.size < BUCKET_PAGES) {
            pages += runCatching { service.contentService.continueGroup(pages.last()) }.getOrNull() ?: break
        }
        val videos = pages.flatMap { it.mediaItems.orEmpty().filterNotNull() }.filter { !it.isShorts }.map { it.toVideo() }.distinctBy { it.key }
        val groups = videos.groupBy(bucket)
        val names = order.filter { !groups[it].isNullOrEmpty() }
        return names.mapIndexed { index, name ->
            val last = index == names.lastIndex
            VideoRow("$keyPrefix-$name", name, groups.getValue(name), if (last) pages.last() else this, canLoadMore = last)
        }
    }

    /** A subscription video's row: live, upcoming, then by age (published date, else "il y a 3 jours"). */
    private fun subscriptionBucket(video: Video): String {
        if (video.live) return "En direct"
        if (video.upcoming) return "Bientôt"
        val hours = ageHours(video) ?: return "Plus ancien"
        return when {
            hours < 24 -> "Aujourd’hui"
            hours < 48 -> "Hier"
            hours < 24 * 7 -> "Cette semaine"
            hours < 24 * 31 -> "Ce mois-ci"
            else -> "Plus ancien"
        }
    }

    /** When a video was published (ms, from [ageHours]: to the hour), null when unknown. */
    fun publishedAt(video: Video): Long? = ageHours(video)?.let { System.currentTimeMillis() - it * 3_600_000L }

    /**
     * The account's subscriptions as channel cards (avatar, name), the ones with new videos first
     * (YouTube's order); empty when signed out or on failure. Blocking-free for the caller (IO).
     */
    suspend fun subscribedChannelCards(): List<Video> = withContext(Dispatchers.IO) {
        if (!signedIn) return@withContext emptyList()
        runCatching { service.contentService.subscribedChannelsByNewContent?.mediaItems.orEmpty() }.getOrDefault(emptyList())
            .filterNotNull().filter { !it.channelId.isNullOrBlank() }.map { it.toVideo() }.distinctBy { it.channelId }
    }

    /** Hours since publication: the engine's date when it has one, else read from the card's text. */
    private fun ageHours(video: Video): Long? {
        // Milliseconds (seconds on some engine versions: read as such when too small to be ms).
        val published = runCatching { video.raw.publishedDate }.getOrDefault(0L).let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        if (published > 0) return ((System.currentTimeMillis() - published) / 3_600_000L).coerceAtLeast(0)
        val text = video.raw.secondTitle?.toString()?.lowercase() ?: return null
        val match = AGE.find(text) ?: return null
        val n = match.groupValues[1].toLongOrNull() ?: return null
        val unit = match.groupValues[2]
        return when {
            unit.startsWith("min") || unit.startsWith("sec") || unit.startsWith("s") && !unit.startsWith("sem") -> 0
            unit.startsWith("h") -> n
            unit.startsWith("j") || unit.startsWith("d") -> n * 24
            unit.startsWith("sem") || unit.startsWith("w") -> n * 24 * 7
            unit.startsWith("mo") -> n * 24 * 30
            unit.startsWith("an") || unit.startsWith("y") -> n * 24 * 365
            else -> null
        }
    }

    private const val BUCKET_PAGES = 3
    private const val RESUME_MAX_PERCENT = 94
    private val SUBSCRIPTION_BUCKETS = listOf("En direct", "Bientôt", "Aujourd’hui", "Hier", "Cette semaine", "Ce mois-ci", "Plus ancien")
    private val HISTORY_BUCKETS = listOf("À reprendre", "Déjà vues")
    /** "il y a 3 jours", "3 days ago", "il y a 1 semaine"…: the number and its unit. */
    private val AGE = Regex("""(\d+)\s*(secondes?|seconds?|minutes?|mins?|heures?|hours?|h\b|jours?|days?|semaines?|weeks?|mois|months?|ans?|années?|years?)""")

    private fun MediaGroup.toRow(key: String, fallbackTitle: String? = null): VideoRow? {
        val items = mediaItems.orEmpty().filterNotNull().filter { !it.isShorts }.map { it.toVideo() }.distinctBy { it.key }
        if (items.isEmpty()) return null
        val own = title?.takeIf { it.isNotBlank() }
        // A feed's row on Accueil: "Musique · Top titres" (or just "Musique").
        val shown = when {
            fallbackTitle == null -> own ?: "Vidéos"
            own == null || own == fallbackTitle -> fallbackTitle
            else -> "$fallbackTitle · $own"
        }
        return VideoRow(key, shown, items, this)
    }

    /**
     * A bare MediaItem holding only a video id, for engine calls that take an item but only read its
     * id (like / remove like) when the video was opened from a link, without a card.
     */
    private fun itemFor(videoId: String): MediaItem =
        java.lang.reflect.Proxy.newProxyInstance(MediaItem::class.java.classLoader, arrayOf(MediaItem::class.java)) { _, method, _ ->
            when {
                method.name == "getVideoId" -> videoId
                method.name == "getType" -> MediaItem.TYPE_VIDEO
                method.returnType == Boolean::class.javaPrimitiveType -> false
                method.returnType == Int::class.javaPrimitiveType -> 0
                method.returnType == Long::class.javaPrimitiveType -> 0L
                method.returnType == Float::class.javaPrimitiveType -> 0f
                method.returnType == Double::class.javaPrimitiveType -> 0.0
                else -> null
            }
        } as MediaItem

    private const val WATCH_LATER = "WL"

    /** An engine item (e.g. the next video) as a card. */
    fun video(item: MediaItem): Video = item.toVideo()

    private fun MediaItem.toVideo() = Video(
        key = videoId ?: channelId?.let { "channel:$it" } ?: playlistId?.let { "playlist:$it" } ?: "item:$id:$title",
        videoId = videoId,
        title = title.orEmpty(),
        channel = author ?: secondTitle?.toString().orEmpty(),
        thumbnail = cardImageUrl ?: backgroundImageUrl,
        durationMs = durationMs,
        watchedPercent = percentWatched.coerceIn(0, 100),
        live = isLive,
        upcoming = isUpcoming,
        badge = badgeText?.takeIf { it.isNotBlank() },
        kind = type,
        raw = this,
        published = if (type == MediaItem.TYPE_CHANNEL || type == MediaItem.TYPE_PLAYLIST || isLive || isUpcoming) null else publishedText(),
        channelId = channelId?.takeIf { it.isNotBlank() },
    )

    /**
     * « il y a 3 jours »: the part of YouTube's second line that says it (its own wording), else
     * the engine's date put the same way, null when neither is there.
     */
    private fun MediaItem.publishedText(): String? {
        secondTitle?.toString().orEmpty().split('•', '·').map { it.trim() }
            .firstOrNull { part -> AGE.containsMatchIn(part.lowercase()) && (part.contains("il y a", true) || part.contains("ago", true)) }
            ?.let { return it }
        val date = runCatching { publishedDate }.getOrDefault(0L).let { if (it in 1 until 100_000_000_000L) it * 1000 else it }
        if (date <= 0) return null
        val minutes = ((System.currentTimeMillis() - date) / 60_000L).coerceAtLeast(0)
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 60 -> "il y a ${minutes.coerceAtLeast(1)} min"
            hours < 24 -> "il y a $hours h"
            days < 7 -> if (days == 1L) "il y a 1 jour" else "il y a $days jours"
            days < 31 -> (days / 7).let { if (it == 1L) "il y a 1 semaine" else "il y a $it semaines" }
            days < 365 -> "il y a ${days / 30} mois"
            else -> (days / 365).let { if (it == 1L) "il y a 1 an" else "il y a $it ans" }
        }
    }
}

/**
 * Still watchable: a video deleted or made private since it was watched stays in the history,
 * with no picture, no channel, and a title like « [Vidéo privée] »; it played « vidéo
 * indisponible ». Used by Reprendre here and by the rows given to miryo (FeedCache).
 */
internal fun MediaItem.isAvailableVideo(): Boolean {
    val name = title?.trim().orEmpty()
    if (name.isEmpty() || UNAVAILABLE_TITLES.any { name.equals(it, ignoreCase = true) }) return false
    if (cardImageUrl.isNullOrBlank() && backgroundImageUrl.isNullOrBlank()) return false
    return !(author.isNullOrBlank() && secondTitle.isNullOrBlank())
}

private val UNAVAILABLE_TITLES = listOf(
    "[Vidéo privée]", "[Vidéo supprimée]", "Vidéo privée", "Vidéo supprimée", "Vidéo non disponible",
    "[Private video]", "[Deleted video]", "Private video", "Deleted video", "Video unavailable",
)
