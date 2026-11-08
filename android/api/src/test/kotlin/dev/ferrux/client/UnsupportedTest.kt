// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import android.os.Binder
import android.os.DeadObjectException
import android.os.Parcel
import android.os.RemoteException
import dev.ferrux.api.ApiErrors
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.IFerruxApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Issue #40: a newer client talking to an older Ferrux degrades instead of failing or disconnecting. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UnsupportedTest {
    /** A Ferrux that knows none of the transactions (as an old one wouldn't know chmod). */
    private class OldFerrux : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean = false
    }

    /**
     * What an unknown transaction really does with the generated Java proxy: nothing
     * fails. This is why ferrux-client gates newer methods instead of relying on an
     * exception (docs/API.md §4).
     */
    @Test
    fun unknownTransactionsAreSilentSoCallsMustBeGated() {
        val api = IFerruxApi.Stub.asInterface(OldFerrux())
        api.chmod("x", 0x1ED) // "succeeds" although nothing handled it
        assertEquals(0, api.apiVersion) // defaults come back
        assertEquals(null, api.info)
    }

    @Test
    fun requireApiRefusesNewerMethods() {
        val conn = FerruxConnection(RuntimeEnvironment.getApplication())
        conn.info = FerruxInfo().apply { apiVersion = 1 }
        conn.requireApi(1, "chmod")
        try {
            conn.requireApi(2, "watch")
            fail("API 2 methods must be refused by an API 1 Ferrux")
        } catch (e: FerruxException.Unsupported) {
            assertTrue(e.message, e.message!!.contains("API 2"))
        }
    }

    @Test
    fun exceptionMapping() {
        fun map(t: Throwable) = FerruxException.from(t)
        assertTrue(map(DeadObjectException()) is FerruxException.Disconnected)
        assertTrue(map(RemoteException("transport broke")) is FerruxException.Disconnected)
        assertTrue(map(RemoteException("Method chmod is unimplemented.")) is FerruxException.Unsupported)
        assertTrue(map(SecurityException("nope")) is FerruxException.NotPermitted)
        val api = map(ApiErrors.exception(IFerruxApi.ERR_OUTSIDE_HOME, "/etc: outside"))
        assertTrue(api is FerruxException.Api && api.isOutsideHome)
        assertEquals("/etc: outside", api.message)
        assertTrue(map(ApiErrors.exception(IFerruxApi.ERR_UNSUPPORTED, "no")) is FerruxException.Unsupported)
    }

    @Test
    fun capabilityGateAvoidsTheCall() = runBlocking {
        val conn = FerruxConnection(RuntimeEnvironment.getApplication())
        conn.info = FerruxInfo().apply {
            apiVersion = 1
            capabilities = arrayOf("pipes", "pty", "files")
        }
        assertEquals(false, conn.supports(FerruxConnection.CAPABILITY_ATTACH))
        // No binder is attached: reaching the remote call would fail differently.
        try {
            conn.sessions()
            fail("sessions() needs the attach capability")
        } catch (_: FerruxException.Unsupported) {
        }
        try {
            conn.attach(1, 24, 80)
            fail("attach() needs the attach capability")
        } catch (_: FerruxException.Unsupported) {
        }
    }
}
