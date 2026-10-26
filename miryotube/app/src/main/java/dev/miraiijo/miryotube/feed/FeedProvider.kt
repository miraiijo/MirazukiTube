package dev.miraiijo.miryotube.feed

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import dev.miraiijo.miryotube.data.YouTube
import dev.miraiijo.miryotube.link.MiryoLink
import dev.miraiijo.miryotube.remote.RemoteControl
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Feed for the miryo launcher (frozen contract):
 *  - query /continue: videos started and not finished;
 *  - query /subscriptions: latest videos of the subscriptions;
 *  - query /accounts: the accounts linked in miryo tube (opaque id, name, avatar, selected);
 *  - query /search?q=…: the first 20 videos YouTube finds (same columns as /continue);
 *  - call "select_account" (arg = account_id): switches account, its feed is refreshed.
 * Contract v2 (contract=2&account=<account_id> on /continue, /subscriptions and /search): that
 * account's data, whatever account miryo tube shows, without switching it; an unknown account (or
 * an id longer than 128 characters) gets an empty list. Without account: miryo tube's account.
 * Trust (miryo-link v3, see link/MiryoLink.kt): only answers miryo, the calling package must be
 * dev.miraiijo.miryo signed with the certificate in BuildConfig.CERT_MIRYO (gradle.properties).
 * Not a signature permission: the two apps have their own keys. miryo tube also works without miryo.
 * Each account has its own cache (FeedCache), always answered at once; older than 30 min and
 * signed in, it's refreshed in the background (one refresh per account and list at a time, not
 * retried within a minute) and miryo is notified when the new one is written. Signed out: empty.
 * No e-mail, token or cookie, ever.
 */
class FeedProvider : ContentProvider() {
    private val matcher = UriMatcher(UriMatcher.NO_MATCH).apply {
        addURI(FeedCache.AUTHORITY, FeedCache.CONTINUE, CODE_CONTINUE)
        addURI(FeedCache.AUTHORITY, FeedCache.SUBSCRIPTIONS, CODE_SUBSCRIPTIONS)
        addURI(FeedCache.AUTHORITY, FeedCache.ACCOUNTS, CODE_ACCOUNTS)
        addURI(FeedCache.AUTHORITY, SEARCH, CODE_SEARCH)
    }

    /** Refreshes run here, never on the caller's (or the main) thread. */
    private val worker = Executors.newSingleThreadExecutor()

    /**
     * Searches: their own threads, never stuck behind a feed refresh. Two at once at most and no
     * queue: a third one while both are busy (YouTube slow) gets an empty answer at once instead of
     * piling up behind them.
     */
    private val searches = java.util.concurrent.ThreadPoolExecutor(
        0, 2, 30L, TimeUnit.SECONDS, java.util.concurrent.SynchronousQueue(),
    )

