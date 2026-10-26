package dev.miraiijo.lumentube.link

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.LocalDateTime

/**
 * The look of miryo's current profile (mirazuki-link v3, ProfilesProvider /look): « Comme miryo »
 * follows its Jour / Nuit / Auto, Auto with the same day bounds (sunrise and sunset in miryo's city).
 * Read again with miryo's profile (start-up, back to the front).
 */
object MirazukiLook {
    /** [dayStart] / [dayEnd]: Auto's day, in minutes of the local day. */
    data class Look(val appearance: String, val dayStart: Int, val dayEnd: Int) {
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
        current = if (MirazukiLink.partner(context, MirazukiLink.MIRAZUKI, MirazukiLink.PROFILES_AUTHORITY)) read(context) else null
    }

    private fun read(context: Context): Look? = runCatching {
        val uri = Uri.parse("content://${MirazukiLink.PROFILES_AUTHORITY}/look")
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null
            fun str(name: String) = c.getColumnIndex(name).takeIf { it >= 0 && !c.isNull(it) }?.let(c::getString)
            val appearance = str("appearance")?.takeIf { it in setOf("day", "night", "auto") } ?: return@use null
            val start = str("day_start")?.toIntOrNull()?.coerceIn(0, MINUTES_A_DAY) ?: DAY_START
            val end = str("day_end")?.toIntOrNull()?.coerceIn(0, MINUTES_A_DAY) ?: DAY_END
            Look(appearance, start, end)
        }
    }.getOrNull()

    private const val MINUTES_A_DAY = 24 * 60
    const val DAY_START = 7 * 60
    const val DAY_END = 20 * 60
}
