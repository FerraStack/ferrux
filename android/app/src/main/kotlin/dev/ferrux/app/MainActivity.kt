// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ferrux.app.ui.FerruxScreen
import dev.ferrux.app.ui.FerruxTheme
import dev.ferrux.app.ui.NativeMissingScreen
import dev.ferrux.app.ui.TerminalTheme
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<FerruxService?>(null)
    private var isBound = false

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

        val loadError = FerruxAppNative.loadError
        val settings = FerruxSettings.get(this)
        setContent {
            val themeId by settings.theme.collectAsStateWithLifecycle()
            val theme = remember(themeId) { TerminalTheme.byId(themeId) }
            LaunchedEffect(theme) { applyWindowColors(theme) }
            FerruxTheme(theme) {
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

    /** System bar icons and the window background follow the terminal theme. */
    private fun applyWindowColors(theme: TerminalTheme) {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (theme.dark) {
            SystemBarStyle.dark(transparent)
        } else {
            SystemBarStyle.light(transparent, transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
        window.setBackgroundDrawable(ColorDrawable(theme.background.toArgb()))
    }

    override fun onStart() {
        super.onStart()
        // Bound from the first start until the Activity is destroyed, not only while it
        // is visible: unbinding in onStop dropped the terminal view (and with it the IME
        // connection and zoom) every time the user switched apps.
        if (FerruxAppNative.loadError == null && !isBound) {
            isBound = bindService(FerruxService.bindIntent(this), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onDestroy() {
        if (isBound) unbindService(connection)
        isBound = false
        service = null
        super.onDestroy()
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
