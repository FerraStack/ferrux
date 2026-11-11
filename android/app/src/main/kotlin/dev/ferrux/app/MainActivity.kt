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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.ferrux.app.ui.BootstrapScreen
import dev.ferrux.app.ui.FerruxScreen
import dev.ferrux.app.ui.FerruxTheme
import dev.ferrux.app.ui.NativeMissingScreen
import java.io.IOException

class MainActivity : ComponentActivity() {

    private var service by mutableStateOf<FerruxService?>(null)
    private var isBound = false

    /** False while the first-run bootstrap screen is shown. */
    private var setupDone by mutableStateOf(true)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val bound = (binder as FerruxService.LocalBinder).service
            service = bound
            // Running sessions (e.g. after "Use Android's shell") win over setup.
            if (bound.sessions.sessions.value.isNotEmpty()) setupDone = true
            // Like Termux: opening the app always lands in a running shell.
            if (setupDone) ensureSession()
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
        val installer = (application as FerruxApplication).bootstrap
        setupDone = installer == null || !installer.available ||
            installer.state.value == BootstrapState.Installed
        setContent {
            FerruxTheme {
                if (loadError != null) {
                    NativeMissingScreen(loadError)
                } else if (!setupDone && installer != null) {
                    val state by installer.state.collectAsStateWithLifecycle()
                    LaunchedEffect(state) {
                        if (state == BootstrapState.Installed) finishSetup()
                    }
                    BootstrapScreen(
                        state = state,
                        onInstall = installer::start,
                        onUseSystemShell = ::finishSetup,
                    )
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

    /** Leaves the setup screen for the terminal (bash, or Android's sh if skipped). */
    private fun finishSetup() {
        setupDone = true
        ensureSession()
    }

    private fun ensureSession() {
        val svc = service ?: return
        if (svc.sessions.sessions.value.isEmpty()) newSession()
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
