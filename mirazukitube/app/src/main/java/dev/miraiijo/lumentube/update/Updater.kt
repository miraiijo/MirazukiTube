package dev.miraiijo.lumentube.update

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Self-update of the miryo apps. Same file in miryo, miryo tube and miryo iptv (only the package line
 * differs): fix it in the three.
 *
 * Channel: the public repo miraiijo/miryo-releases (formerly mirazuki-releases, then lumen-releases), release « channel », one `<app>.json` per app,
 * written by mirazuki-ship on the Mac:
 *   {"format":1,"package":"dev.miraiijo.<app>","stable":{…}|null,"beta":{…}|null}
 *   entry: {"versionCode","versionName","apk","sha256","size","notes","date"}
 * Stable versions only by default; with « Recevoir les versions bêta », the newest of the two.
 * miryo also installs and updates its companions with it ([checkFor] / [download] of another package).
 * No account and no token: public files over HTTPS. Before the system installer gets the APK, its
 * size, SHA-256, package, version and signing certificate are checked (the installer itself also
 * refuses an APK signed with another key). Blocking calls: IO dispatcher only.
 */
object Updater {
    private const val RELEASES = "https://github.com/miraiijo/miryo-releases/releases/download/"
    private const val CHANNEL = "${RELEASES}channel/"
    /** The channel's addresses before the renames (GitHub redirects them): versions published for older installs still point there. */
    private val FORMER_RELEASES = listOf(
        "https://github.com/miraiijo/mirazuki-releases/releases/download/",
        "https://github.com/miraiijo/lumen-releases/releases/download/",
    )
    private const val TIMEOUT_MS = 15_000
    private const val MAX_JSON = 64 * 1024
    private const val MAX_APK = 300L * 1024 * 1024
    private const val MAX_REDIRECTS = 5
    private const val PREFS = "updates"
    private const val KEY_BETA = "beta"
    private const val KEY_LAST_CHECK = "last_check"
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    /** One version offered by the channel. [beta]: published as a beta (may crash or have bugs). */
    data class UpdateInfo(
        /** The app this APK is (this app, or a companion miryo installs). */
        val packageName: String,
        val versionCode: Long,
        val versionName: String,
        val beta: Boolean,
        val apkUrl: String,
        val sha256: String,
        val size: Long,
        val notes: String,
    )

    fun installedVersionCode(context: Context): Long = packageInfo(context).longVersionCode

    fun installedVersionName(context: Context): String = packageInfo(context).versionName.orEmpty()

    /** Betas are named « … bêta N »; a stable version never has the word. */
    fun isBeta(versionName: String): Boolean = versionName.contains("bêta", ignoreCase = true) || versionName.contains("beta", ignoreCase = true)

    /** « Recevoir les versions bêta »: on by default when the installed version is a beta. */
    fun wantsBeta(context: Context): Boolean = prefs(context).getBoolean(KEY_BETA, isBeta(installedVersionName(context)))

