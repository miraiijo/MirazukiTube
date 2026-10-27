package dev.miraiijo.miryotube.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A channel by id and name: hidden (« Ne plus montrer cette chaîne »), or being put in a folder. */
@androidx.compose.runtime.Immutable
data class ChannelRef(val id: String, val name: String)

/** A folder of subscriptions (« Cuisine », « Jeux »): a name and its channels' ids. */
@androidx.compose.runtime.Immutable
data class Folder(val name: String, val channels: Set<String>)

/**
 * What each profile arranges on this box only, never sent to YouTube: hidden channels, folders of
 * subscriptions and when each subscription was last looked at (Abonnements' pastilles). One JSON
 * object per profile ([profile]: the account's opaque id, or [GUEST]).
 */
class Curation(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("curation", Context.MODE_PRIVATE)

    private fun read(profile: String): JSONObject =
        runCatching { JSONObject(prefs.getString(profile, null) ?: "{}") }.getOrDefault(JSONObject())

    private fun write(profile: String, data: JSONObject) = prefs.edit().putString(profile, data.toString()).apply()

    fun hidden(profile: String): List<ChannelRef> {
        val array = read(profile).optJSONArray(KEY_HIDDEN) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            ChannelRef(id, o.optString("name").ifBlank { "Chaîne" })
        }
    }

    fun hide(profile: String, channel: ChannelRef) {
        val list = hidden(profile).filterNot { it.id == channel.id } + channel
        val data = read(profile)
        data.put(KEY_HIDDEN, JSONArray(list.takeLast(MAX_HIDDEN).map { JSONObject().put("id", it.id).put("name", it.name.take(NAME_MAX)) }))
        write(profile, data)
    }

    fun unhide(profile: String, channelId: String) {
        val data = read(profile)
        data.put(KEY_HIDDEN, JSONArray(hidden(profile).filterNot { it.id == channelId }.map { JSONObject().put("id", it.id).put("name", it.name) }))
        write(profile, data)
    }

    fun folders(profile: String): List<Folder> {
        val array = read(profile).optJSONArray(KEY_FOLDERS) ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val ids = o.optJSONArray("channels")
            Folder(name, (0 until (ids?.length() ?: 0)).mapNotNull { ids?.optString(it)?.takeIf { s -> s.isNotBlank() } }.toSet())
        }
    }

    private fun saveFolders(profile: String, folders: List<Folder>) {
        val data = read(profile)
        data.put(KEY_FOLDERS, JSONArray(folders.map { JSONObject().put("name", it.name).put("channels", JSONArray(it.channels.toList())) }))
        write(profile, data)
    }

    /**
     * Puts [channelId] in the folder [name] (created when new), out of every other one: a channel
     * is in one folder at most. [name] null: in none.
     */
    fun file(profile: String, channelId: String, name: String?) {
        val clean = name?.trim()?.take(NAME_MAX)?.takeIf { it.isNotEmpty() }
        var folders = folders(profile).map { it.copy(channels = it.channels - channelId) }
        if (clean != null) {
            folders = if (folders.any { it.name.equals(clean, ignoreCase = true) }) {
                folders.map { if (it.name.equals(clean, ignoreCase = true)) it.copy(channels = it.channels + channelId) else it }
            } else {
                (folders + Folder(clean, setOf(channelId))).takeLast(MAX_FOLDERS)
            }
        }
        saveFolders(profile, folders)
    }

    /** Removes a folder (its channels stay subscribed, just in no folder). */
    fun deleteFolder(profile: String, name: String) = saveFolders(profile, folders(profile).filterNot { it.name == name })

    /**
     * When each subscription was last looked at (ms), by channel id; [since] for the ones never
     * looked at: the first visit of Abonnements, a day back (so its first pastilles mean something).
     */
    fun visits(profile: String): Map<String, Long> {
        val o = read(profile).optJSONObject(KEY_VISITS) ?: return emptyMap()
        return o.keys().asSequence().associateWith { o.optLong(it) }
    }

    fun since(profile: String): Long {
        val data = read(profile)
        if (data.has(KEY_SINCE)) return data.optLong(KEY_SINCE)
        val first = System.currentTimeMillis() - DAY_MS
        write(profile, data.put(KEY_SINCE, first))
        return first
    }

    fun visited(profile: String, channelId: String, at: Long = System.currentTimeMillis()) {
        val data = read(profile)
        val visits = data.optJSONObject(KEY_VISITS) ?: JSONObject()
        visits.put(channelId, at)
        // The oldest go first past the cap (channels unsubscribed long ago).
        if (visits.length() > MAX_VISITS) {
            visits.keys().asSequence().toList().sortedBy { visits.optLong(it) }.take(visits.length() - MAX_VISITS).forEach { visits.remove(it) }
        }
        write(profile, data.put(KEY_VISITS, visits))
    }

    companion object {
        /** The profile without an account (Invité). */
        const val GUEST = "guest"
        private const val KEY_HIDDEN = "hidden"
        private const val KEY_FOLDERS = "folders"
        private const val KEY_VISITS = "visits"
        private const val KEY_SINCE = "since"
        private const val MAX_HIDDEN = 300
        private const val MAX_FOLDERS = 20
        private const val MAX_VISITS = 500
        const val NAME_MAX = 40
        private const val DAY_MS = 24 * 60 * 60 * 1000L
    }
}
