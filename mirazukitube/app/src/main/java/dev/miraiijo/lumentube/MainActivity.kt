package dev.miraiijo.lumentube

import android.content.Intent
import android.os.Bundle
import android.view.InputDevice
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import dev.miraiijo.lumentube.link.MirazukiLink
import dev.miraiijo.lumentube.ui.Appearance
import dev.miraiijo.lumentube.ui.BrandIntro
import dev.miraiijo.lumentube.ui.DayColors
import dev.miraiijo.lumentube.ui.MirazukiTubeTheme
import dev.miraiijo.lumentube.ui.NightColors
import dev.miraiijo.lumentube.ui.Palette
import dev.miraiijo.lumentube.ui.Root
import dev.miraiijo.lumentube.ui.windowBackground

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** The logo animation over the first frames of a fresh start (see onCreate). */
    private val intro = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        // A fresh start shows the logo animation (BrandIntro) over Accueil while it loads. A video
        // opened from a link (miryo, a browser…) skips it: the system splash (the mark) stays until
        // there's something to show (rows, an error, "Qui regarde ?", the video), at most SPLASH_MAX_MS.
        intro.value = savedInstanceState == null && intent?.data == null
        val splash = installSplashScreen()
        val shownAt = android.os.SystemClock.uptimeMillis()
        splash.setKeepOnScreenCondition { !intro.value && android.os.SystemClock.uptimeMillis() - shownAt < SPLASH_MAX_MS && !vm.state.value.readyToShow() }
        super.onCreate(savedInstanceState)
        // Warm-up, like SmartTube's splash: YouTube's player script and the signature engine load now
        // (in the background) instead of during the first video's start. Here rather than in the
        // Application: a feed query from miryo starts the process without needing all that.
        com.liskovsoft.youtubeapi.service.YouTubeServiceManager.instance().refreshCacheIfNeeded()
        // The theme's color behind the first frame (Jour: no black flash before the pages).
        val initialBackground = windowBackground(vm.state.value.settings.appearance)
        Palette.colors = if (initialBackground == DayColors.background) DayColors else NightColors
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(initialBackground.toArgb()))
        setContent {
            val settings = vm.state.collectAsState().value.settings
            MirazukiTubeTheme(Appearance.entries.getOrElse(settings.appearance) { Appearance.AUTO }, uiScale = settings.uiScale) {
                // And when the look changes (Auto at 7:30 / 19:30, the header's button).
                val background = Palette.Background
                LaunchedEffect(background) { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb())) }
                Box(Modifier.fillMaxSize()) {
                    Root(vm)
                    if (intro.value) BrandIntro(ready = { vm.state.value.readyToShow() }, maxMs = SPLASH_MAX_MS, onDone = { intro.value = false })
                }
            }
        }
        if (savedInstanceState == null) handleLink(intent)
    }

    // "Regarder sur la TV": listened to only while miryo tube is on screen (Android 10 doesn't let a
    // background app bring itself up anyway).
    override fun onStart() {
        super.onStart()
        vm.onForeground(true)
    }

    override fun onStop() {
        vm.onForeground(false)
        super.onStop()
    }

    /**
     * Gamepad (DualSense, Xbox…): its buttons become the keys the app already understands. Only
     * events from a gamepad are touched, never the remote's. Android's own fallbacks (A → OK,
     * B / Y → Back, X / Start → OK) are replaced by this mapping:
     *  A = OK · B = Back · X = play / pause (and the small window back full screen) ·
     *  Y / Start = Menu (a card's menu, the player's settings) · Select = search ·
     *  L1 / R1 = −10 s / +10 s · L2 / R2 = previous / next chapter (±60 s without chapters).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        dev.miraiijo.lumentube.ui.OkRelease.dispatch(event, ::dispatchMapped)

    private fun dispatchMapped(event: KeyEvent): Boolean {
        // A Sony pad (DualSense, DualShock 4) on a box without the PlayStation driver (Android 10/11
        // kernels): Android's Generic.kl scrambles it (Cross came as B = Back, Square as A = OK).
        // Its buttons are read from their raw code instead, the same whatever the key layout.
        val sony = if (event.device?.vendorId == SONY_VENDOR && android.os.Build.VERSION.SDK_INT < SONY_DRIVER_SDK) SONY_SCANCODES[event.scanCode] else null
        if (event.device?.vendorId == SONY_VENDOR && event.action == KeyEvent.ACTION_DOWN) {
            android.util.Log.d("MirazukiTube.Pad", "key=${KeyEvent.keyCodeToString(event.keyCode)} scan=0x${Integer.toHexString(event.scanCode)} → ${sony?.let(KeyEvent::keyCodeToString)}")
        }
        // L3 / R3: nothing (they used to come as Select / Start).
        if (sony == IGNORED) return true
        val mapped = GAMEPAD_KEYS[sony ?: event.keyCode]
        if (mapped == null || (sony == null && !event.isFromSource(InputDevice.SOURCE_GAMEPAD))) return super.dispatchKeyEvent(event)
        val copy = KeyEvent(
            event.downTime, event.eventTime, event.action, mapped, event.repeatCount, event.metaState,
            event.deviceId, event.scanCode, event.flags, event.source,
        )
        return super.dispatchKeyEvent(copy)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // getReferrer() reads the current intent: this one, not the one that started the activity.
        setIntent(intent)
        handleLink(intent)
    }

    /**
     * youtube.com/watch?v=…&t=… or youtu.be/…?t=… from another app (miryo, a browser…): played from
     * t when given (miryo's "continue" passes the saved position). A malformed link or extras
     * (opaque URI, bad parcel) are ignored, never a crash.
     */
    private fun handleLink(intent: Intent?) {
        if (intent == null) return
        runCatching {
            val uri = intent.data ?: return
            val id = uri.getQueryParameter("v") ?: uri.takeIf { it.host == "youtu.be" }?.lastPathSegment
            // Only a real video id (11 characters): the engine puts it as is in its signed-in requests.
            if (id == null || !VIDEO_ID.matches(id)) return
            // miryo's contract v2 (the account to play as, no "Qui regarde ?"): from miryo only.
            val mirazuki = fromMirazuki(intent)
            val account = if (mirazuki) intent.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.length in 1..MAX_ACCOUNT_ID } else null
            // An account asked for (even invalid: then the selected one plays) never shows the picker.
            val skipPicker = mirazuki && (intent.getBooleanExtra(EXTRA_SKIP_PICKER, false) || intent.hasExtra(EXTRA_ACCOUNT_ID))
            vm.playLink(id, startSeconds(uri.getQueryParameter("t")), account, skipPicker)
        }.onFailure { android.util.Log.w("MirazukiTube", "link ignored: ${it.javaClass.simpleName}") }
    }

    /**
     * The link comes from miryo (mirazuki-link v3: package and signing certificate). The caller is
     * the system's referrer (the launching package); a caller-set referrer extra, which any app can
     * forge, makes it untrusted. Best effort on Android 10 (no launching uid before Android 14).
     */
    private fun fromMirazuki(intent: Intent): Boolean {
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) return false
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return false
        return caller == MirazukiLink.MIRAZUKI && runCatching { MirazukiLink.isTrusted(this, caller) }.getOrDefault(false)
    }

    /** "90", "90s", "1m30s" or "1h2m3s" → seconds (0 when absent or unreadable). */
    private fun startSeconds(t: String?): Int {
        if (t.isNullOrBlank() || t.length > 16) return 0
        val total = t.toLongOrNull() ?: Regex("(\\d{1,9})([hms])").findAll(t.lowercase()).sumOf { match ->
            val value = match.groupValues[1].toLong()
            when (match.groupValues[2]) {
                "h" -> value * 3600
                "m" -> value * 60
                else -> value
            }
        }
        // Capped at a day: a huge or malformed value never overflows nor crashes.
        return total.coerceIn(0L, MAX_START_S).toInt()
    }

    private companion object {
        /**
         * The splash never stays longer than this, whatever is still loading (Accueil comes from
         * the network: longer than miryo iptv's 1.5 s, its rows usually arrive within it).
         */
        const val SPLASH_MAX_MS = 3_000L
        val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")
        val GAMEPAD_KEYS = mapOf(
            KeyEvent.KEYCODE_BUTTON_A to KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_BUTTON_B to KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_BUTTON_X to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_BUTTON_Y to KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_START to KeyEvent.KEYCODE_MENU,
            KeyEvent.KEYCODE_BUTTON_SELECT to KeyEvent.KEYCODE_SEARCH,
            KeyEvent.KEYCODE_BUTTON_L1 to KeyEvent.KEYCODE_MEDIA_REWIND,
            KeyEvent.KEYCODE_BUTTON_R1 to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,
            KeyEvent.KEYCODE_BUTTON_L2 to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            KeyEvent.KEYCODE_BUTTON_R2 to KeyEvent.KEYCODE_MEDIA_NEXT,
        )
        const val MAX_START_S = 24 * 3600L
        const val EXTRA_ACCOUNT_ID = "dev.miraiijo.lumen.extra.ACCOUNT_ID"
        const val EXTRA_SKIP_PICKER = "dev.miraiijo.lumen.extra.SKIP_PICKER"
        const val MAX_ACCOUNT_ID = 128
        const val SONY_VENDOR = 0x054c
        /** Sony pads read right (hid-playstation driver, Android 12+ key layouts) from this version. */
        const val SONY_DRIVER_SDK = 31
        const val IGNORED = KeyEvent.KEYCODE_UNKNOWN
        /**
         * Raw codes of a Sony pad without its driver (HID button order: Square, Cross, Circle,
         * Triangle, L1, R1, L2, R2, Create, Options, L3, R3, PS), as gamepad buttons (BTN_SOUTH…
         * when the pad declares itself a gamepad, BTN_TRIGGER… when a joystick). PS is left to the system.
         */
        val SONY_SCANCODES: Map<Int, Int> = listOf(0x130, 0x120).flatMap { base ->
            listOf(
                KeyEvent.KEYCODE_BUTTON_X, // Square: play / pause
                KeyEvent.KEYCODE_BUTTON_A, // Cross: OK
                KeyEvent.KEYCODE_BUTTON_B, // Circle: Back
                KeyEvent.KEYCODE_BUTTON_Y, // Triangle: Menu
                KeyEvent.KEYCODE_BUTTON_L1,
                KeyEvent.KEYCODE_BUTTON_R1,
                KeyEvent.KEYCODE_BUTTON_L2,
                KeyEvent.KEYCODE_BUTTON_R2,
                KeyEvent.KEYCODE_BUTTON_SELECT, // Create: Rechercher
                KeyEvent.KEYCODE_BUTTON_START, // Options: Menu
                IGNORED, // L3
                IGNORED, // R3
            ).mapIndexed { index, key -> base + index to key }
        }.toMap()
    }
}
