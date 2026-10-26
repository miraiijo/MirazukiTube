package dev.miraiijo.lumentube.feed

import dev.miraiijo.lumentube.data.isAvailableVideo
import android.content.Context
import android.net.Uri
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import dev.miraiijo.lumentube.link.MirazukiLink
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Feed read by the miryo launcher (see FeedProvider): "continue" (videos started, not finished)
 * and "subscriptions" (latest videos), kept as a JSON file in filesDir. Written each time
 * miryo tube loads its history or its subscriptions, and by the provider when it refreshes them.
 * Only public video data is stored: no token, cookie or account id.
 */
object FeedCache {
    const val AUTHORITY = MirazukiLink.TUBE_AUTHORITY
    const val CONTINUE = "continue"
    const val SUBSCRIPTIONS = "subscriptions"
    const val MAX_ROWS = 20

    /** Contract with miryo (frozen): column names and order. */
    val COLUMNS = arrayOf("video_id", "title", "channel", "thumbnail_url", "duration_ms", "watched_percent", "position_s", "published_ms", "live")

    data class Item(
        val videoId: String,
        val title: String,
        val channel: String,
        val thumbnailUrl: String,
        val durationMs: Long,
        val watchedPercent: Int,
        val positionS: Long,
        val publishedMs: Long,
        val live: Boolean,
    ) {
        fun toRow(): Array<Any> = arrayOf(videoId, title, channel, thumbnailUrl, durationMs, watchedPercent, positionS, publishedMs, if (live) 1 else 0)
    }

    private const val FILE = "lumen_feed.json"
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    @Volatile
    private var context: Context? = null

    /** Contract with miryo (frozen): the /accounts columns. */
    val ACCOUNT_COLUMNS = arrayOf("account_id", "name", "avatar_url", "selected")
    const val ACCOUNTS = "accounts"
    private val ACCOUNT_ID = Regex("[0-9a-f]{64}")

    /** Called by the app and by the provider (whichever starts first). */
    fun attach(context: Context) {
        if (this.context == null) this.context = context.applicationContext
    }

    fun uri(list: String): Uri = Uri.parse("content://$AUTHORITY/$list")

    /** content://dev.miraiijo.lumentube.feed/ — miryo watches it with notifyForDescendants. */
    val ROOT: Uri get() = Uri.parse("content://$AUTHORITY/")

    /**
     * Tells miryo something changed: the root (its observer has notifyForDescendants) and each path,
     * for observers registered on a single path.
     */
    fun notifyReaders() {
        val resolver = context?.contentResolver ?: return
        runCatching {
            resolver.notifyChange(ROOT, null)
            listOf(CONTINUE, SUBSCRIPTIONS, ACCOUNTS).forEach { resolver.notifyChange(uri(it), null) }
        }
    }

    /**
     * An account's opaque id for miryo: HMAC-SHA256 of the engine's account identity with a random
     * key kept on this box. Stable across restarts, 64 hex characters, and it gives away nothing
     * (no e-mail, not even a hash anyone could recompute from an e-mail).
     */
    fun accountId(identity: String): String? {
        val ctx = context ?: return null
        val prefs = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = synchronized(this) {
            prefs.getString(KEY_SALT, null) ?: android.util.Base64.encodeToString(
                ByteArray(32).also { java.security.SecureRandom().nextBytes(it) },
                android.util.Base64.NO_WRAP,
            ).also { prefs.edit().putString(KEY_SALT, it).commit() }
        }
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(android.util.Base64.decode(key, android.util.Base64.NO_WRAP), "HmacSHA256"))
        return mac.doFinal(identity.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private const val PREFS = "lumen_feed"
    private const val KEY_SALT = "account_id_key"
    private const val KEY_PLAY_AS_HOME = "play_as_home"
    private const val KEY_SWAP_HOME = "swap_home"

    /** The account to come back to while the engine is switched for one of miryo's reads. Opaque id only. */
    var swapHome: String?
        get() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_SWAP_HOME, null)
        set(value) {
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply { if (value == null) remove(KEY_SWAP_HOME) else putString(KEY_SWAP_HOME, value) }?.commit()
        }

    /**
     * miryo tube's own account while the engine plays as another one for miryo (contract v2): kept
     * on disk so a process killed meanwhile goes back to it at the next start. Opaque id only.
     */
    var playAsHome: String?
        get() = context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.getString(KEY_PLAY_AS_HOME, null)
        set(value) {
            context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.apply { if (value == null) remove(KEY_PLAY_AS_HOME) else putString(KEY_PLAY_AS_HOME, value) }?.commit()
        }


