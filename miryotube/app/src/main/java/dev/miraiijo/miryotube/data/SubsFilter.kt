package dev.miraiijo.miryotube.data

/**
 * Abonnements' filters, kept on the box: « Que du nouveau » (the videos already watched hidden),
 * a duration, a folder of channels, and one channel (OK on its pastille). Pure (unit-tested).
 */
@androidx.compose.runtime.Immutable
data class SubsFilter(
    val newOnly: Boolean = false,
    val duration: Int = DURATION_ALL,
    val folder: String? = null,
    /** One channel's videos only (its pastille), with its name for the row's title. */
    val channel: String? = null,
    val channelName: String? = null,
) {
    val active: Boolean get() = newOnly || duration != DURATION_ALL || folder != null || channel != null

    /** Whether a video stays on the page. [folderChannels]: the channels of [folder] (null: no folder chosen). */
    fun keeps(channelId: String?, durationMs: Long, watchedPercent: Int, live: Boolean, folderChannels: Set<String>?): Boolean {
        if (channel != null && channelId != channel) return false
        if (folderChannels != null && (channelId == null || channelId !in folderChannels)) return false
        if (newOnly && watchedPercent >= WATCHED_PERCENT) return false
        return durationKeeps(duration, durationMs, live)
    }

    companion object {
        const val DURATION_ALL = 0
        const val DURATION_SHORT = 1
        const val DURATION_MEDIUM = 2
        const val DURATION_LONG = 3

        /** The duration pill's choices, in the order OK goes through them. */
        val DURATIONS = listOf(DURATION_ALL to "Toutes", DURATION_SHORT to "Moins de 10 min", DURATION_MEDIUM to "10 à 20 min", DURATION_LONG to "Plus de 20 min")

        /** Watched to the end (YouTube's full red bar): hidden by « Que du nouveau ». */
        const val WATCHED_PERCENT = 95

        private const val TEN_MIN_MS = 10 * 60 * 1000L
        private const val TWENTY_MIN_MS = 20 * 60 * 1000L

        /** A live (or a video whose length isn't known) only stays when every duration is shown. */
        fun durationKeeps(duration: Int, durationMs: Long, live: Boolean): Boolean = when (duration) {
            DURATION_SHORT -> !live && durationMs in 1 until TEN_MIN_MS
            DURATION_MEDIUM -> !live && durationMs in TEN_MIN_MS..TWENTY_MIN_MS
            DURATION_LONG -> !live && durationMs > TWENTY_MIN_MS
            else -> true
        }

        /** New videos of a channel since it was last looked at, from their publication times (ms, null: unknown). */
        fun newCount(published: List<Long?>, lastVisit: Long): Int = published.count { it != null && it > lastVisit }
    }
}
