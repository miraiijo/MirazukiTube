package dev.miraiijo.miryotube.link

import android.content.Context
import android.os.Binder
import dev.miraiijo.miryotube.BuildConfig
import dev.miraiijo.miryotube.update.Updater

/**
 * miryo-link v3: how the miryo apps find each other. Same file in miryo, miryo tube and miryo iptv
 * (only the package lines differ): fix it in the three.
 *
 * Each app works alone. A partner is used only when it is installed, when the provider belongs to
 * it, and when it is signed with the certificate this build expects. The certificates (SHA-256 of
 * the signing certificate, public, not a secret) come from gradle.properties
 * (miryo.cert.miryo / miryo.cert.miryotube / miryo.cert.miryoiptv), written by `mirazuki-keys init`.
 * Empty = linking off for that app (safe default: nothing is read, nothing is answered).
 *
 * Providers (read-only, every query checks the caller):
 *  - miryo      content://dev.miraiijo.miryo.profiles/profiles
 *               profile_id, name, color (ARGB long), avatar, kids ("1"/"0"), current ("1"/"0")
 *               content://dev.miraiijo.miryo.profiles/weather
 *               city, latitude, longitude (no row: no city set in miryo)
 *  - miryo tube  content://dev.miraiijo.miryotube.feed/…  (feed contract v2, see FeedProvider)
 *  - miryo iptv  content://dev.miraiijo.miryoiptv.rows/channels?profile=<profile_id>
 *               channel_id, name, number, logo_url (https or null), kind ("recent"/"favorite"),
 *               now_title, now_start_ms, now_end_ms — never a stream address, a host or an account
 * Each provider notifies its root URI when its data changes. Unknown columns are ignored and
 * missing ones read as empty, so a newer partner never breaks an older one.
 */
object MiryoLink {
    const val VERSION = 3

    // Android install ids of the three apps (applicationId): never shown, and kept as they were
    // first published, because Android treats another id as another app (no update, data lost).
    const val MIRYO = "dev.miraiijo.lumen"
    const val MIRYOTUBE = "dev.miraiijo.lumentube"
    const val MIRYOIPTV = "dev.miraiijo.lumeniptv"

    const val PROFILES_AUTHORITY = "dev.miraiijo.miryo.profiles"
    const val TUBE_AUTHORITY = "dev.miraiijo.miryotube.feed"
    const val IPTV_AUTHORITY = "dev.miraiijo.miryoiptv.rows"

    /** Opens a channel in miryo iptv: data miryoiptv://channel/<channel_id>, this extra = the miryo profile. */
    const val IPTV_SCHEME = "miryoiptv"
    const val EXTRA_PROFILE = "dev.miraiijo.link.extra.PROFILE_ID"

    /** Expected signing certificate of [pkg] (empty: linking with it is off). */
    fun expectedCert(pkg: String): String = when (pkg) {
        MIRYO -> BuildConfig.CERT_MIRYO
        MIRYOTUBE -> BuildConfig.CERT_MIRYOTUBE
        MIRYOIPTV -> BuildConfig.CERT_MIRYOIPTV
        else -> ""
    }.lowercase().replace(":", "")

    /** [pkg] is installed and signed with the expected certificate. */
    fun isTrusted(context: Context, pkg: String): Boolean {
        val expected = expectedCert(pkg)
        return expected.isNotEmpty() && expected in Updater.signers(context, pkg)
    }

    /** [authority] is served by [pkg] itself (another app declaring the same name is ignored). */
    fun ownsProvider(context: Context, authority: String, pkg: String): Boolean =
        runCatching { context.packageManager.resolveContentProvider(authority, 0)?.packageName == pkg }.getOrDefault(false)

    /** Partner ready to be read: installed, its own provider, trusted certificate. */
    fun partner(context: Context, pkg: String, authority: String): Boolean =
        ownsProvider(context, authority, pkg) && isTrusted(context, pkg)

    /** Inside a provider: the app calling is one of [allowed], with its expected certificate. */
    fun callerIsOneOf(context: Context, vararg allowed: String): Boolean {
        val uid = Binder.getCallingUid()
        val packages = context.packageManager.getPackagesForUid(uid).orEmpty()
        return packages.any { it in allowed && isTrusted(context, it) }
    }
}