    /** Feed rows from engine items: videos only (no Shorts, no channels / playlists), at most [MAX_ROWS]. */
    fun itemsFrom(items: List<MediaItem?>, list: String): List<Item> = items.asSequence()
        .filterNotNull()
        .filter { !it.isShorts && it.type != MediaItem.TYPE_CHANNEL && it.type != MediaItem.TYPE_PLAYLIST }
        .filter { it.videoId?.let(VIDEO_ID::matches) == true }
        .filter { list != CONTINUE || it.percentWatched in 1..94 }
        .filter { it.isAvailableVideo() }
        .distinctBy { it.videoId }
        .map { item ->
            val id = item.videoId
            val durationMs = item.durationMs.coerceAtLeast(0L)
            val percent = item.percentWatched.coerceIn(0, 100)
            val position = item.startTimeSeconds.toLong().takeIf { it > 0 } ?: (durationMs / 1000 * percent / 100)
            Item(
                videoId = id,
                title = item.title.orEmpty(),
                channel = item.author ?: item.secondTitle?.toString().orEmpty(),
                thumbnailUrl = httpsThumbnail(item.cardImageUrl, id),
                durationMs = durationMs,
                watchedPercent = percent,
                positionS = position,
                publishedMs = item.publishedDate.takeIf { it > 0 } ?: 0L,
                live = item.isLive,
            )
        }
        .take(MAX_ROWS)
        .toList()

    /**
     * Replaces one list of [accountId]'s cache (the account the data was fetched for: a switch while
     * it loaded never mixes accounts) and tells miryo.
     */
    fun store(list: String, items: List<Item>, accountId: String) {
        val ctx = context ?: return
        if (!ACCOUNT_ID.matches(accountId)) return
        synchronized(this) {
            val root = readRoot(ctx, accountId)
            root.put(list, JSONObject().put("updated_ms", System.currentTimeMillis()).put("items", JSONArray(items.map { it.toJson() })))
            write(ctx, accountId, root)
        }
        notifyReaders()
    }

    /** Content kept for an instant display, but refreshed at the next query (account just selected). */
    fun markStale(accountId: String) {
        val ctx = context ?: return
        if (!ACCOUNT_ID.matches(accountId)) return
        synchronized(this) {
            val root = readRoot(ctx, accountId)
            listOf(CONTINUE, SUBSCRIPTIONS).forEach { list -> root.optJSONObject(list)?.put("updated_ms", 0L) }
            write(ctx, accountId, root)
        }
    }

    /** Signed out of an account: its cache goes. */
    fun delete(accountId: String) {
        val ctx = context ?: return
        if (!ACCOUNT_ID.matches(accountId)) return
        synchronized(this) { runCatching { file(ctx, accountId).delete() } }
        notifyReaders()
    }

    /** A list of [accountId]'s cache and when it was written (0: never or stale). */
    fun load(list: String, accountId: String): Pair<Long, List<Item>> {
        val ctx = context ?: return 0L to emptyList()
        if (!ACCOUNT_ID.matches(accountId)) return 0L to emptyList()
        val entry = synchronized(this) { readRoot(ctx, accountId).optJSONObject(list) } ?: return 0L to emptyList()
        val array = entry.optJSONArray("items") ?: JSONArray()
        val items = (0 until array.length()).mapNotNull { array.optJSONObject(it)?.toItem() }
        return entry.optLong("updated_ms", 0L) to items.take(MAX_ROWS)
    }

    /** One file per account: mirazuki_feed_<account_id>.json (the id is 64 hex characters, safe as a name). */
    private fun file(ctx: Context, accountId: String) = android.util.AtomicFile(File(ctx.filesDir, "lumen_feed_$accountId.json"))

    private fun readRoot(ctx: Context, accountId: String): JSONObject =
        runCatching { JSONObject(String(file(ctx, accountId).readFully())) }.getOrDefault(JSONObject())

    /** AtomicFile: written to a side file, synced, then swapped (a power cut never leaves it empty). */
    private fun write(ctx: Context, accountId: String, root: JSONObject) {
        val file = file(ctx, accountId)
        runCatching {
            val out = file.startWrite()
            try {
                out.write(root.toString().toByteArray())
                file.finishWrite(out)
            } catch (e: java.io.IOException) {
                file.failWrite(out)
            }
        }
        // The single-file cache of 0.5.x is gone with the first write of the per-account one.
        runCatching { File(ctx.filesDir, FILE).delete() }
    }

    private fun httpsThumbnail(url: String?, videoId: String): String = when {
        url.isNullOrBlank() -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        url.startsWith("//") -> "https:$url"
        url.startsWith("http://") -> "https://" + url.removePrefix("http://")
        url.startsWith("https://") -> url
        else -> "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    private fun Item.toJson() = JSONObject()
        .put("video_id", videoId).put("title", title).put("channel", channel).put("thumbnail_url", thumbnailUrl)
        .put("duration_ms", durationMs).put("watched_percent", watchedPercent).put("position_s", positionS)
        .put("published_ms", publishedMs).put("live", live)

    private fun JSONObject.toItem(): Item? {
        val id = optString("video_id").takeIf(VIDEO_ID::matches) ?: return null
        return Item(
            videoId = id,
            title = optString("title"),
            channel = optString("channel"),
            thumbnailUrl = optString("thumbnail_url").takeIf { it.startsWith("https://") } ?: "https://i.ytimg.com/vi/$id/hqdefault.jpg",
            durationMs = optLong("duration_ms"),
            watchedPercent = optInt("watched_percent").coerceIn(0, 100),
            positionS = optLong("position_s"),
            publishedMs = optLong("published_ms"),
            live = optBoolean("live"),
        )
    }
}