    fun setWantsBeta(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_BETA, value).apply()
    }

    /** True at most once a day (the silent check at start-up). [markChecked] once the check succeeded. */
    fun dailyCheckDue(context: Context): Boolean {
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        val now = System.currentTimeMillis()
        // A clock set back (last check "in the future") must not silence the checks for days.
        return last > now || now - last >= DAY_MS
    }

    /** Records a successful check (a failed one, offline at start-up, is retried at the next start). */
    fun markChecked(context: Context) {
        prefs(context).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
    }

    /**
     * One download at a time in this process (miryo itself and its companions): two downloads at
     * once would fight for the bandwidth and the cache. [download] refuses while this is held.
     */
    private val busy = java.util.concurrent.atomic.AtomicBoolean(false)

    /** True while an APK is being downloaded and checked. */
    fun isDownloading(): Boolean = busy.get()

    /**
     * The version to install for [app] (« mirazuki », « mirazukitube », « mirazukiiptv »), or null when up to
     * date or when nothing is published yet. Never a lower version (Android refuses a downgrade):
     * leaving the betas keeps the installed one until a newer stable version.
     */
    fun check(context: Context, app: String): UpdateInfo? = checkFor(context, app, context.packageName, wantsBeta(context))

    /**
     * Same for any miryo app [pkg] (« mirazukitube » / dev.miraiijo.lumentube…): the version to install
     * when it isn't installed, or one newer than the installed one; null otherwise or when nothing
     * is published.
     */
    fun checkFor(context: Context, app: String, pkg: String, beta: Boolean): UpdateInfo? {
        require(APP.matches(app)) { "Appli inconnue" }
        val text = try {
            fetchText(CHANNEL + "$app.json")
        } catch (_: NotPublished) {
            return null
        }
        val json = JSONObject(text)
        if (json.optInt("format") != 1) throw IOException("Format de mise à jour inconnu : installe la dernière version à la main")
        if (json.optString("package") != pkg) throw IOException("Ce canal est celui d'une autre appli")
        val stableEntry = json.optJSONObject("stable")?.let { entry(it, pkg, beta = false) }
        val betaEntry = json.optJSONObject("beta")?.let { entry(it, pkg, beta = true) }
        val pick = if (beta) listOfNotNull(stableEntry, betaEntry).maxByOrNull { it.versionCode } else stableEntry
        return pick?.takeIf { it.versionCode > (installedCode(context, pkg) ?: 0L) }
    }

    /** Installed version of [pkg] (code, name), or null when it isn't installed. */
    fun installed(context: Context, pkg: String): Pair<Long, String>? = runCatching {
        context.packageManager.getPackageInfo(pkg, 0).let { it.longVersionCode to it.versionName.orEmpty() }
    }.getOrNull()

    private fun installedCode(context: Context, pkg: String): Long? = installed(context, pkg)?.first

    /**
     * Downloads and checks the APK into the cache; [onProgress] gets 0..1. [expectedCert]: SHA-256
     * of the certificate a first install must carry (an installed app is compared with itself).
     */
    fun download(context: Context, update: UpdateInfo, expectedCert: String = "", onProgress: (Float) -> Unit = {}): File {
        if (!busy.compareAndSet(false, true)) throw IOException("Un autre téléchargement est en cours : réessaie dans un instant")
        try {
            return downloadLocked(context, update, expectedCert, onProgress)
        } finally {
            busy.set(false)
        }
    }

    private fun downloadLocked(context: Context, update: UpdateInfo, expectedCert: String, onProgress: (Float) -> Unit): File {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        // One file per app: the installer may still be reading another app's APK.
        File(dir, "update.apk").delete()
        val target = File(dir, "${update.packageName}.apk")
        target.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        val connection = open(update.apkUrl)
        try {
            var total = 0L
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > update.size) throw IOException("Fichier plus gros qu'annoncé")
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                        onProgress(total.toFloat() / update.size)
                    }
                }
            }
            if (total != update.size) throw IOException("Téléchargement incomplet")
        } catch (e: IOException) {
            target.delete()
            throw e
        } finally {
            connection.disconnect()
        }
        if (hex(digest.digest()) != update.sha256) fail(target, "Fichier altéré (empreinte différente) : rien n'est installé")
        verifyArchive(context, target, update, expectedCert)
        return target
    }

    /** Android must allow this app to install apps (« Sources inconnues » for this app). */
    fun canInstall(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    fun openInstallPermissionSettings(context: Context): Boolean {
        val candidates = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (_: ActivityNotFoundException) {
            } catch (_: SecurityException) {
            }
        }
        return false
    }

    /** Hands the APK to the system installer (the user confirms on screen). */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        @Suppress("DEPRECATION")
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // ---- Checks ------------------------------------------------------------------------------

    private fun entry(o: JSONObject, pkg: String, beta: Boolean): UpdateInfo {
        val apk = o.optString("apk")
        // Only an APK of a release of the same repo (never the channel itself, never another host).
        val base = (listOf(RELEASES) + FORMER_RELEASES).firstOrNull { apk.startsWith(it) }
        if (base == null || apk.startsWith("${base}channel/") || ".." in apk) throw IOException("Adresse de l'APK inattendue")
        val sha = o.optString("sha256").lowercase()
        if (!SHA256.matches(sha)) throw IOException("Empreinte de l'APK invalide")
        val size = o.optLong("size")
        if (size <= 0 || size > MAX_APK) throw IOException("Taille de l'APK invalide")
        val code = o.optLong("versionCode")
        if (code <= 0) throw IOException("Numéro de version invalide")
        return UpdateInfo(pkg, code, o.optString("versionName").take(40), beta, apk, sha, size, o.optString("notes").take(2000))
    }

    /**
     * The APK is the announced app at the announced version, signed like the installed one (or,
     * for a first install, with [expectedCert] when this build knows it).
     */
    private fun verifyArchive(context: Context, apk: File, update: UpdateInfo, expectedCert: String) {
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val info = pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNING_CERTIFICATES) ?: fail(apk, "APK illisible")
        if (info.packageName != update.packageName) fail(apk, "APK d'une autre appli : refusé")
        if (info.longVersionCode != update.versionCode) fail(apk, "Version différente de celle annoncée : refusé")
        val incoming = info.signingInfo?.apkContentsSigners?.map { hex(sha256(it.toByteArray())) }?.toSet()
        val installed = signers(context, update.packageName)
        // Some Android builds can't read an archive's certificates: an update of an installed app is
        // then left to the system installer (it refuses another key); a first install is refused.
        if (incoming.isNullOrEmpty()) {
            if (installed.isEmpty()) fail(apk, "Signature de l'APK illisible : refusé")
            return
        }
        val expected = expectedCert.lowercase().replace(":", "")
        when {
            installed.isNotEmpty() -> if (incoming != installed) fail(apk, "APK signé par une autre clé : refusé")
            expected.isNotEmpty() -> if (expected !in incoming) fail(apk, "APK signé par une autre clé : refusé")
        }
    }

    /** SHA-256 (lowercase hex) of the signing certificates of [pkg]; empty if not installed. */
    fun signers(context: Context, pkg: String): Set<String> = runCatching {
        context.packageManager.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            .signingInfo?.apkContentsSigners?.map { hex(sha256(it.toByteArray())) }?.toSet().orEmpty()
    }.getOrDefault(emptySet())

    private fun fail(apk: File, message: String): Nothing {
        apk.delete()
        throw IOException(message)
    }

    // ---- HTTP --------------------------------------------------------------------------------

    private class NotPublished : IOException("Rien de publié")

    private fun fetchText(url: String): String {
        val connection = open(url)
        try {
            val bytes = connection.inputStream.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_JSON) throw IOException("Réponse trop longue")
                }
                out.toByteArray()
            }
            return String(bytes, Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    /** HTTPS only, at every redirect (GitHub sends the files from its storage host). */
    private fun open(start: String): HttpURLConnection {
        var url = start
        repeat(MAX_REDIRECTS) {
            if (!url.startsWith("https://")) throw IOException("Adresse non sécurisée")
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", "miryo-Updater")
            }
            when (val code = connection.responseCode) {
                in 200..299 -> return connection
                in 300..399 -> {
                    val location = connection.getHeaderField("Location") ?: throw IOException("Redirection sans adresse")
                    url = URL(URL(url), location).toString()
                    connection.disconnect()
                }
                404 -> {
                    connection.disconnect()
                    throw NotPublished()
                }
                else -> {
                    connection.disconnect()
                    throw IOException("Serveur des mises à jour : erreur $code")
                }
            }
        }
        throw IOException("Trop de redirections")
    }

    private fun packageInfo(context: Context) = context.packageManager.getPackageInfo(context.packageName, 0)

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private val APP = Regex("[a-z]{3,20}")
    private val SHA256 = Regex("[0-9a-f]{64}")
}
