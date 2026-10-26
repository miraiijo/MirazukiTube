package dev.miraiijo.miryotube.player

import android.content.Context
import dev.miraiijo.miryotube.Settings
import dev.miraiijo.miryotube.ui.UI_SCALE_DEFAULT

/** miryo tube's own preferences (not SmartTube's): player choices and the app's switches. */
class PlayerPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("player", Context.MODE_PRIVATE)

    /** Held quality (1080 = 1080p, + MiryoPlayer.HDR_FLAG for its HDR version), 0 = Auto. */
    var quality: Int
        get() = prefs.getInt(KEY_QUALITY, 0)
        set(value) = prefs.edit().putInt(KEY_QUALITY, value).apply()

    /** Name of the last subtitle track chosen ("Français"), null = off. Used when the video has it. */
    var subtitles: String?
        get() = prefs.getString(KEY_SUBTITLES, null)
        set(value) = prefs.edit().putString(KEY_SUBTITLES, value).apply()

    var sponsorBlock: Boolean
        get() = prefs.getBoolean(KEY_SPONSORBLOCK, true)
        set(value) = prefs.edit().putBoolean(KEY_SPONSORBLOCK, value).apply()

    /** At the end of a video, the next one starts after a short countdown. */
    var autoplay: Boolean
        get() = prefs.getBoolean(KEY_AUTOPLAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTOPLAY, value).apply()

    /** YouTube's per-video loudness applied: no jump in volume from one video to the next. */
    var stableVolume: Boolean
        get() = prefs.getBoolean(KEY_STABLE_VOLUME, true)
        set(value) = prefs.edit().putBoolean(KEY_STABLE_VOLUME, value).apply()

    /** Community titles and thumbnails (DeArrow) instead of the clickbait ones. */
    var deArrow: Boolean
        get() = prefs.getBoolean(KEY_DEARROW, false)
        set(value) = prefs.edit().putBoolean(KEY_DEARROW, value).apply()

    /** Picture format: 0 = whole picture (fit), 1 = fills the screen (crops), 2 = stretched. */
    var pictureFormat: Int
        get() = prefs.getInt(KEY_PICTURE_FORMAT, 0)
        set(value) = prefs.edit().putInt(KEY_PICTURE_FORMAT, value).apply()

    /** "Statistiques pour les curieux" shown over the video. */
    var stats: Boolean
        get() = prefs.getBoolean(KEY_STATS, false)
        set(value) = prefs.edit().putBoolean(KEY_STATS, value).apply()

    /** "Fréquence d'affichage automatique": the TV follows the video's frame rate (off by default: HDMI blank at each switch). */
    var autoFrameRate: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FRAME_RATE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FRAME_RATE, value).apply()

    /** "Regarder sur la TV": a linked phone can send videos and drive the player (off until a phone is linked). */
    var remote: Boolean
        get() = prefs.getBoolean(KEY_REMOTE, false)
        set(value) = prefs.edit().putBoolean(KEY_REMOTE, value).apply()

    /** "Apparence": 0 = Auto (day / night by the hour), 1 = Jour, 2 = Nuit, 3 = Comme miryo (the default). */
    var appearance: Int
        get() = prefs.getInt(KEY_APPEARANCE, dev.miraiijo.miryotube.ui.APPEARANCE_DEFAULT)
        set(value) = prefs.edit().putInt(KEY_APPEARANCE, value).apply()

    /** "Taille de l'interface", in %; 0.12.1's "Grande" (large_ui) is read as 100 %. */
    var uiScale: Int
        get() = if (prefs.contains(KEY_UI_SCALE)) prefs.getInt(KEY_UI_SCALE, UI_SCALE_DEFAULT)
            else if (prefs.getBoolean(KEY_LARGE_UI, false)) 100 else UI_SCALE_DEFAULT
        set(value) = prefs.edit().putInt(KEY_UI_SCALE, value).remove(KEY_LARGE_UI).apply()

    /** "Recevoir même miryo tube fermé" (off by default: ~50–80 MB kept in memory). */
    var backgroundCast: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_CAST, false)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_CAST, value).apply()

    /** « Petite fenêtre » (PictureInPicture.HOME…): Accueil by default, as in miryo iptv. */
    var background: String
        get() = prefs.getString(KEY_BACKGROUND, null)?.takeIf { it in PictureInPicture.all } ?: PictureInPicture.HOME
        set(value) = prefs.edit().putString(KEY_BACKGROUND, value).apply()
    /** "Sons de navigation": 0 = Comme miryo (the default), 1 = Activés, 2 = Coupés. */
    var navSounds: Int
        get() = prefs.getInt(KEY_NAV_SOUNDS, 0)
        set(value) = prefs.edit().putInt(KEY_NAV_SOUNDS, value).apply()

    fun settings() = Settings(
        sponsorBlock = sponsorBlock, autoplay = autoplay, stableVolume = stableVolume, deArrow = deArrow,
        remote = remote, appearance = appearance, uiScale = uiScale, backgroundCast = backgroundCast,
        background = background,
        navSounds = navSounds,
    )

    private companion object {
        const val KEY_QUALITY = "quality"
        const val KEY_SUBTITLES = "subtitles"
        const val KEY_SPONSORBLOCK = "sponsorblock"
        const val KEY_AUTOPLAY = "autoplay"
        const val KEY_STABLE_VOLUME = "stable_volume"
        const val KEY_DEARROW = "dearrow"
        const val KEY_PICTURE_FORMAT = "picture_format"
        const val KEY_STATS = "stats"
        const val KEY_AUTO_FRAME_RATE = "auto_frame_rate"
        const val KEY_REMOTE = "remote"
        const val KEY_APPEARANCE = "appearance"
        const val KEY_LARGE_UI = "large_ui"
        const val KEY_UI_SCALE = "ui_scale"
        const val KEY_BACKGROUND_CAST = "background_cast"
        const val KEY_BACKGROUND = "background"
        const val KEY_NAV_SOUNDS = "nav_sounds"
    }
}
