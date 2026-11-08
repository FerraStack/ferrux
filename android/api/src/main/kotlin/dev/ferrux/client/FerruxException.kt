// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.os.DeadObjectException
import android.os.RemoteException
import dev.ferrux.api.ApiErrors
import dev.ferrux.api.IFerruxApi

/** Everything the client library throws (docs/API.md §7). */
sealed class FerruxException(message: String?, cause: Throwable? = null) : Exception(message, cause) {
    class NotInstalled : FerruxException("Ferrux is not installed")

    class NotPermitted(detail: String?) :
        FerruxException("This app is not allowed to use Ferrux" + (detail?.let { ": $it" } ?: ""))

    class Outdated(val installed: Int, val required: Int) :
        FerruxException("Ferrux API $installed is installed; $required is required")

    /** Ferrux died, was updated or force-stopped, or the connection was closed. */
    class Disconnected(cause: Throwable? = null) : FerruxException("Ferrux disconnected", cause)

    /** The method doesn't exist in the installed Ferrux (check its API version first). */
    class Unsupported(detail: String?, cause: Throwable? = null) : FerruxException(detail, cause)

    /** An error reported by Ferrux; [code] is one of the `IFerruxApi.ERR_*` constants. */
    class Api(val code: Int, message: String?) : FerruxException(message) {
        val isNotFound get() = code == IFerruxApi.ERR_NOT_FOUND
        val isOutsideHome get() = code == IFerruxApi.ERR_OUTSIDE_HOME
        val isBootstrapRequired get() = code == IFerruxApi.ERR_BOOTSTRAP_REQUIRED
        val isLimit get() = code == IFerruxApi.ERR_LIMIT
    }

    companion object {
        fun from(t: Throwable): FerruxException {
            ApiErrors.parse(t)?.let { (code, text) ->
                return if (code == IFerruxApi.ERR_UNSUPPORTED) Unsupported(text, t) else Api(code, text)
            }
            return fromOther(t)
        }

        private fun fromOther(t: Throwable): FerruxException = when (t) {
            is FerruxException -> t
            is SecurityException -> NotPermitted(t.message)
            is UnsupportedOperationException -> Unsupported(t.message, t)
            is DeadObjectException -> Disconnected(t)
            is RemoteException -> Disconnected(t)
            is IllegalArgumentException -> Api(IFerruxApi.ERR_INVALID_ARGUMENT, t.message)
            else -> Api(IFerruxApi.ERR_IO, t.toString())
        }

        /** Runs a remote call, mapping exceptions to [FerruxException]. */
        inline fun <T> wrap(block: () -> T): T = try {
            block()
        } catch (t: Throwable) {
            if (t is Error) throw t
            throw from(t)
        }
    }
}
