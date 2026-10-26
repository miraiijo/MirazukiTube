package dev.miraiijo.miryotube.link

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime

/**
 * The look of miryo's current profile (miryo-link v3, ProfilesProvider /look): « Comme miryo »
 * follows its Jour / Nuit / Auto, Auto with the same day bounds (sunrise and sunset in miryo's city),
 * and its « Sons de navigation ».
 * Read again with miryo's profile (start-up, back to the front).
 */
object MiryoLook {
    /**
     * [dayStart] / [dayEnd]: Auto's day, in minutes of the local day. [navSounds]: miryo's « Sons de
     * navigation » (null: an older miryo that doesn't say).
     */
    data class Look(val appearance: String, val dayStart: Int, val dayEnd: Int, val navSounds: Boolean? = null) {
        fun isLight(now: LocalDateTime): Boolean = when (appearance) {
            "day" -> true
            "night" -> false
            else -> (now.hour * 60 + now.minute) in dayStart until dayEnd
        }
    }

    /** null: no miryo (or an older one). Compose state: the appearance follows it at once. */
    var current: Look? by mutableStateOf(null)
        private set

    /** Blocking (a provider query): off the main thread. */
    fun refresh(context: Context) {
        current = if (MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) read(context) else null
    }

    private fun read(context: Context): Look? = runCatching {
        val uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/look")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun str(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            val appearance = str("appearance")?.takeIf { it in setOf("day", "night", "auto") } ?: return@use null
            val start = str("day_start")?.toIntOrNull()?.coerceIn(0, MINUTES_A_DAY) ?: DAY_START
            val end = str("day_end")?.toIntOrNull()?.coerceIn(0, MINUTES_A_DAY) ?: DAY_END
            Look(appearance, start, end, navSounds = str("nav_sounds")?.let { it == "1" })
        }
    }.getOrNull()

    private const val MINUTES_A_DAY = 24 * 60
    const val DAY_START = 7 * 60
    const val DAY_END = 20 * 60
}
