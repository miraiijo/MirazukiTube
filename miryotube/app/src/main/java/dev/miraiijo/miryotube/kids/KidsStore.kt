package dev.miraiijo.miryotube.kids

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A channel a parent allowed for the kids profile. */
@androidx.compose.runtime.Immutable
data class KidChannel(val id: String, val name: String, val avatar: String?)

/** What a parental code check gives. */
sealed interface PinResult {
    data object Ok : PinResult
    data object Wrong : PinResult
    data class Locked(val minutes: Int) : PinResult
    /** miryo couldn't check the code this time (its Keystore was busy): try again. */
    data object Unavailable : PinResult
}

/**
 * miryo tube's kids profile (one per box): no YouTube account, only the channels a parent allowed,
 * no search, no comments, no YouTube suggestions; leaving it asks for the parental code. Exists
 * once a code is set. [active] survives restarts: a kid closing the app comes back to the profile.
 * Only the code's salted hash is stored ([PinHash]); wrong codes are counted on disk, and after
 * [MAX_TRIES] the code is refused for [LOCK_MS] (the 10 000 codes can't be tried in a row).
 */
class KidsStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("kids", Context.MODE_PRIVATE)

    /**
     * miryo has a parental code: it is the suite's, so it guards this kids profile too (one code,
     * one count of wrong tries). Read with [refreshSuite]; the app's own code is used without miryo.
     */
    @Volatile
    var usesMiryoCode: Boolean = false
        private set

    /** Reads again whether miryo has a parental code. Blocking (a provider call): off the main thread. */
    fun refreshSuite() {
        usesMiryoCode = dev.miraiijo.miryotube.link.MiryoParental.hasPin(appContext) == true
    }

    val exists: Boolean get() = prefs.getString(KEY_PIN, null) != null || (prefs.getBoolean(KEY_SUITE, false) && usesMiryoCode)

    /** miryo tube's own code exists (not only miryo's). */
    val hasOwnPin: Boolean get() = prefs.getString(KEY_PIN, null) != null

    /** The kids profile created under miryo's code (no code of its own to choose). */
    fun createWithMiryoCode() = prefs.edit().putBoolean(KEY_SUITE, true).apply()

    var active: Boolean
        get() = prefs.getBoolean(KEY_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_ACTIVE, value).apply()

    /** Turned on because miryo's current profile is a kids one (off again when miryo's isn't). */
    var fromMiryo: Boolean
        get() = prefs.getBoolean(KEY_FROM_MIRYO, false)
        set(value) = prefs.edit().putBoolean(KEY_FROM_MIRYO, value).apply()

    /** The account to come back to when the kids profile is left (opaque id, null: none). */
    var previousAccount: String?
        get() = prefs.getString(KEY_PREVIOUS, null)
        set(value) = prefs.edit().putString(KEY_PREVIOUS, value).apply()

    val channels: List<KidChannel>
        get() = runCatching {
            val array = JSONArray(prefs.getString(KEY_CHANNELS, "[]"))
            (0 until array.length()).mapNotNull { i ->
                val o = array.optJSONObject(i) ?: return@mapNotNull null
                val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                KidChannel(id, o.optString("name").ifBlank { "Chaîne" }, o.optString("avatar").takeIf { it.startsWith("https://") })
            }
        }.getOrDefault(emptyList())

    fun isAllowed(channelId: String): Boolean = channels.any { it.id == channelId }

    fun allow(channel: KidChannel) {
        if (isAllowed(channel.id)) return
        save(channels + channel)
    }

    fun disallow(channelId: String) = save(channels.filterNot { it.id == channelId })

    private fun save(list: List<KidChannel>) {
        val array = JSONArray()
        list.forEach { array.put(JSONObject().put("id", it.id).put("name", it.name).put("avatar", it.avatar ?: "")) }
        prefs.edit().putString(KEY_CHANNELS, array.toString()).apply()
    }

    /** Sets (or replaces) the parental code: the profile exists from then on. Off the main thread. */
    fun setPin(pin: String) {
        prefs.edit().putString(KEY_PIN, PinHash.hash(pin)).putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).putLong(KEY_LOCKED_ELAPSED, 0L).apply()
    }

    /** Checks the parental code, counting wrong ones. Off the main thread. */
    fun verify(pin: String): PinResult {
        if (usesMiryoCode) {
            when (val answer = dev.miraiijo.miryotube.link.MiryoParental.check(appContext, pin)) {
                dev.miraiijo.miryotube.link.MiryoParental.Answer.Ok -> return PinResult.Ok
                dev.miraiijo.miryotube.link.MiryoParental.Answer.Wrong -> return PinResult.Wrong
                is dev.miraiijo.miryotube.link.MiryoParental.Answer.Locked -> return PinResult.Locked(answer.minutes)
                // Keystore busy on miryo's side: not counted, the same answer as a wrong try would be misleading.
                dev.miraiijo.miryotube.link.MiryoParental.Answer.Unavailable -> return PinResult.Unavailable
                null -> usesMiryoCode = false
            }
        }
        val now = System.currentTimeMillis()
        val since = android.os.SystemClock.elapsedRealtime()
        val lockedUntil = prefs.getLong(KEY_LOCKED_UNTIL, 0L)
        // A clock set back never extends the lock beyond LOCK_MS; one moved forward doesn't end it
        // either: the same lock is counted on the boot clock (until the box restarts).
        val wallLeft = (lockedUntil - now).takeIf { it in 1..LOCK_MS } ?: 0L
        val bootLeft = (prefs.getLong(KEY_LOCKED_ELAPSED, 0L) - since).takeIf { it in 1..LOCK_MS } ?: 0L
        if (maxOf(wallLeft, bootLeft) > 0) return PinResult.Locked(minutesLeft(maxOf(wallLeft, bootLeft)))
        val stored = prefs.getString(KEY_PIN, null) ?: return PinResult.Wrong
        if (PinHash.verify(pin, stored)) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, 0L).putLong(KEY_LOCKED_ELAPSED, 0L).apply()
            return PinResult.Ok
        }
        val fails = prefs.getInt(KEY_FAILS, 0) + 1
        if (fails >= MAX_TRIES) {
            prefs.edit().putInt(KEY_FAILS, 0).putLong(KEY_LOCKED_UNTIL, now + LOCK_MS).putLong(KEY_LOCKED_ELAPSED, since + LOCK_MS).apply()
            return PinResult.Locked(minutesLeft(LOCK_MS))
        }
        prefs.edit().putInt(KEY_FAILS, fails).apply()
        return PinResult.Wrong
    }

    /** Removes the kids profile (code, channels, state). */
    fun delete() = prefs.edit().clear().apply()

    private fun minutesLeft(ms: Long) = ((ms + 59_999) / 60_000).toInt().coerceAtLeast(1)

    private companion object {
        const val KEY_PIN = "pin"
        const val KEY_SUITE = "suite_code"
        const val KEY_ACTIVE = "active"
        const val KEY_FROM_MIRYO = "from_lumen" // stored key of older versions, never shown
        const val KEY_PREVIOUS = "previous_account"
        const val KEY_CHANNELS = "channels"
        const val KEY_FAILS = "fails"
        const val KEY_LOCKED_UNTIL = "locked_until"
        const val KEY_LOCKED_ELAPSED = "locked_elapsed"
        const val MAX_TRIES = 5
        const val LOCK_MS = 5 * 60_000L
    }
}
