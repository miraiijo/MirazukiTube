package dev.miraiijo.miryotube

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
import dev.miraiijo.miryotube.link.MiryoLink
import dev.miraiijo.miryotube.link.MiryoPip
import dev.miraiijo.miryotube.player.PictureInPicture
import dev.miraiijo.miryotube.ui.Appearance
import dev.miraiijo.miryotube.ui.BrandIntro
import dev.miraiijo.miryotube.ui.DayColors
import dev.miraiijo.miryotube.ui.MiryoTubeTheme
import dev.miraiijo.miryotube.ui.NightColors
import dev.miraiijo.miryotube.ui.Palette
import dev.miraiijo.miryotube.ui.Root
import dev.miraiijo.miryotube.ui.SystemClicks
import dev.miraiijo.miryotube.ui.windowBackground

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels()

    /** The logo animation over the first frames of a fresh start (see onCreate). */
    private val intro = mutableStateOf(false)

    /** The pages are composed (after the intro's dot has landed, right away without the intro). */
    private val pagesShown = mutableStateOf(true)

    override fun onCreate(savedInstanceState: Bundle?) {
        // A fresh start shows the logo animation (BrandIntro) over Accueil while it loads. A video
        // opened from a link (miryo, a browser…) skips it: the system splash (the mark) stays until
        // there's something to show (rows, an error, "Qui regarde ?", the video), at most SPLASH_MAX_MS.
        intro.value = savedInstanceState == null && intent?.data == null
        pagesShown.value = !intro.value
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
            MiryoTubeTheme(Appearance.entries.getOrElse(settings.appearance) { Appearance.AUTO }, uiScale = settings.uiScale) {
                // And when the look changes (Auto at 7:30 / 19:30, the header's button).
                val background = Palette.Background
                LaunchedEffect(background) { window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(background.toArgb())) }
                val sounds = settings.navSoundsOn
                SystemClicks(sounds)
                Box(Modifier.fillMaxSize()) {
                    if (pagesShown.value) androidx.compose.runtime.CompositionLocalProvider(dev.miraiijo.miryotube.ui.LocalNavSounds provides sounds) { Root(vm) }
                    if (intro.value) BrandIntro(ready = { vm.state.value.readyToShow() }, maxMs = SPLASH_MAX_MS, onSettled = { pagesShown.value = true }, onDone = { intro.value = false })
                }
            }
        }
        if (savedInstanceState == null) handleLink(intent)
        PictureInPicture.enter = ::enterPip
        PictureInPicture.dismiss = { moveTaskToBack(true) }
        addOnPictureInPictureModeChangedListener { info ->
            if (info.isInPictureInPictureMode) {
                PictureInPicture.opened(this)
                return@addOnPictureInPictureModeChangedListener
            }
            PictureInPicture.closed()
            leftPip()
            // Window closed by the user (not expanded back): the video ends (its position is saved
            // by the player screen as the app stops).
            if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) vm.stopPlaying()
        }
    }

    /** miryo was told the window opens (MiryoPip): told again when it closes. */
    private var pipTold = false

    /**
     * The system's picture-in-picture window: only if the box offers it and while a video plays
     * (paused, Accueil just leaves, as Android TV asks). miryo is told first so that, back on the
     * launcher, it lets the window play. False when it couldn't start.
     */
    private fun enterPip(): Boolean {
        val player = PictureInPicture.player ?: return false
        if (isInPictureInPictureMode || !player.exo.playWhenReady) return false
        if (!packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
        val format = player.exo.videoFormat
        val ratio = if (format != null && format.width > 0 && format.height > 0) (format.width * format.pixelWidthHeightRatio / format.height) else 16f / 9f
        val fitted = ratio.coerceIn(0.42f, 2.38f)
        val params = android.app.PictureInPictureParams.Builder()
            .setAspectRatio(android.util.Rational((fitted * 1000).toInt(), 1000))
            // From the full-screen picture (the small in-app window: the system's default animation).
            .setSourceRectHint(if (vm.state.value.minimized) null else pictureRect(fitted))
            .build()
        MiryoPip.tell(this, true)
        pipTold = true
        val entered = runCatching { enterPictureInPictureMode(params) }.getOrDefault(false)
        if (!entered) leftPip()
        return entered
    }

    /**
     * Where the picture is on screen right now (the video fitted in the window): the system grows
     * the window from there, without a jump, instead of cutting to the small window.
     */
    private fun pictureRect(ratio: Float): android.graphics.Rect? {
        val w = window.decorView.width
        val h = window.decorView.height
        if (w <= 0 || h <= 0) return null
        return if (w.toFloat() / h > ratio) {
            val pw = (h * ratio).toInt()
            android.graphics.Rect((w - pw) / 2, 0, (w + pw) / 2, h)
        } else {
            val ph = (w / ratio).toInt()
            android.graphics.Rect(0, (h - ph) / 2, w, (h + ph) / 2)
        }
    }

    private fun leftPip() {
        if (!pipTold) return
        pipTold = false
        MiryoPip.tell(this, false)
    }

    /** Accueil during a video: the system's window, over miryo (if the box offers it). */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (leftByUser()) enterPip()
    }

    /** When this app last opened a screen of its own (installer, Android's settings, dictation). */
    private var ownLaunchAt = 0L

    /** Also called when this app opens one of those screens: never the window then, only for Accueil. */
    @Deprecated("Kept to see the app's own launches")
    override fun startActivityForResult(intent: Intent, requestCode: Int, options: Bundle?) {
        ownLaunchAt = android.os.SystemClock.uptimeMillis()
        @Suppress("DEPRECATION")
        super.startActivityForResult(intent, requestCode, options)
    }

    private fun leftByUser() = android.os.SystemClock.uptimeMillis() - ownLaunchAt > OWN_LAUNCH_MS

    override fun onDestroy() {
        PictureInPicture.closed()
        leftPip()
        PictureInPicture.enter = { false }
        PictureInPicture.dismiss = {}
        super.onDestroy()
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
     * RestrictedApi: lint's false positive on ComponentActivity (androidx.core's own @RestrictTo on
     * its key dispatch); overriding Activity.dispatchKeyEvent is the public Android API.
     */
    @android.annotation.SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean =
        dev.miraiijo.miryotube.ui.OkRelease.dispatch(event, ::dispatchMapped)

    // RestrictedApi: same false positive as above (super.dispatchKeyEvent is the public Activity API).
    @android.annotation.SuppressLint("RestrictedApi")
    private fun dispatchMapped(event: KeyEvent): Boolean {
        // A Sony pad (DualSense, DualShock 4) on a box without the PlayStation driver (Android 10/11
        // kernels): Android's Generic.kl scrambles it (Cross came as B = Back, Square as A = OK).
        // Its buttons are read from their raw code instead, the same whatever the key layout.
        val sony = if (event.device?.vendorId == SONY_VENDOR && android.os.Build.VERSION.SDK_INT < SONY_DRIVER_SDK) SONY_SCANCODES[event.scanCode] else null
        if (BuildConfig.DEBUG && event.device?.vendorId == SONY_VENDOR && event.action == KeyEvent.ACTION_DOWN) {
            android.util.Log.d("miryotube.Pad", "key=${KeyEvent.keyCodeToString(event.keyCode)} scan=0x${Integer.toHexString(event.scanCode)} → ${sony?.let(KeyEvent::keyCodeToString)}")
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
            val miryo = fromMiryo(intent)
            val account = if (miryo) intent.getStringExtra(EXTRA_ACCOUNT_ID)?.takeIf { it.length in 1..MAX_ACCOUNT_ID } else null
            // An account asked for (even invalid: then the selected one plays) never shows the picker.
            val skipPicker = miryo && (intent.getBooleanExtra(EXTRA_SKIP_PICKER, false) || intent.hasExtra(EXTRA_ACCOUNT_ID))
            vm.playLink(id, startSeconds(uri.getQueryParameter("t")), account, skipPicker)
        }.onFailure { android.util.Log.w("miryotube", "link ignored: ${it.javaClass.simpleName}") }
    }

    /**
     * The link comes from miryo (miryo-link v3: package and signing certificate). The caller is
     * the system's referrer (the launching package); a caller-set referrer extra, which any app can
     * forge, makes it untrusted. Best effort on Android 10 (no launching uid before Android 14).
     */
    private fun fromMiryo(intent: Intent): Boolean {
        if (intent.hasExtra(Intent.EXTRA_REFERRER) || intent.hasExtra(Intent.EXTRA_REFERRER_NAME)) return false
        val caller = referrer?.takeIf { it.scheme == "android-app" }?.host ?: return false
        return caller == MiryoLink.MIRYO && runCatching { MiryoLink.isTrusted(this, caller) }.getOrDefault(false)
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

    internal companion object {
        /**
         * The splash never stays longer than this, whatever is still loading (Accueil comes from
         * the network: longer than miryo iptv's 1.5 s, its rows usually arrive within it).
         */
        const val SPLASH_MAX_MS = 3_000L

        /** onUserLeaveHint this soon after the app opened a screen itself: that screen, not Accueil. */
        const val OWN_LAUNCH_MS = 1_000L
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
        const val EXTRA_ACCOUNT_ID = "dev.miraiijo.miryo.extra.ACCOUNT_ID"
        const val EXTRA_SKIP_PICKER = "dev.miraiijo.miryo.extra.SKIP_PICKER"
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
