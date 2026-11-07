// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ferrux.app.ui.FerruxScreen
import dev.ferrux.app.ui.FerruxTheme
import dev.ferrux.app.ui.NativeMissingScreen
import dev.ferrux.terminal.FerruxNative
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<FerruxService?>(null)

    /** Start one session automatically on a fresh launch, not after every rebind. */
    private var autoStartPending = true

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as FerruxService.LocalBinder).service
            service = bound
            if (autoStartPending && bound.sessions.sessions.value.isEmpty()) newSession()
            autoStartPending = false
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        autoStartPending = savedInstanceState == null
        requestNotificationPermission()

        val loadError = FerruxNative.loadError
        setContent {
            FerruxTheme {
                if (loadError != null) {
                    NativeMissingScreen(loadError)
                } else {
                    FerruxScreen(
                        service = service,
                        onNewSession = ::newSession,
                        onExit = ::finishAndRemoveTask,
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (FerruxNative.loadError == null) {
            bindService(FerruxService.bindIntent(this), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        if (FerruxNative.loadError == null) unbindService(connection)
        service = null
        super.onStop()
    }

    private fun newSession() {
        val svc = service ?: return
        try {
            svc.createSession()
        } catch (e: IOException) {
            Toast.makeText(this, getString(R.string.session_failed, e.message), Toast.LENGTH_LONG).show()
        }
    }

    private fun requestNotificationPermission() {
        // The foreground service runs without it; the permission only makes the
        // session notification (with its Exit action) visible. Don't block on it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }
}
