package dev.miraiijo.miryotube.kids

import android.content.Context
import android.net.Uri
import dev.miraiijo.miryotube.link.MiryoLink

/**
 * miryo's current profile (miryo-link v3, content://dev.miraiijo.miryo.profiles/profiles): a kids
 * profile there opens miryo tube in its kids profile, with no way out from here (the profile is
 * changed in miryo, behind miryo's own parental code).
 */
object MiryoProfile {
    private val URI: Uri = Uri.parse("content://${MiryoLink.PROFILES_AUTHORITY}/profiles")

    /** True: miryo's current profile is a kids one; false: an adult one; null: no trusted miryo. Blocking. */
    fun currentIsKids(context: Context): Boolean? {
        if (!MiryoLink.partner(context, MiryoLink.MIRYO, MiryoLink.PROFILES_AUTHORITY)) return null
        return runCatching {
            context.contentResolver.query(URI, arrayOf("kids", "current"), null, null, null)?.use { cursor ->
                val kids = cursor.getColumnIndex("kids")
                val current = cursor.getColumnIndex("current")
                if (kids < 0 || current < 0) return@use null
                var found: Boolean? = null
                while (cursor.moveToNext()) {
                    if (cursor.getString(current) == "1") {
                        found = cursor.getString(kids) == "1"
                        break
                    }
                }
                found
            }
        }.getOrNull()
    }
}
