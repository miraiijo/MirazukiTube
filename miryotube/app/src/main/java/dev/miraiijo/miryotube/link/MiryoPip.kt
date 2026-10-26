package dev.miraiijo.miryotube.link

import android.content.Context
import android.net.Uri
import android.os.Binder
import android.os.Bundle

/**
 * Tells miryo (ProfilesProvider call("pip"), miryo-link v3) that the picture-in-picture window
 * opens or closes: back on the launcher, miryo then doesn't take the sound (its « Son des applis
 * quittées » would pause the small window). Said before the window opens, so it's known before
 * miryo comes up. The token lets miryo forget it if this process dies. Without miryo, or with an
 * older one: nothing (the window plays all the same, miryo just may pause it).
 */
object MiryoPip {
    private val ROOT: Uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/")
    private val token = Binder()

    fun tell(context: Context, on: Boolean) {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return
        runCatching { context.contentResolver.call(ROOT, "pip", if (on) "on" else "off", Bundle().apply { putBinder("token", token) }) }
    }
}
