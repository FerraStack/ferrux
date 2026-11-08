// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import dev.ferrux.api.IFerruxApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Whether Ferrux can be used, without binding to it (docs/API.md §8). */
sealed class FerruxStatus {
    /** Ferrux isn't installed (or isn't visible: the client lacks `<queries>`). */
    data object NotInstalled : FerruxStatus()

    /** The user disabled the Ferrux app. */
    data object Disabled : FerruxStatus()

    /** Ferrux is too old (or has no API service at all: [installed] = 0). */
    data class Outdated(val installed: Int, val required: Int) : FerruxStatus()

    /** This app isn't signed by a key Ferrux trusts. */
    data object NotPermitted : FerruxStatus()

    data class Ready(val apiVersion: Int) : FerruxStatus()
}

/** Entry point of the Ferrux client library. */
object FerruxClient {
    /** The official Ferrux package. */
    const val DEFAULT_PACKAGE = "dev.ferrux"
    const val ACTION_BIND = "dev.ferrux.api.action.BIND"
    const val META_API_VERSION = "dev.ferrux.api.VERSION"

    /** The permission guarding the service of the Ferrux build [ferruxPackage]. */
    fun permission(ferruxPackage: String = DEFAULT_PACKAGE) = "$ferruxPackage.permission.API"

    fun bindIntent(ferruxPackage: String = DEFAULT_PACKAGE): Intent =
        Intent(ACTION_BIND).setPackage(ferruxPackage)

    /** Checks installation, API version and permission without binding. */
    fun status(
        context: Context,
        minApi: Int = 1,
        ferruxPackage: String = DEFAULT_PACKAGE,
    ): FerruxStatus {
        val pm = context.packageManager
        val app = try {
            @Suppress("DEPRECATION")
            pm.getApplicationInfo(ferruxPackage, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            return FerruxStatus.NotInstalled
        }
        if (!app.enabled) return FerruxStatus.Disabled
        @Suppress("DEPRECATION")
        val service = pm.resolveService(bindIntent(ferruxPackage), PackageManager.GET_META_DATA)
            ?: return FerruxStatus.Outdated(0, minApi)
        val version = service.serviceInfo.metaData?.getInt(META_API_VERSION, 0) ?: 0
        if (version < minApi) return FerruxStatus.Outdated(version, minApi)
        if (context.checkSelfPermission(permission(ferruxPackage)) != PackageManager.PERMISSION_GRANTED) {
            return FerruxStatus.NotPermitted
        }
        return FerruxStatus.Ready(version)
    }

    /**
     * Binds Ferrux's API service and returns once it is connected and its version
     * checked. Call [FerruxConnection.close] to unbind.
     *
     * Bind while your app is in the foreground: Ferrux then shares your priority, and
     * may start its foreground service for [FerruxConnection.keepAlive].
     *
     * @throws FerruxException.NotInstalled, [FerruxException.NotPermitted],
     *   [FerruxException.Outdated] or [FerruxException.Disconnected]
     */
    suspend fun connect(
        context: Context,
        minApi: Int = 1,
        ferruxPackage: String = DEFAULT_PACKAGE,
    ): FerruxConnection {
        val appContext = context.applicationContext
        val binder = CompletableDeferred<IBinder>()
        val connection = FerruxConnection(appContext)
        val serviceConnection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                if (!binder.complete(service)) connection.onReconnected(service)
            }

            override fun onServiceDisconnected(name: ComponentName) = connection.onDisconnected()

            override fun onBindingDied(name: ComponentName) {
                // Before the first connection this would otherwise leave connect() waiting forever.
                if (!binder.completeExceptionally(FerruxException.Disconnected())) connection.onDisconnected()
            }

            override fun onNullBinding(name: ComponentName) {
                binder.completeExceptionally(FerruxException.Outdated(0, minApi))
            }
        }
        val flags = Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT or
            Context.BIND_INCLUDE_CAPABILITIES
        val bound = try {
            appContext.bindService(bindIntent(ferruxPackage), serviceConnection, flags)
        } catch (e: SecurityException) {
            throw FerruxException.NotPermitted(e.message)
        }
        if (!bound) {
            appContext.unbindService(serviceConnection)
            throw when (status(appContext, minApi, ferruxPackage)) {
                is FerruxStatus.Outdated -> FerruxException.Outdated(0, minApi)
                FerruxStatus.NotPermitted -> FerruxException.NotPermitted(null)
                else -> FerruxException.NotInstalled()
            }
        }
        try {
            val service = binder.await()
            connection.attach(serviceConnection, IFerruxApi.Stub.asInterface(service))
            val version = withContext(Dispatchers.IO) {
                FerruxException.wrap { connection.api.apiVersion }
            }
            if (version < minApi) throw FerruxException.Outdated(version, minApi)
            connection.info = withContext(Dispatchers.IO) { FerruxException.wrap { connection.api.info } }
            return connection
        } catch (t: Throwable) {
            runCatching { appContext.unbindService(serviceConnection) }
            throw t
        }
    }
}
