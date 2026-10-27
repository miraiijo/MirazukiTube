package dev.miraiijo.miryotube.system

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Réglages › Affichage › Animations, same rule as miryo. « Économes »: what only decorates goes
 * (pages switch without a cross-fade, no shine on the cards). « Complètes »: everything. « Auto »: chosen from the box
 * itself, measured once per process. « Comme miryo » (default): miryo's choice, resolved by miryo.
 */
object LowEnd {
    const val MIRYO = "miryo"
    const val AUTO = "auto"
    const val ECO = "eco"
    const val FULL = "full"
    val CHOICES = setOf(MIRYO, AUTO, ECO, FULL)

    /** The user's choice (Compose state: the screens follow it at once). Set from the settings (PlayerPrefs). */
    var choice: String by mutableStateOf(MIRYO)

    @Volatile
    private var detected: Boolean? = null

    /** True: Économes now. Without a context (no measure yet), Auto counts as Complètes. */
    fun reduced(context: Context? = null): Boolean = when (choice) {
        ECO -> true
        FULL -> false
        MIRYO -> dev.miraiijo.miryotube.link.MiryoLook.current?.reducedMotion ?: auto(context)
        else -> auto(context)
    }

    private fun auto(context: Context?): Boolean = detected ?: context?.let(::detectedLowEnd) ?: false

    /** What « Auto » picks on this box (true: Économes). */
    fun detectedLowEnd(context: Context): Boolean = detected ?: run {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val cores = littleCoresOnly()
        val low = am == null || am.isLowRamDevice || info.totalMem in 1 until MIN_RAM_BYTES ||
            // Only small cores (Cortex-A53 / A55 / A35: Freebox POP, most sticks and cheap boxes)
            // means a modest GPU too; big cores (Shield's A57, A72, A76…) keep everything.
            cores == true ||
            (cores == null && info.totalMem in 1 until LOW_END_RAM_BYTES)
        detected = low
        low
    }

    /** true: every core is a small one; false: at least one big core; null: unknown. */
    private fun littleCoresOnly(): Boolean? = runCatching {
        val parts = java.io.File("/proc/cpuinfo").readLines()
            .filter { it.startsWith("CPU part") }
            .map { it.substringAfter(':').trim().lowercase() }
            .toSet()
        if (parts.isEmpty()) null else parts.all { it in LITTLE_CORES }
    }.getOrNull()

    /** ARM Cortex-A53, A55, A35, A7. */
    private val LITTLE_CORES = setOf("0xd03", "0xd05", "0xd04", "0xc07")
    private const val MIN_RAM_BYTES = 3L * 512 * 1024 * 1024
    private const val LOW_END_RAM_BYTES = 3L * 1024 * 1024 * 1024
}
