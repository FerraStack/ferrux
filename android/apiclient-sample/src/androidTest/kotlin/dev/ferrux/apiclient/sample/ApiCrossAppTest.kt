// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.apiclient.sample

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.ferrux.api.FerruxInfo
import dev.ferrux.api.FileStat
import dev.ferrux.client.FerruxClient
import dev.ferrux.client.FerruxConnection
import dev.ferrux.client.FerruxException
import dev.ferrux.client.FerruxStatus
import dev.ferrux.client.ProcessSpec
import dev.ferrux.client.PtySize
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Cross-app test of the Ferrux API, run by scripts/ci-api.sh against an installed
 * Ferrux debug build. The instrumentation argument `expect` is `granted` (default:
 * this APK is signed with a key Ferrux trusts) or `denied` (re-signed with a key it
 * doesn't); each test only runs in its mode.
 */
@RunWith(AndroidJUnit4::class)
class ApiCrossAppTest {
    private val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val expect = InstrumentationRegistry.getArguments().getString("expect", "granted")
    private var conn: FerruxConnection? = null

    private fun granted(): FerruxConnection {
        assumeTrue("expect=$expect", expect == "granted")
        return runBlocking { withTimeout(TIMEOUT) { FerruxClient.connect(context) } }.also { conn = it }
    }

    @Before
    fun logMode() {
        android.util.Log.i(TAG, "expect=$expect")
    }

    @After
    fun closeConnection() {
        conn?.close()
    }

    @Test
    fun statusAndInfo() {
        val c = granted()
        assertEquals(FerruxStatus.Ready(1), FerruxClient.status(context))
        val info = c.info
        assertEquals(1, info.apiVersion)
        assertEquals("dev.ferrux", info.packageName)
        assertTrue(info.home, info.home.endsWith("/files/home"))
        assertTrue(info.prefix, info.prefix.endsWith("/files/usr"))
        assertEquals(FerruxInfo.BOOTSTRAP_NOT_INSTALLED, info.bootstrapState)
        assertTrue(info.capabilities.toList().containsAll(listOf("pipes", "pty", "pty-master-fd", "files")))
        assertEquals(16, info.clientProcessLimit)
    }

    @Test
    fun pipesEcho() = runBlocking {
        val c = granted()
        val r = withTimeout(TIMEOUT) { c.exec(listOf("/system/bin/sh", "-c", "echo api-ok; echo err >&2")) }
        assertEquals("api-ok\n", r.stdout)
        assertEquals("err\n", r.stderr)
        assertEquals(0, r.status.exitCode)
        assertEquals(0, r.status.signal)
    }

    /** Closing our stdin must reach the program, so Ferrux must not keep a copy. */
    @Test
    fun pipesStdinEofAndExitCode() = runBlocking {
        val c = granted()
        val r = withTimeout(TIMEOUT) {
            c.exec(listOf("sh", "-c", "cat; exit 3"), stdin = "through cat".toByteArray())
        }
        assertEquals("through cat", r.stdout)
        assertEquals(3, r.status.exitCode)
    }

    @Test
    fun spawnErrorsAreTyped() = runBlocking {
        val c = granted()
        val e = try {
            c.exec(listOf("definitely-not-a-program"))
            null
        } catch (e: FerruxException.Api) {
            e
        }
        assertNotNull("spawning a missing program must fail", e)
        assertTrue(e!!.message, e.isBootstrapRequired || e.isNotFound)
    }

    @Test
    fun ptyEchoResizeAndKill() = runBlocking {
        val c = granted()
        val p = c.spawn(ProcessSpec(listOf("/system/bin/sh"), pty = PtySize(24, 80)))
        try {
            assertTrue(p.pty != null && !p.ptyIsSocket)
            val out = Collector(p.ptyInput()!!)
            val input = p.ptyOutput()!!
            input.write("echo h\"\"i\n".toByteArray())
            out.await(Regex("""(?m)^hi\r?$"""))

            p.resize(10, 33)
            input.write("stty size\n".toByteArray())
            out.await(Regex("""(?m)^10 33\r?$"""))

            p.kill()
            val status = withTimeout(TIMEOUT) { p.await() }
            assertEquals(9, status.signal)
            assertEquals(-1, status.exitCode)
        } finally {
            p.close()
        }
    }

    @Test
    fun filesConfinedToHome() = runBlocking {
        val c = granted()
        val f = c.files
        f.mkdirs("api-test/sub")
        f.writeAtomically("api-test/hello.txt", "hello from the sample".toByteArray())
        assertEquals("hello from the sample", f.readText("api-test/hello.txt"))
        val names = f.list("api-test").map { it.name to it.type }
        assertEquals(listOf("hello.txt" to FileStat.TYPE_FILE, "sub" to FileStat.TYPE_DIR), names)
        assertEquals(21L, f.stat("api-test/hello.txt").size)

        // A program in Ferrux sees the same file.
        val r = c.exec(listOf("cat", "api-test/hello.txt"), cwd = ".")
        assertEquals("hello from the sample", r.stdout)

        for (outside in listOf("/data/data/dev.ferrux/shared_prefs", "../usr", "/etc/hosts")) {
            try {
                f.stat(outside)
                fail("$outside must be refused")
            } catch (e: FerruxException.Api) {
                assertTrue("$outside: ${e.message}", e.isOutsideHome || e.isNotFound)
            }
        }
        f.delete("api-test", recursive = true)
    }

    /** Security review of #27: cancelling exec() returns promptly and leaves nothing running. */
    @Test
    fun cancelledExecDoesNotLeak() = runBlocking {
        val c = granted()
        val before = c.refreshInfo().liveProcesses
        val started = System.currentTimeMillis()
        val result = withTimeoutOrNull(1_500) { c.exec(listOf("sh", "-c", "sleep 60")) }
        assertEquals(null, result)
        assertTrue("cancellation took too long", System.currentTimeMillis() - started < 10_000)
        // Ferrux hung the process up: the live count returns to where it was.
        val deadline = System.currentTimeMillis() + TIMEOUT
        while (c.refreshInfo().liveProcesses > before) {
            if (System.currentTimeMillis() > deadline) fail("the cancelled process is still running")
            Thread.sleep(200)
        }
    }

    /** Security review of #27: writeAtomically keeps +x and writes through symlinks. */
    @Test
    fun writeAtomicallyKeepsModeAndSymlinks() = runBlocking {
        val c = granted()
        val f = c.files
        f.mkdirs("api-atomic")
        f.writeText("api-atomic/run.sh", "#!/system/bin/sh\necho one\n")
        f.chmod("api-atomic/run.sh", 0x1ED) // 0755
        f.writeAtomically("api-atomic/run.sh", "#!/system/bin/sh\necho two\n".toByteArray())
        assertEquals(0x1ED, f.stat("api-atomic/run.sh").mode)
        assertEquals("two\n", c.exec(listOf("sh", "api-atomic/run.sh")).stdout)

        c.exec(listOf("ln", "-s", "run.sh", "api-atomic/link"))
        f.writeAtomically("api-atomic/link", "#!/system/bin/sh\necho three\n".toByteArray())
        assertEquals(FileStat.TYPE_SYMLINK, f.stat("api-atomic/link").type)
        assertEquals("three\n", c.exec(listOf("sh", "api-atomic/run.sh")).stdout)
        assertEquals(0x1ED, f.stat("api-atomic/run.sh").mode)
        f.delete("api-atomic", recursive = true)
    }

    /** Security review of #27: opening a FIFO fails fast instead of blocking a Binder thread. */
    @Test
    fun fifoIsRefusedWithoutBlocking() = runBlocking {
        val c = granted()
        c.exec(listOf("mkfifo", "api-fifo"))
        val started = System.currentTimeMillis()
        try {
            c.files.open("api-fifo", "r").close()
            fail("opening a FIFO must be refused")
        } catch (e: FerruxException.Api) {
            assertTrue(e.message, System.currentTimeMillis() - started < 5_000)
        } finally {
            c.files.delete("api-fifo")
        }
    }

    /** Holds a keep-alive for a while; ci-api.sh checks Ferrux's service is in the foreground meanwhile. */
    @Test
    fun keepAliveHold() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("keepalive") == "1")
        val c = granted()
        c.keepAlive("ci keep-alive check").use {
            android.util.Log.i(TAG, "keep-alive acquired")
            Thread.sleep(KEEPALIVE_HOLD_MS)
        }
    }

    @Test
    fun deniedForOtherSigners() {
        assumeTrue("expect=$expect", expect == "denied")
        assertEquals(FerruxStatus.NotPermitted, FerruxClient.status(context))
        try {
            runBlocking { withTimeout(TIMEOUT) { FerruxClient.connect(context) } }.close()
            fail("connect must be refused for a client signed with an untrusted key")
        } catch (e: FerruxException.NotPermitted) {
            android.util.Log.i(TAG, "denied as expected: ${e.message}")
        }
    }

    /** Accumulates everything read from a stream on a background thread. */
    private class Collector(input: InputStream) {
        private val buf = StringBuffer()

        init {
            Thread {
                val b = ByteArray(4096)
                try {
                    while (true) {
                        val n = input.read(b)
                        if (n < 0) break
                        buf.append(String(b, 0, n))
                    }
                } catch (_: java.io.IOException) {
                    // EIO when the PTY is hung up.
                }
            }.apply { isDaemon = true }.start()
        }

        fun await(re: Regex) {
            val deadline = System.currentTimeMillis() + TIMEOUT
            while (!re.containsMatchIn(buf)) {
                if (System.currentTimeMillis() > deadline) fail("no ${re.pattern} in output: $buf")
                Thread.sleep(50)
            }
        }
    }

    private companion object {
        const val TAG = "FerruxApiTest"
        const val TIMEOUT = 20_000L
        const val KEEPALIVE_HOLD_MS = 30_000L
    }
}
