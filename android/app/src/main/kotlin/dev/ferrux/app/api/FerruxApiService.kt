// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.app.api

import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import dev.ferrux.api.AttachResult
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.FileStat
import dev.ferrux.api.ApiErrors
import dev.ferrux.api.IFerruxApi
import dev.ferrux.api.IProcessCallback
import dev.ferrux.api.SessionInfo
import dev.ferrux.api.SpawnRequest
import dev.ferrux.api.SpawnResult
import dev.ferrux.app.FerruxAppNative

/**
 * Ferrux API v1 (docs/API.md): lets FerraStack apps run programs and manage files in
 * Ferrux. Exported, guarded by `${applicationId}.permission.API`
 * (`signature|knownSigner`); every call re-checks the permission of the calling UID.
 *
 * The logic lives in Rust (crates/ferrux-host via [ApiNative]); this class checks
 * permissions and ownership and converts between AIDL parcelables and JNI values.
 */
class FerruxApiService : Service() {
    private val permission by lazy { "$packageName.permission.API" }

    override fun onBind(intent: Intent): IBinder = binder

    private fun enforce(): Int {
        if (checkCallingPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("Ferrux API requires $permission")
        }
        if (FerruxAppNative.loadError != null) {
            throw ApiErrors.exception(IFerruxApi.ERR_UNSUPPORTED, "libferrux.so is not available")
        }
        return Binder.getCallingUid()
    }

    private fun pfd(fd: Long): ParcelFileDescriptor? =
        if (fd >= 0) ParcelFileDescriptor.adoptFd(fd.toInt()) else null

    private fun parseStat(record: String): FileStat {
        val f = record.split('\u0000')
        return FileStat().apply {
            type = f[0].toInt()
            size = f[1].toLong()
            modifiedMillis = f[2].toLong()
            mode = f[3].toInt()
            path = f[4]
            name = f[5]
            symlinkTarget = f[6].ifEmpty { null }
        }
    }

    private val binder = object : IFerruxApi.Stub() {
        override fun getApiVersion(): Int {
            enforce()
            return IFerruxApi.API_VERSION
        }

        override fun getInfo(): FerruxInfo {
            enforce()
            val native = ApiNative.info()
            @Suppress("DEPRECATION")
            val pkg = packageManager.getPackageInfo(packageName, 0)
            return FerruxInfo().apply {
                apiVersion = IFerruxApi.API_VERSION
                versionName = pkg.versionName ?: ""
                versionCode = pkg.longVersionCode
                packageName = this@FerruxApiService.packageName
                prefix = native[0]
                home = native[1]
                tmpDir = native[2]
                bootstrapState = if (native[3] == "1") {
                    FerruxInfo.BOOTSTRAP_READY
                } else {
                    FerruxInfo.BOOTSTRAP_NOT_INSTALLED
                }
                capabilities = CAPABILITIES
                androidApi = Build.VERSION.SDK_INT
                abi = Build.SUPPORTED_ABIS.firstOrNull() ?: ""
                liveProcesses = ApiNative.liveCount()
                clientProcessLimit = ApiNative.ownerLimit()
            }
        }

        override fun spawn(request: SpawnRequest?, callback: IProcessCallback?): SpawnResult {
            val uid = enforce()
            requireNotNull(request) { "request is null" }
            requireNotNull(callback) { "callback is required" }
            val pty = when (request.mode) {
                SpawnRequest.MODE_PIPES -> false
                SpawnRequest.MODE_PTY -> true
                else -> throw ApiErrors.exception(
                    IFerruxApi.ERR_INVALID_ARGUMENT, "unknown mode ${request.mode}",
                )
            }
            if (request.shared) {
                throw ApiErrors.exception(IFerruxApi.ERR_UNSUPPORTED, "shared sessions are not available yet")
            }
            val owner = ApiRegistry.ownerFor(callback, uid)
            val r = ApiNative.spawn(
                request.argv ?: emptyArray(),
                request.env ?: emptyArray(),
                request.clearEnv,
                request.cwd,
                pty,
                request.mergeStderr,
                request.rows,
                request.cols,
                owner.id,
            )
            // A failed foreground start doesn't fail the spawn: the client is bound,
            // so Ferrux shares its priority while it is visible.
            ApiRegistry.refresh(this@FerruxApiService)
            return SpawnResult().apply {
                handle = r[0]
                pid = r[1].toInt()
                stdin = pfd(r[2])
                stdout = pfd(r[3])
                stderr = pfd(r[4])
                this.pty = pfd(r[5])
                ptyIsSocket = false
            }
        }

        override fun resize(handle: Long, rows: Int, cols: Int) {
            ApiRegistry.checkHandle(handle, enforce())
            ApiNative.resize(handle, rows, cols)
        }

        override fun signal(handle: Long, signo: Int) {
            ApiRegistry.checkHandle(handle, enforce())
            ApiNative.signal(handle, signo)
        }

        override fun kill(handle: Long) {
            ApiRegistry.checkHandle(handle, enforce())
            ApiNative.kill(handle)
        }

        override fun close(handle: Long) {
            ApiRegistry.checkHandle(handle, enforce())
            ApiNative.close(handle)
            ApiRegistry.refresh(null)
        }

        override fun listSessions(): Array<SessionInfo> {
            enforce()
            throw ApiErrors.exception(IFerruxApi.ERR_UNSUPPORTED, "shared sessions are not available yet")
        }

        override fun attachSession(
            sessionId: Long,
            rows: Int,
            cols: Int,
            callback: IProcessCallback?,
        ): AttachResult {
            enforce()
            throw ApiErrors.exception(IFerruxApi.ERR_UNSUPPORTED, "shared sessions are not available yet")
        }

        override fun stat(path: String?): FileStat {
            enforce()
            return parseStat(ApiNative.stat(requireNotNull(path)))
        }

        override fun list(path: String?, offset: Int, limit: Int): Array<FileStat> {
            enforce()
            return ApiNative.list(requireNotNull(path), offset, limit).map(::parseStat).toTypedArray()
        }

        override fun openFile(path: String?, mode: String?): ParcelFileDescriptor {
            enforce()
            val fd = ApiNative.openFile(requireNotNull(path), mode ?: "r")
            return ParcelFileDescriptor.adoptFd(fd)
        }

        override fun mkdirs(path: String?) {
            enforce()
            ApiNative.mkdirs(requireNotNull(path))
        }

        override fun rename(from: String?, to: String?) {
            enforce()
            ApiNative.rename(requireNotNull(from), requireNotNull(to))
        }

        override fun delete(path: String?, recursive: Boolean) {
            enforce()
            ApiNative.delete(requireNotNull(path), recursive)
        }

        override fun acquireKeepAlive(token: IBinder?, reason: String?) {
            val uid = enforce()
            val ok = ApiRegistry.acquireKeepAlive(
                this@FerruxApiService, requireNotNull(token), uid, reason ?: "",
            )
            if (!ok) {
                throw ApiErrors.exception(
                    IFerruxApi.ERR_KEEPALIVE_DENIED,
                    "Android did not allow Ferrux to start its foreground service; open Ferrux once",
                )
            }
        }

        override fun releaseKeepAlive(token: IBinder?) {
            enforce()
            ApiRegistry.releaseKeepAlive(requireNotNull(token))
        }
    }

    private companion object {
        // "attach" arrives with shared sessions.
        val CAPABILITIES = arrayOf("pipes", "pty", "pty-master-fd", "files", "keepalive")
    }
}
