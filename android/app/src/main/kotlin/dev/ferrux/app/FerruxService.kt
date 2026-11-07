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
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Foreground service that owns all terminal sessions, so they survive the
 * Activity being destroyed. The Activity binds to it via [LocalBinder].
 *
 * Lifecycle: [createSession] (re)starts the service in the foreground; when the
 * last session ends, or the notification's Exit action is used, it leaves the
 * foreground and stops itself (it lingers only while an Activity is bound).
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

    override fun onCreate() {
        super.onCreate()
        sessions = SessionManager(applicationContext)
        createNotificationChannel()
        lifecycleScope.launch {
            var hadSessions = false
            sessions.sessions.collect { list ->
                if (list.isNotEmpty()) {
                    hadSessions = true
                    notificationManager.notify(NOTIFICATION_ID, buildNotification(list.size))
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
        if (intent?.action == ACTION_EXIT) {
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
        sessions.shutdown()
        super.onDestroy()
    }

    /** Starts a session, promoting this service to the foreground first. */
    fun createSession(rows: Int = 24, cols: Int = 80): Long {
        startForegroundService(Intent(this, FerruxService::class.java))
        return sessions.create(rows, cols)
    }

    private fun goForeground() {
        val notification = buildNotification(sessions.sessions.value.size)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private val notificationManager: NotificationManager
        get() = getSystemService(NotificationManager::class.java)

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW,
        ).apply { setShowBadge(false) }
        notificationManager.createNotificationChannel(channel)
    }

    private fun buildNotification(count: Int): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val exit = PendingIntent.getService(
            this, 1, Intent(this, FerruxService::class.java).setAction(ACTION_EXIT), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_text, count))
            .setContentIntent(open)
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(
                Notification.Action.Builder(null, getString(R.string.notification_exit), exit).build(),
            )
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "sessions"
        private const val NOTIFICATION_ID = 1
        // Explicit intents only, so this name is independent of the applicationId.
        const val ACTION_EXIT = "dev.ferrux.app.action.EXIT"

        fun bindIntent(context: Context) = Intent(context, FerruxService::class.java)
    }
}
