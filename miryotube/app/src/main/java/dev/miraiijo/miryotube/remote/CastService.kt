package dev.miraiijo.miryotube.remote

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import dev.miraiijo.miryotube.MainActivity
import dev.miraiijo.miryotube.R
import dev.miraiijo.miryotube.Settings
import dev.miraiijo.miryotube.player.PlayerPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * "Recevoir même miryo tube fermé" (off by default): keeps the phone link listening while miryo tube
 * is not on screen, and opens it when the phone sends a video. A foreground service (it would be
 * killed otherwise), only the engine's remote loop: no UI, no player (~50–80 MB on the box).
 *
 * Opening an activity from the background needs, on Android 10+, the "display over other apps"
 * permission, granted once with adb (Android TV has no screen for it):
 *   adb shell appops set dev.miraiijo.miryotube SYSTEM_ALERT_WINDOW allow
 */
class CastService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        running = true
        RemoteControl.start(this)
        scope.launch { RemoteControl.events.collect(::onEvent) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        running = false
        scope.cancel()
        // miryo tube on screen keeps its own listening.
        if (!RemoteControl.uiForeground) RemoteControl.stop()
        super.onDestroy()
    }

    /** On screen, the view model handles everything; here only what needs miryo tube to come back. */
    private fun onEvent(event: RemoteControl.Event) {
        if (RemoteControl.uiForeground) return
        when (event) {
            is RemoteControl.Event.Open -> openApp(event)
            // Nobody to answer (no screen alive): the phone sees an idle TV.
            RemoteControl.Event.StateRequest -> if (!RemoteControl.uiAttached) RemoteControl.reportIdle()
            else -> Unit
        }
    }

    private fun openApp(event: RemoteControl.Event.Open) {
        val intent = if (RemoteControl.uiAttached) {
            // The view model got the video too and plays it: the screen only has to come back.
            Intent(this, MainActivity::class.java)
        } else {
            // No screen alive: the same path as a YouTube link (video id and start, checked there).
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/watch?v=${event.videoId}&t=${event.startMs / 1000}"), this, MainActivity::class.java)
        }
        // Refused silently without the overlay permission (see above): the phone still sees the TV.
        runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Regarder sur la TV", NotificationManager.IMPORTANCE_MIN),
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("miryo tube attend le téléphone")
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 7

        @Volatile
        var running = false
            private set

        /** Starts or stops the service to match "Regarder sur la TV" + "Recevoir même miryo tube fermé". */
        fun sync(context: Context, settings: Settings) {
            val want = settings.remote && settings.backgroundCast
            val intent = Intent(context, CastService::class.java)
            if (want && !running) runCatching { context.startForegroundService(intent) }
            else if (!want && running) context.stopService(intent)
        }
    }
}

/** After a reboot: back to waiting for the phone, when it's enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        CastService.sync(context, PlayerPrefs(context).settings())
    }
}
