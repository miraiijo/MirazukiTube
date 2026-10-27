package dev.miraiijo.miryotube.system

import android.app.ActivityManager
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit

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

    /** What « Auto » picks on this box (true: Économes): the fluidity test when it was run, else the processor and memory. */
    fun detectedLowEnd(context: Context): Boolean {
        // Read for Compose: a new test result recomposes the screens that asked.
        revision
        return detected ?: report(context).low.also { detected = it }
    }

    /** What is known of this box, for « Auto » and to explain its choice. Same rule as miryo. */
    data class Report(val low: Boolean, val ramGb: Double?, val littleCores: Boolean?, val cores: Int, val latePercent: Int?)

    fun report(context: Context): Report {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val cores = littleCoresOnly()
        val late = measured(context)
        // Too little memory: Économes whatever the test says.
        val memoryLow = am == null || am.isLowRamDevice || info.totalMem in 1 until MIN_RAM_BYTES
        // Only small cores (Cortex-A53 / A55 / A35: Freebox POP, most sticks and cheap boxes) means a
        // modest GPU too: Économes whatever the test says (the POP passed the old 3 s test, yet the real
        // screens dropped up to 9 frames in 10). Otherwise the test decides when it was run; big cores
        // (Shield's A57, A72, A76…) keep everything; processor not readable: memory decides.
        val low = memoryLow || cores == true || when {
            late != null -> late >= LATE_LIMIT
            cores != null -> false
            else -> info.totalMem in 1 until LOW_END_RAM_BYTES
        }
        val ram = info.totalMem.takeIf { it > 0 }?.let { it / (1024.0 * 1024 * 1024) }
        return Report(low, ram, cores, Runtime.getRuntime().availableProcessors(), late)
    }

    /** The report in words: « processeur d'entrée de gamme (4 cœurs), mémoire 1,9 Go, … ». */
    fun describe(report: Report): String = listOfNotNull(
        when (report.littleCores) {
            true -> "processeur d'entrée de gamme (${report.cores} cœurs)"
            false -> "processeur puissant (${report.cores} cœurs)"
            null -> null
        },
        report.ramGb?.let { "mémoire " + "%.1f".format(java.util.Locale.FRANCE, it) + " Go" },
        when {
            report.latePercent == null -> "test de fluidité pas encore fait"
            report.latePercent < LATE_LIMIT -> "test de fluidité réussi (${report.latePercent} % d'images en retard)"
            else -> "test de fluidité : ${report.latePercent} % d'images en retard"
        },
    ).joinToString(", ")

    /** Share of late frames (%) found by the fluidity test on this box; null: never run. */
    fun measured(context: Context): Int? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LATE, -1).takeIf { it >= 0 }

    /** Keeps the fluidity test's result for this box (« Auto » follows it from now on). */
    fun saveMeasure(context: Context, latePercent: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt(KEY_LATE, latePercent.coerceIn(0, 100)) }
        detected = null
        revision++
    }

    private var revision by mutableIntStateOf(0)

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
    /** From this share of late frames in the fluidity test, the box is modest: Économes. */
    const val LATE_LIMIT = 10
    private const val PREFS = "box_perf"
    /** v2: the 5 s test of real-like rows (the old 3 s test let the POP pass): old results are not kept. */
    private const val KEY_LATE = "late_frames_v2"
    private const val LOW_END_RAM_BYTES = 3L * 1024 * 1024 * 1024
}