    /** Refreshes queued or running, as "account/list": never twice the same (single flight). */
    private val refreshing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Last refresh started per "account/list" (elapsedRealtime): a failing one isn't retried at every query. */
    private val lastRefresh = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Last searches answered (key: account and text), for a few minutes: the same search is instant. */
    private val searchCache = object : LinkedHashMap<String, Pair<Long, List<FeedCache.Item>>>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<Long, List<FeedCache.Item>>>?) = size > SEARCH_CACHE_SIZE
    }

    override fun onCreate(): Boolean {
        context?.let(FeedCache::attach)
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: throw SecurityException("Not ready")
        // Who asks comes first: anything else is refused before the URI is even looked at.
        if (!isMiryo(ctx)) throw SecurityException("This feed is only readable by the miryo launcher")
        val code = matcher.match(uri)
        if (code == UriMatcher.NO_MATCH) throw IllegalArgumentException("Unknown feed: $uri")
        ready(ctx)
        // Contract v2: an account given (contract=2 is accepted but changes nothing else).
        val asked = uri.getQueryParameter(PARAM_ACCOUNT)
        val account = when {
            asked == null -> YouTube.homeAccountId()
            asked.length > MAX_ACCOUNT_ID || !YouTube.isKnownAccount(asked) -> null
            else -> asked
        }
        val cursor = when (code) {
            CODE_ACCOUNTS -> accounts()
            CODE_SEARCH -> search(uri.getQueryParameter("q"), account, asked != null)
            else -> videos(if (code == CODE_CONTINUE) FeedCache.CONTINUE else FeedCache.SUBSCRIPTIONS, account)
        }
        cursor.setNotificationUri(ctx.contentResolver, uri)
        return cursor
    }

    /** One row per linked account. Only the opaque id, the display name, the avatar and "selected". */
    private fun accounts(): Cursor {
        val cursor = MatrixCursor(FeedCache.ACCOUNT_COLUMNS)
        YouTube.feedAccounts().forEach { account ->
            cursor.addRow(arrayOf<Any?>(account.id, account.name, account.avatarUrl, if (account.selected) "1" else "0"))
        }
        return cursor
    }

    /** [accountId]: whose list (null: signed out, or an unknown account asked for: empty). */
    private fun videos(list: String, accountId: String?): Cursor {
        val cursor = MatrixCursor(FeedCache.COLUMNS)
        if (accountId == null) return cursor
        val (updatedMs, items) = FeedCache.load(list, accountId)
        // The cache now, never a wait on YouTube; stale: refreshed behind (miryo is notified).
        if (System.currentTimeMillis() - updatedMs > MAX_AGE_MS) refreshLater(accountId, listOf(list))
        items.take(FeedCache.MAX_ROWS).forEach { cursor.addRow(it.toRow()) }
        return cursor
    }

    /**
     * Refreshes [lists] of [accountId] on the worker, as that account (the engine switches back
     * right after; miryo tube on screen: only if it's the engine's account). Single flight: a list
     * already queued or running, or started less than a minute ago, isn't asked again.
     */
    private fun refreshLater(accountId: String, lists: List<String>, force: Boolean = false) {
        val now = android.os.SystemClock.elapsedRealtime()
        val todo = lists.filter { list ->
            val key = "$accountId/$list"
            val recent = !force && (lastRefresh[key]?.let { now - it < RETRY_MS } ?: false)
            !recent && refreshing.add(key)
        }
        if (todo.isEmpty()) return
        todo.forEach { lastRefresh["$accountId/$it"] = now }
        val started = runCatching {
            worker.execute {
                try {
                    YouTube.asAccount(accountId, allowSwitch = RemoteControl.accountSwitchAllowed) { todo.forEach { YouTube.refreshFeed(it) } }
                } finally {
                    todo.forEach { refreshing.remove("$accountId/$it") }
                }
            }
        }
        if (started.isFailure) todo.forEach { refreshing.remove("$accountId/$it") }
    }

    /**
     * /search?q=…: YouTube's results for q (cut at 200 characters), videos only (no channel,
     * playlist or Short), 20 at most. Empty q, error, or no answer within 8 s: empty cursor.
     */
    private fun search(q: String?, accountId: String?, accountAsked: Boolean): Cursor {
        val cursor = MatrixCursor(FeedCache.COLUMNS)
        // Longer than 200 characters: cut at 200 (nothing more is sent to YouTube).
        val text = q?.trim().orEmpty().take(MAX_QUERY).trim()
        if (text.isEmpty()) return cursor
        // An unknown account asked for: empty, never another account's results.
        if (accountAsked && accountId == null) return cursor
        val cacheKey = "${accountId.orEmpty()}/${if (accountAsked) 1 else 0}/$text"
        val now = android.os.SystemClock.elapsedRealtime()
        synchronized(searchCache) { searchCache[cacheKey]?.takeIf { now - it.first < SEARCH_CACHE_MS }?.second }?.let { cached ->
            cached.forEach { cursor.addRow(it.toRow()) }
            return cursor
        }
        // Both search threads busy: empty at once (never queued behind slow ones).
        val request = runCatching { searches.submit<List<FeedCache.Item>> {
            val items = if (accountId == null) {
                YouTube.searchItems(text) // signed out
            } else {
                // As that account. miryo tube on screen on another account (no switch then): an account
                // asked for gets nothing rather than another account's results; none asked: its own.
                YouTube.asAccount(accountId, allowSwitch = RemoteControl.accountSwitchAllowed) { YouTube.searchItems(text) }
                    ?: if (accountAsked || YouTube.playingAsOther) emptyList() else YouTube.searchItems(text)
            }
            FeedCache.itemsFrom(items, SEARCH)
        } }.getOrNull() ?: return cursor
        val items = runCatching { request.get(REFRESH_TIMEOUT_S, TimeUnit.SECONDS) }
            .onFailure { request.cancel(true) }
            .getOrNull()
        if (!items.isNullOrEmpty()) synchronized(searchCache) { searchCache[cacheKey] = android.os.SystemClock.elapsedRealtime() to items }
        items.orEmpty().forEach { cursor.addRow(it.toRow()) }
        return cursor
    }

    /**
     * call(content://dev.miraiijo.miryotube.feed/, "select_account", account_id): selects that
     * account, marks its feed for refresh (its last content stays for an instant display) and
     * refreshes /continue and /subscriptions on the worker. Bundle "ok" = false for an unknown id;
     * null for an unknown method.
     */
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: throw SecurityException("Not ready")
        if (!isMiryo(ctx)) throw SecurityException("This feed is only readable by the miryo launcher")
        if (method != METHOD_SELECT_ACCOUNT) return null
        ready(ctx)
        val selected = arg != null && YouTube.selectFeedAccount(arg)
        if (selected && arg != null) {
            FeedCache.markStale(arg)
            // As that account (during a miryo launch played as another one, the engine isn't on it);
            // the same single flight as the queries' refreshes.
            refreshLater(arg, listOf(FeedCache.CONTINUE, FeedCache.SUBSCRIPTIONS), force = true)
        }
        return Bundle().apply { putBoolean("ok", selected) }
    }

    /** Providers start before Application.onCreate: the engine's prefs (accounts) must exist first. */
    private fun ready(ctx: android.content.Context) {
        GlobalPreferences.instance(ctx.applicationContext)
        FeedCache.attach(ctx)
        YouTube.watchAccountChanges()
        // A miryo launch cut short (process killed while playing as another account): back home.
        YouTube.restoreHome()
    }

    /**
     * The caller is miryo (miryo-link v3): its package name, given by the system and not by the
     * caller, and its signing certificate, the one this build expects (BuildConfig.CERT_MIRYO). Any
     * other app, miryo signed with another key, or a build without that certificate, is refused.
     */
    private fun isMiryo(ctx: android.content.Context): Boolean =
        callingPackage == MiryoLink.MIRYO && runCatching { MiryoLink.callerIsOneOf(ctx, MiryoLink.MIRYO) }.getOrDefault(false)

    override fun getType(uri: Uri): String? = when (matcher.match(uri)) {
        CODE_CONTINUE, CODE_SUBSCRIPTIONS -> "vnd.android.cursor.dir/vnd.miryotube.video"
        CODE_ACCOUNTS -> "vnd.android.cursor.dir/vnd.miryotube.account"
        CODE_SEARCH -> "vnd.android.cursor.dir/vnd.miryotube.video"
        else -> null
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Read-only feed")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only feed")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Read-only feed")

    private companion object {
        const val CODE_CONTINUE = 1
        const val CODE_SUBSCRIPTIONS = 2
        const val CODE_ACCOUNTS = 3
        const val CODE_SEARCH = 4
        const val SEARCH = "search"
        const val MAX_QUERY = 200
        const val MAX_AGE_MS = 30 * 60 * 1000L
        const val REFRESH_TIMEOUT_S = 8L
        const val RETRY_MS = 60_000L
        const val SEARCH_CACHE_SIZE = 8
        const val SEARCH_CACHE_MS = 5 * 60_000L
        const val METHOD_SELECT_ACCOUNT = "select_account"
        const val PARAM_ACCOUNT = "account"
        const val MAX_ACCOUNT_ID = 128
    }
}
