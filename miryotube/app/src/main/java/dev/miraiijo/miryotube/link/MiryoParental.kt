package dev.miraiijo.miryotube.link

import android.content.Context
import android.net.Uri

/**
 * miryo's parental code, the suite's (miryo-link v3, ProfilesProvider call()): when miryo is
 * installed, trusted and has a code, that code and its single limiter protect miryo tube too; the
 * app's own code is only used without miryo. Blocking (miryo hashes the code): off the main thread.
 */
object MiryoParental {
    sealed interface Answer {
        data object Ok : Answer
        data object Wrong : Answer
        data class Locked(val minutes: Int) : Answer
        /** miryo's Keystore didn't answer this time: nothing counted, try again. */
        data object Unavailable : Answer
    }

    private val ROOT: Uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/")

    /** True: miryo has a parental code (it is the one to use). False / null: no code, no miryo, or an older miryo. */
    fun hasPin(context: Context): Boolean? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        return runCatching { context.contentResolver.call(ROOT, "parental_status", null, null)?.getBoolean("has_pin") }.getOrNull()
    }

    /** miryo's verdict on [pin]; null when miryo can't answer (then the app's own code applies). */
    fun check(context: Context, pin: String): Answer? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        val bundle = runCatching { context.contentResolver.call(ROOT, "check_parental_pin", pin, null) }.getOrNull() ?: return null
        return when (bundle.getString("result")) {
            "ok" -> Answer.Ok
            "wrong" -> Answer.Wrong
            "locked" -> Answer.Locked(bundle.getInt("minutes").coerceAtLeast(1))
            "unavailable" -> Answer.Unavailable
            else -> null
        }
    }
}
