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
import androidx.activity.SystemBarStyle
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

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as FerruxService.LocalBinder).service
            service = bound
            // Like Termux: opening the app always lands in a running shell.
            if (bound.sessions.sessions.value.isEmpty()) newSession()
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Edge-to-edge with transparent bars over the terminal background; the
        // content applies the insets itself (see FerruxScreen).
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
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
        // Bound from the first start until the Activity is destroyed, not just while
        // visible: unbinding in onStop tore down the terminal view, and with it the
        // font size and IME state, every time the user switched apps.
        if (FerruxNative.loadError == null && !bound) {
            bound = bindService(FerruxService.bindIntent(this), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        if (bound) unbindService(connection)
        bound = false
        service = null
        super.onDestroy()
    }

    private var bound = false

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
