// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.content.res.Resources
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service that owns all terminal sessions, so they survive the
 * Activity being destroyed. The Activity binds to it via [LocalBinder].
 *
 * Lifecycle: [createSession] (re)starts the service in the foreground; when the
 * last session ends, or the notification's Exit action is used, it leaves the
 * foreground and stops itself (it lingers only while an Activity is bound).
 *
 * The notification shows how many sessions there are, opens the app when tapped
 * and has an Exit action that closes every session. It is posted with the
 * `specialUse` foreground service type that Android 14+ requires. From Android 14 on
 * the user can swipe it away; the service keeps running, and the notification comes
 * back with the next change to the sessions.
 *
 * Like Termux, the notification can also acquire a partial wake lock, so that
 * long-running jobs keep the CPU awake while the screen is off. It is off by
 * default and is released on Exit.
 */
class FerruxService : LifecycleService() {

    inner class LocalBinder : Binder() {
        val service: FerruxService get() = this@FerruxService
    }

    private val binder = LocalBinder()

    lateinit var sessions: SessionManager
        private set

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user chose Exit; bound Activities should finish. */
    val exitRequests: SharedFlow<Unit> = _exitRequests.asSharedFlow()

    private var wakeLock: PowerManager.WakeLock? = null
    private val _wakeLockHeld = MutableStateFlow(false)

    /** Whether the user acquired the wake lock from the notification. */
    val wakeLockHeld: StateFlow<Boolean> = _wakeLockHeld.asStateFlow()

    override fun onCreate() {
        super.onCreate()
        sessions = SessionManager(applicationContext)
        createNotificationChannel()
        lifecycleScope.launch {
            var hadSessions = false
            sessions.sessions.combine(wakeLockHeld) { list, held -> list to held }.collect { (list, held) ->
                if (list.isNotEmpty()) {
                    hadSessions = true
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(list, held))
                } else if (hadSessions) {
                    hadSessions = false
                    stopForeground(Service.STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_WAKE_LOCK -> {
                setWakeLock(true)
                return START_NOT_STICKY
            }
            ACTION_WAKE_UNLOCK -> {
                setWakeLock(false)
                return START_NOT_STICKY
            }
        }
        if (intent?.action == ACTION_EXIT) {
            setWakeLock(false)
            sessions.killAll()
            stopForeground(Service.STOP_FOREGROUND_REMOVE)
            stopSelf()
            _exitRequests.tryEmit(Unit)
            return START_NOT_STICKY
        }
        goForeground()
        // Sessions cannot be restored after process death, so don't ask to be restarted.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        setWakeLock(false)
        sessions.shutdown()
        super.onDestroy()
    }

    /** Starts a session, promoting this service to the foreground first. */
    fun createSession(rows: Int = 24, cols: Int = 80): Long {
        try {
            startForegroundService(Intent(this, FerruxService::class.java))
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (Android 12+): only possible if
            // a session is created while the app is in the background. The session
            // still works while the app is bound; it is just not protected.
            Log.w(FerruxApplication.TAG, "cannot start the foreground service now", e)
        }
        return sessions.create(rows, cols)
    }

    /** Acquires or releases the partial wake lock (see the class comment). */
    private fun setWakeLock(held: Boolean) {
        if (held && wakeLock == null) {
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, WAKE_LOCK_TAG)
                .apply {
                    setReferenceCounted(false)
                    // Held until the user releases it or exits, like Termux's.
                    @Suppress("WakelockTimeout")
                    acquire()
                }
            Log.i(FerruxApplication.TAG, "wake lock acquired")
        } else if (!held) {
            wakeLock?.let {
                if (it.isHeld) it.release()
                Log.i(FerruxApplication.TAG, "wake lock released")
            }
            wakeLock = null
        }
        _wakeLockHeld.value = held
    }

    private fun goForeground() {
        val notification = buildNotification(sessions.sessions.value, wakeLockHeld.value)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        // Low importance: no sound or peeking, but visible in the shade (and on the
        // lock screen, where it shows only the session count).
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.notification_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(list: List<SessionInfo>, wakeLockHeld: Boolean): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, FerruxService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE,
        )
        val wakeLockAction = if (wakeLockHeld) ACTION_WAKE_UNLOCK else ACTION_WAKE_LOCK
        val toggleWakeLock = PendingIntent.getService(
            this, 2, Intent(this, FerruxService::class.java).setAction(wakeLockAction),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setColor(getColor(R.color.ferrux_accent))
            .setContentTitle(getString(R.string.app_name))
            .setContentText(sessionSummary(resources, list, wakeLockHeld))
            .setContentIntent(open)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setLocalOnly(true)
            .addAction(
                Notification.Action.Builder(
                    android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
                    getString(R.string.notification_exit),
                    exit,
                ).build(),
            )
            .addAction(
                Notification.Action.Builder(
                    null,
                    getString(
                        if (wakeLockHeld) R.string.notification_wake_lock_release else R.string.notification_wake_lock_acquire,
                    ),
                    toggleWakeLock,
                ).build(),
            )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Show it at once rather than after Android 12's 10 s grace period.
            builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        // Explicit intents only, so this name is independent of the applicationId.
        const val ACTION_EXIT = "dev.ferrux.app.action.EXIT"
        const val ACTION_WAKE_LOCK = "dev.ferrux.app.action.WAKE_LOCK"
        const val ACTION_WAKE_UNLOCK = "dev.ferrux.app.action.WAKE_UNLOCK"
        private const val WAKE_LOCK_TAG = "ferrux:sessions"

        fun bindIntent(context: Context) = Intent(context, FerruxService::class.java)

        /** "1 session", "3 sessions · 1 exited", "2 sessions · wake lock held". */
        fun sessionSummary(res: Resources, list: List<SessionInfo>, wakeLockHeld: Boolean = false): String {
            var text = res.getQuantityString(R.plurals.notification_sessions, list.size, list.size)
            val exited = list.count { it.exitCode != null }
            if (exited > 0) text = res.getString(R.string.notification_sessions_exited, text, exited)
            if (wakeLockHeld) text = res.getString(R.string.notification_wake_lock_held, text)
            return text
        }
    }
}
