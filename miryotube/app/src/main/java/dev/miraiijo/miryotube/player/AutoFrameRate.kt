package dev.miraiijo.miryotube.player

import android.app.Activity
import android.view.Display
import kotlin.math.abs

/**
 * « Fréquence d'affichage automatique »: the TV switches to a refresh rate that fits the video
 * (24 / 23.976 fps → 24 or 23.976 Hz, 25 / 50 → 50 Hz, 30 / 60 / 29.97 / 59.94 → 60 or 59.94 Hz),
 * at its current resolution, through the window's preferredDisplayModeId (Android 10 box).
 * One instance for the whole playing session (held above the per-video player screens): the
 * next video at an equivalent rate never switches the TV again, and [reset] (no video anymore,
 * or the option turned off) brings the TV's own mode back. Every call is guarded: a TV that lists
 * a single mode, or refuses, simply stays as it is.
 */
class AutoFrameRate(private val activity: Activity) {
    /** The window's preferredDisplayModeId before the first switch (0 = the system's choice); null = none made. */
    private var originalModeId: Int? = null

    /** The video's frame rate is known: switches when the display isn't already at a fitting rate. */
    fun apply(fps: Float) {
        val targets = targetRates(fps)
        if (targets.isEmpty()) return
        runCatching {
            val display = display() ?: return
            val current = display.mode
            val window = activity.window
            val attributes = window.attributes
            val asked = attributes.preferredDisplayModeId
            // Already asked for (or at) a fitting mode: equivalent rates never switch the TV.
            val askedMode = display.supportedModes.firstOrNull { it.modeId == asked }
            val effective = if (asked != 0 && askedMode != null) askedMode else current
            if (targets.any { matches(effective.refreshRate, it) }) return
            val sameSize = display.supportedModes.filter { it.physicalWidth == effective.physicalWidth && it.physicalHeight == effective.physicalHeight }
            val mode = targets.firstNotNullOfOrNull { rate -> sameSize.firstOrNull { matches(it.refreshRate, rate) } } ?: return
            if (originalModeId == null) originalModeId = asked
            attributes.preferredDisplayModeId = mode.modeId
            window.attributes = attributes
        }.onFailure { android.util.Log.w(TAG, "display mode unchanged: ${it.javaClass.simpleName}") }
    }

    /** Back to the TV's own mode (the player is left, or the option turned off). */
    fun reset() {
        val original = originalModeId ?: return
        originalModeId = null
        runCatching {
            val window = activity.window
            val attributes = window.attributes
            attributes.preferredDisplayModeId = original
            window.attributes = attributes
        }.onFailure { android.util.Log.w(TAG, "display mode not restored: ${it.javaClass.simpleName}") }
    }

    @Suppress("DEPRECATION")
    private fun display(): Display? = runCatching<Display?> { activity.windowManager.defaultDisplay }.getOrNull()

    companion object {
        private const val TAG = "MiryoTube.AFR"

        /**
         * Display rates that fit [fps], preferred first; empty = unknown or unusual rate (no switch).
         * NTSC rates (23.976, 29.97, 59.94) prefer their exact mode, then the round one.
         */
        fun targetRates(fps: Float): List<Float> = when {
            fps.isNaN() || fps <= 0f -> emptyList()
            fps in 23.5f..24.5f -> if (fps < 23.99f) listOf(23.976f, 24f) else listOf(24f, 23.976f)
            fps in 24.5f..25.5f || fps in 49f..51f -> listOf(50f)
            fps in 29.5f..30.5f -> if (fps < 29.985f) listOf(59.94f, 60f) else listOf(60f, 59.94f)
            fps in 59f..61f -> if (fps < 59.97f) listOf(59.94f, 60f) else listOf(60f, 59.94f)
            else -> emptyList()
        }

        /** Same rate within 0.02 Hz: 23.976 and 24, or 59.94 and 60, stay apart. */
        private fun matches(refreshRate: Float, rate: Float) = abs(refreshRate - rate) < 0.02f
    }
}
