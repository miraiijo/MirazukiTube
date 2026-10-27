package dev.miraiijo.miryotube.link

import android.content.Context
import android.net.Uri
import android.os.Bundle

/**
 * Temps d'écran enfant, the suite's (miryo-link v3, ProfilesProvider /screen_time and
 * call("screen_time_add")): a kids profile's time per day and its bedtime are set in miryo; the
 * minutes watched here are added to miryo's count, so the time is the same in the 3 apps.
 * Without miryo, or with an older one: null (no limit here). Blocking: off the main thread.
 */
object MiryoScreenTime {
    /** [limitMin] 0 = no daily limit, [bedtimeMin] null = no bedtime; [locked]: no more screen today. */
    data class Status(val limitMin: Int, val usedMin: Int, val bedtimeMin: Int?, val locked: Boolean)

    private val ROOT: Uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/")

    fun status(context: Context, profileId: String): Status? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        val uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/screen_time").buildUpon().appendQueryParameter("profile", profileId).build()
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return@use null
                fun str(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
                Status(
                    limitMin = str("limit_min")?.toIntOrNull() ?: 0,
                    usedMin = str("used_min")?.toIntOrNull() ?: 0,
                    bedtimeMin = str("bedtime_min")?.toIntOrNull(),
                    locked = str("locked") == "1",
                )
            }
        }.getOrNull()
    }

    /** [minutes] watched on [profileId]: miryo counts them and answers the new status. */
    fun add(context: Context, profileId: String, minutes: Int = 1): Status? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        val bundle = runCatching {
            context.contentResolver.call(ROOT, "screen_time_add", profileId, Bundle().apply { putInt("minutes", minutes) })
        }.getOrNull() ?: return null
        return Status(
            limitMin = bundle.getInt("limit_min"),
            usedMin = bundle.getInt("used_min"),
            bedtimeMin = if (bundle.containsKey("bedtime_min")) bundle.getInt("bedtime_min") else null,
            locked = bundle.getString("locked") == "1",
        )
    }
}
