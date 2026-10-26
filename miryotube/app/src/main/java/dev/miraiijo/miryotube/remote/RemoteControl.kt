package dev.miraiijo.miryotube.remote

import android.content.Context
import android.media.AudioManager
import com.liskovsoft.mediaserviceinterfaces.RemoteControlService
import com.liskovsoft.mediaserviceinterfaces.data.Command
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import io.reactivex.Observable
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.Disposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import java.util.concurrent.TimeUnit

/**
 * "Regarder sur la TV": the YouTube app on a phone, linked with a TV code, sends videos here and
 * drives the player (play, pause, seek, next, stop, volume). The engine's remote control (as in
 * SmartTube), listened to while miryo tube is on screen. Events go to the view model (open, stop)
 * and to the player screen (the rest); the player reports its state back so the phone follows.
 */
object RemoteControl {
    sealed interface Event {
        data class Open(val videoId: String, val startMs: Long) : Event
        data class Seek(val positionMs: Long) : Event
        data object Play : Event
        data object Pause : Event
        data object Next : Event
        data object Stop : Event
        /** The phone asks where we are: the player answers with [reportPlaying], or nobody does (idle). */
        data object StateRequest : Event
        data class Connected(val device: String?) : Event
        data object Disconnected : Event
    }

    /** miryo tube's screen exists (its view model handles the events) / is on screen. */
    @Volatile
    var uiAttached = false
    @Volatile
    var uiForeground = false
        set(value) {
            field = value
            if (!value) backgroundSinceMs = android.os.SystemClock.elapsedRealtime()
        }

    /** When miryo tube last left the screen (elapsedRealtime; 0: never shown in this process). */
    @Volatile
    private var backgroundSinceMs = 0L

    /**
     * miryo may switch the engine's account for one of its reads: miryo tube not on screen, and
     * left for at least [SWITCH_GRACE_MS] (its requests still running when it left have finished).
     */
    val accountSwitchAllowed: Boolean
        get() = !uiForeground && android.os.SystemClock.elapsedRealtime() - backgroundSinceMs >= SWITCH_GRACE_MS

    private const val SWITCH_GRACE_MS = 10_000L

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 32)
    val events: SharedFlow<Event> = _events

    private val service: RemoteControlService get() = YouTubeServiceManager.instance().remoteControlService
    private var listening: Disposable? = null
    private var context: Context? = null
    private val VIDEO_ID = Regex("[A-Za-z0-9_-]{11}")

    /** miryo tube on screen: commands are listened to (the loop restarts by itself after an error). */
    fun start(context: Context) {
        this.context = context.applicationContext
        if (listening?.isDisposed == false) return
        listening = service.commandObserve
            .subscribeOn(Schedulers.io())
            .observeOn(AndroidSchedulers.mainThread())
            .retryWhen { errors -> errors.delay(RETRY_S, TimeUnit.SECONDS) }
            .subscribe({ handle(it) }, { /* retried above; an error here only ends this session */ })
    }

    /** miryo tube left: no more listening (a video can't be opened in the background anyway). */
    fun stop() {
        listening?.dispose()
        listening = null
    }

    /** The code to type on the phone (YouTube › Paramètres › Regarder sur la TV › Saisir le code TV). */
    fun pairingCode(): Observable<String> = service.pairingCodeObserve

    fun reportPlaying(videoId: String, positionMs: Long, durationMs: Long, playing: Boolean) =
        post(service.postStartPlayingObserve(videoId, positionMs, durationMs, if (playing) RemoteControlService.STATE_PLAYING else RemoteControlService.STATE_PAUSED))

    fun reportState(positionMs: Long, durationMs: Long, playing: Boolean) =
        post(service.postStateChangeObserve(positionMs, durationMs, if (playing) RemoteControlService.STATE_PLAYING else RemoteControlService.STATE_PAUSED))

    fun reportIdle() = post(service.postStateChangeObserve(0, 0, RemoteControlService.STATE_IDLE))

    /** Only while a phone is linked (no request otherwise). */
    @Volatile
    var connected = false
        private set

    /**
     * Reports leave one at a time, in order (an "idle" never overtakes the next video's "playing"):
     * the engine's observables run on its own threads, so each one is awaited here.
     */
    private val reports = java.util.concurrent.Executors.newSingleThreadExecutor()

    private fun post(call: Observable<Void>?) {
        if (!connected || call == null) return
        reports.execute { runCatching { call.timeout(REPORT_TIMEOUT_S, TimeUnit.SECONDS).blockingSubscribe({}, {}) } }
    }

    private fun handle(command: Command) {
        when (command.type) {
            Command.TYPE_CONNECTED -> {
                connected = true
                _events.tryEmit(Event.Connected(command.deviceName))
            }
            Command.TYPE_DISCONNECTED -> {
                connected = false
                _events.tryEmit(Event.Disconnected)
            }
            Command.TYPE_OPEN_VIDEO -> {
                connected = true
                val id = command.videoId?.takeIf(VIDEO_ID::matches) ?: return
                _events.tryEmit(Event.Open(id, command.currentTimeMs.coerceAtLeast(0L)))
            }
            Command.TYPE_SEEK -> _events.tryEmit(Event.Seek(command.currentTimeMs.coerceAtLeast(0L)))
            Command.TYPE_PLAY -> _events.tryEmit(Event.Play)
            Command.TYPE_PAUSE -> _events.tryEmit(Event.Pause)
            Command.TYPE_NEXT -> _events.tryEmit(Event.Next)
            Command.TYPE_STOP -> _events.tryEmit(Event.Stop)
            Command.TYPE_GET_STATE -> {
                connected = true
                _events.tryEmit(Event.StateRequest)
            }
            Command.TYPE_VOLUME -> setVolume(command)
            else -> Unit
        }
    }

    /** Phone's volume slider (absolute 0–100, or a step): the box's media volume. */
    private fun setVolume(command: Command) {
        val audio = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        val max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val current = audio.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        val target = (if (command.delta != -1) current + command.delta else command.volume).coerceIn(0, 100)
        audio.setStreamVolume(AudioManager.STREAM_MUSIC, (target * max + 50) / 100, 0)
        post(service.postVolumeChangeObserve(target))
    }

    private const val RETRY_S = 30L
    private const val REPORT_TIMEOUT_S = 10L
}
