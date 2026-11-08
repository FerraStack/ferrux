// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.api

import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.os.Process
import android.os.RemoteException
import android.util.Log
import dev.ferrux.api.ApiErrors
import dev.ferrux.api.IFerruxApi
import dev.ferrux.api.IProcessCallback
import dev.ferrux.app.FerruxApplication
import dev.ferrux.app.FerruxService
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide state of the API service (it outlives individual `FerruxApiService`
 * instances): clients ("owners", one per `IProcessCallback` binder), keep-alive tokens,
 * and the thread that delivers exits. See docs/API.md §6.6–6.7.
 */
internal object ApiRegistry {
    class Owner(val id: Long, val uid: Int, val callback: IProcessCallback) {
        lateinit var death: IBinder.DeathRecipient
    }

    private class KeepAlive(val uid: Int, val reason: String, val death: IBinder.DeathRecipient)

    private val owners = ConcurrentHashMap<IBinder, Owner>()
    private val ownersById = ConcurrentHashMap<Long, Owner>()
    private val nextOwner = AtomicLong(1)
    private val keepAlives = ConcurrentHashMap<IBinder, KeepAlive>()

    /** What [FerruxService] needs to stay in the foreground for. */
    data class Holds(val keepAlives: Int, val processes: Int) {
        val any: Boolean get() = keepAlives > 0 || processes > 0
    }

    private val _holds = MutableStateFlow(Holds(0, 0))
    val holds: StateFlow<Holds> = _holds.asStateFlow()

    @Volatile private var dispatcher: Thread? = null

    /**
     * Rejects binders that live in Ferrux's own process (e.g. a client passing back
     * Ferrux's `IFerruxApi` binder as its callback). `linkToDeath` is a no-op for them,
     * so cleanup would never run, and calls on them would execute inside Ferrux.
     */
    private fun requireRemote(binder: IBinder, what: String) {
        if (binder is Binder || Binder.getCallingPid() == Process.myPid()) {
            throw SecurityException("$what must be a binder of the calling app")
        }
    }

    /** The owner record for [callback], created (and linked to its death) on first use. */
    @Synchronized
    fun ownerFor(callback: IProcessCallback, uid: Int): Owner {
        val binder = callback.asBinder()
        requireRemote(binder, "callback")
        owners[binder]?.let { return it }
        val owner = Owner(nextOwner.getAndIncrement(), uid, callback)
        owner.death = IBinder.DeathRecipient { ownerDied(binder) }
        // Registered before linkToDeath: if the client dies in between, ownerDied
        // finds the record and cleans up.
        owners[binder] = owner
        ownersById[owner.id] = owner
        try {
            binder.linkToDeath(owner.death, 0)
        } catch (e: RemoteException) {
            owners.remove(binder)
            ownersById.remove(owner.id)
            throw IllegalStateException("client already died", e)
        }
        ensureDispatcher()
        return owner
    }

    @Synchronized
    private fun ownerDied(binder: IBinder) {
        val owner = owners.remove(binder) ?: return
        ownersById.remove(owner.id)
        val n = ApiNative.closeOwner(owner.id)
        Log.i(FerruxApplication.TAG, "api client uid ${owner.uid} died; closed $n process(es)")
        refresh(null)
    }

    /**
     * Every client has unbound (`Service.onUnbind`): hang up everything spawned
     * through the API and drop all keep-alives (issue #39). Without this, a client
     * that unbinds but keeps its process alive would leave its processes running.
     */
    @Synchronized
    fun closeAll() {
        for ((binder, owner) in owners.entries.toList()) {
            runCatching { binder.unlinkToDeath(owner.death, 0) }
            val n = ApiNative.closeOwner(owner.id)
            if (n > 0) Log.i(FerruxApplication.TAG, "api clients unbound; closed $n process(es) of uid ${owner.uid}")
        }
        owners.clear()
        ownersById.clear()
        for ((token, ka) in keepAlives.entries.toList()) {
            runCatching { token.unlinkToDeath(ka.death, 0) }
        }
        keepAlives.clear()
        refresh(null)
    }

    /** Throws `ERR_NO_SUCH_HANDLE` unless [handle] belongs to a client with [uid]. */
    fun checkHandle(handle: Long, uid: Int) {
        val owner = ownersById[ApiNative.ownerOf(handle)]
        if (owner == null || owner.uid != uid) {
            throw ApiErrors.exception(IFerruxApi.ERR_NO_SUCH_HANDLE, "no such handle $handle")
        }
    }

    /** Registers a keep-alive token; false if Android refused the foreground service. */
    @Synchronized
    fun acquireKeepAlive(context: Context, token: IBinder, uid: Int, reason: String): Boolean {
        requireRemote(token, "keep-alive token")
        if (keepAlives.containsKey(token)) return true
        val death = IBinder.DeathRecipient { releaseKeepAlive(token) }
        keepAlives[token] = KeepAlive(uid, reason, death)
        try {
            token.linkToDeath(death, 0)
        } catch (e: RemoteException) {
            keepAlives.remove(token)
            throw IllegalStateException("client already died", e)
        }
        Log.i(FerruxApplication.TAG, "keep-alive from uid $uid: $reason")
        if (!refresh(context)) {
            releaseKeepAlive(token)
            return false
        }
        return true
    }

    @Synchronized
    fun releaseKeepAlive(token: IBinder) {
        val ka = keepAlives.remove(token) ?: return
        runCatching { token.unlinkToDeath(ka.death, 0) }
        refresh(null)
    }

    /**
     * Recomputes [holds]. With a [context], and when something now needs Ferrux in
     * the foreground, (re)starts [FerruxService] as a foreground service; returns false
     * if Android refused (background start restrictions).
     */
    fun refresh(context: Context?): Boolean {
        val holds = Holds(keepAlives.size, ApiNative.liveCount())
        _holds.value = holds
        if (context == null || !holds.any) return true
        val identity = Binder.clearCallingIdentity()
        return try {
            context.startForegroundService(Intent(context, FerruxService::class.java))
            true
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException.
            Log.w(FerruxApplication.TAG, "cannot start the foreground service: $e")
            false
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    @Synchronized
    private fun ensureDispatcher() {
        if (dispatcher != null) return
        dispatcher = Thread(::dispatchExits, "ferrux-api-exits").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Delivers exits to the owning clients' callbacks. Nothing a client does may
     * kill this thread or, through an uncaught exception, the whole Ferrux process
     * with everyone's sessions: every delivery and every round is guarded.
     */
    private fun dispatchExits() {
        while (true) {
            try {
                val exits = ApiNative.awaitExits(DISPATCH_TIMEOUT_MS)
                for (i in exits.indices step 4) {
                    val handle = exits[i]
                    val owner = ownersById[exits[i + 1]] ?: continue
                    try {
                        owner.callback.onExit(handle, exits[i + 2].toInt(), exits[i + 3].toInt())
                    } catch (t: Throwable) {
                        Log.w(FerruxApplication.TAG, "onExit($handle) not delivered: $t")
                    }
                }
                // Also picks up processes reaped after their handle was closed.
                refresh(null)
            } catch (t: Throwable) {
                Log.e(FerruxApplication.TAG, "exit dispatcher error", t)
                Thread.sleep(DISPATCH_TIMEOUT_MS.toLong())
            }
        }
    }

    private const val DISPATCH_TIMEOUT_MS = 1000
}
