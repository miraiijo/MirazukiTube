package dev.miraiijo.miryotube.player

import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.android.exoplayer2.Player

/**
 * The system's picture-in-picture window (not the in-app small window): Accueil during a video
 * (and Retour, if chosen in Réglages › Lecture) keeps it playing over miryo or another app.
 * The player screen registers its player here; MainActivity opens and closes the window.
 */
object PictureInPicture {
    /** In the system's window: the player fills it, no controls nor pages. */
    var active by mutableStateOf(false)
        private set

    /** The video on screen (full screen or in the small in-app window), null without one. */
    var player: MiryoPlayer? = null
        private set

    private var session: PipSession? = null

    /** The video's title and channel: Android TV shows them on the window's notice (else « Programme sans titre »). */
    var title: String = ""
        private set
    var channel: String = ""
        private set

    /** The player screen's title and channel, once known (a link learns them after loading). */
    fun describe(p: MiryoPlayer, title: String, channel: String) {
        if (player !== p) return
        this.title = title
        this.channel = channel
        session?.describe(title, channel)
    }

    /** Opens the window (MainActivity); false when the box can't or nothing plays. */
    var enter: () -> Boolean = { false }

    /** Closes the window (MainActivity: the app goes behind, as the window's « Fermer »). */
    var dismiss: () -> Unit = {}

    /** The player screen's player (each video has its own: « À suivre » in the window takes the session along). */
    fun attach(context: Context, p: MiryoPlayer) {
        player = p
        if (active) restartSession(context)
    }

    fun detach(p: MiryoPlayer) {
        if (player !== p) return
        player = null
        session?.release()
        session = null
    }

    fun opened(context: Context) {
        active = true
        restartSession(context)
    }

    fun closed() {
        active = false
        session?.release()
        session = null
    }

    private fun restartSession(context: Context) {
        session?.release()
        session = player?.let { PipSession(context.applicationContext, it).also { s -> s.describe(title, channel) } }
    }

    /** Former « Petite fenêtre » setting value (kept only so that saved settings still read). */
    const val HOME = "pip_home"
}

/**
 * The system's media session, only while the picture-in-picture window shows: the window's menu
 * gets its Lecture / Pause button and the remote's ▶︎ ⏸ keys drive the window. Never outside the
 * window: the remote's media keys never start a hidden player.
 */
class PipSession(context: Context, private val player: MiryoPlayer) {
    private val session = MediaSession(context, "miryo tube")

    private val listener = object : Player.EventListener {
        override fun onPlayerStateChanged(playWhenReady: Boolean, playbackState: Int) = publish()
    }

    init {
        session.setCallback(
            object : MediaSession.Callback() {
                override fun onPlay() { player.exo.playWhenReady = true }
                override fun onPause() { player.exo.playWhenReady = false }
            },
            Handler(Looper.getMainLooper()),
        )
        player.exo.addListener(listener)
        publish()
        session.isActive = true
    }

    private fun publish() {
        val playing = player.exo.playWhenReady
        session.setPlaybackState(
            PlaybackState.Builder()
                .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE)
                .setState(if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED, player.exo.currentPosition.coerceAtLeast(0), if (playing) 1f else 0f)
                .build(),
        )
    }

    fun describe(title: String, channel: String) {
        session.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, title.ifBlank { "miryo tube" })
                .putString(MediaMetadata.METADATA_KEY_ARTIST, channel)
                .build(),
        )
    }

    fun release() {
        player.exo.removeListener(listener)
        session.isActive = false
        session.release()
    }
}
