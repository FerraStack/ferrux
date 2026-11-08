// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class OwnershipTest {
    private val io = Executors.newCachedThreadPool().asCoroutineDispatcher()

    @Test
    fun resultIsReturnedWhenNotCancelled() = runBlocking {
        val released = AtomicInteger()
        val r = completeOrRelease(io, { 42 }) { released.incrementAndGet() }
        assertEquals(42, r)
        assertEquals(0, released.get())
    }

    /** A spawn that completes after the caller was cancelled must not leak its result. */
    @Test
    fun resultIsReleasedWhenTheCallerWasCancelled() = runBlocking {
        val started = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val released = AtomicInteger()
        val job = async {
            completeOrRelease(io, {
                started.countDown()
                finish.await(5, TimeUnit.SECONDS) // the "Binder call" in flight
                7
            }) { released.addAndGet(it) }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        job.cancel()
        finish.countDown()
        try {
            job.await()
            fail("expected cancellation")
        } catch (_: CancellationException) {
        }
        assertEquals("the created resource is released", 7, released.get())
    }

    @Test
    fun errorsPropagateWithoutRelease() = runBlocking {
        val released = AtomicInteger()
        try {
            completeOrRelease<Int>(io, { throw IllegalStateException("E2: nope") }) { released.incrementAndGet() }
            fail("expected an exception")
        } catch (e: IllegalStateException) {
            assertEquals("E2: nope", e.message)
        }
        assertEquals(0, released.get())
    }

    @Test
    fun linkTargets() {
        assertEquals("/h/a/b.txt", resolveLinkTarget("/h/a/link", "b.txt"))
        assertEquals("/h/a/../c", resolveLinkTarget("/h/a/link", "../c"))
        assertEquals("/abs/t", resolveLinkTarget("/h/a/link", "/abs/t"))
    }
}
