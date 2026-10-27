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
 * its « Sons de navigation » and its Animations (reduced_motion: Économes right now), its text size
 * (text_scale: « Taille de l'interface » Comme miryo) and its subtitles (sub_size, sub_color, sub_bg,
 * sub_lang: « Sous-titres réglés une fois » for the 3 apps).
 * Read again with miryo's profile (start-up, back to the front).
 */
object MiryoLook {
    /**
     * [dayStart] / [dayEnd]: Auto's day, in minutes of the local day. [navSounds]: miryo's « Sons de
     * navigation » (null: an older miryo that doesn't say). [textScale]: 1.0, 1.15 or 1.3. The
     * subtitles: [subSize] s/m/l/xl, [subColor] white/yellow, [subBg] none/shadow/band, [subLang] an
     * ISO language (null: none chosen). [dnd]: « Ne pas déranger » during a playback.
     */
    data class Look(
        val appearance: String,
        val dayStart: Int,
        val dayEnd: Int,
        val navSounds: Boolean? = null,
        val reducedMotion: Boolean? = null,
        val textScale: Float? = null,
        val subSize: String? = null,
        val subColor: String? = null,
        val subBg: String? = null,
        val subLang: String? = null,
        val dnd: Boolean? = null,
    ) {
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
            Look(
                appearance, start, end,
                navSounds = str("nav_sounds")?.let { it == "1" },
                reducedMotion = str("reduced_motion")?.let { it == "1" },
                textScale = str("text_scale")?.toFloatOrNull()?.takeIf { it in 0.5f..2f },
                subSize = str("sub_size")?.takeIf { it in SUB_SIZES },
                subColor = str("sub_color")?.takeIf { it in SUB_COLORS },
                subBg = str("sub_bg")?.takeIf { it in SUB_BACKGROUNDS },
                subLang = str("sub_lang")?.trim()?.lowercase()?.takeIf { it.length in 2..3 },
                dnd = str("dnd")?.let { it == "1" },
            )
        }
    }.getOrNull()

    val SUB_SIZES = listOf("s", "m", "l", "xl")
    val SUB_COLORS = listOf("white", "yellow")
    val SUB_BACKGROUNDS = listOf("none", "shadow", "band")

    private const val MINUTES_A_DAY = 24 * 60
    const val DAY_START = 7 * 60
    const val DAY_END = 20 * 60
}
