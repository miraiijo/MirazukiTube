package dev.miraiijo.miryotube.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue

/**
 * « Minuterie » (sleep timer): the playback stops on its own after the time chosen, or at the end
 * of the video. Process-wide, so it goes on from one video to the next. A minute before, the
 * player asks « Toujours là ? » (any answer adds [EXTRA_MINUTES]); without an answer it stops and
 * the TV's own screen saver and standby take over.
 */
object SleepTimer {
    /** « À la fin de la vidéo ». */
    const val AT_END = -1
    const val EXTRA_MINUTES = 30
    /** « Toujours là ? » shows this long before the end. */
    const val ASK_MS = 60_000L

    /** The choice (minutes, [AT_END], 0 = off) and when it ends (elapsedRealtime, 0: no time set). */
    var minutes by mutableIntStateOf(0)
        private set
    var endsAt by mutableLongStateOf(0L)
        private set

    val active: Boolean get() = minutes != 0

    fun set(choice: Int, now: Long = android.os.SystemClock.elapsedRealtime()) {
        minutes = choice
        endsAt = if (choice > 0) now + choice * 60_000L else 0L
    }

    /** « Je suis là »: 30 minutes more from now. */
    fun extend(now: Long = android.os.SystemClock.elapsedRealtime()) = set(EXTRA_MINUTES, now)

    fun cancel() = set(0)

    /** Time left (ms), null when no time is set (off, or at the end of the video). */
    fun left(now: Long = android.os.SystemClock.elapsedRealtime()): Long? = if (endsAt > 0) (endsAt - now).coerceAtLeast(0L) else null

    /** As the player's menu shows it: « Désactivée », « À la fin de la vidéo », « 29 min ». */
    fun label(now: Long = android.os.SystemClock.elapsedRealtime()): String = when {
        minutes == AT_END -> "À la fin de la vidéo"
        minutes == 0 -> "Désactivée"
        else -> "${((left(now) ?: 0L) + 59_999L) / 60_000L} min"
    }
}
