// SPDX-License-Identifier: Apache-2.0
package dev.ferrux.client

import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

private object Unset

/**
 * Runs a blocking call that creates something the caller must own (a process, a file
 * descriptor, a keep-alive) without leaking it when the caller is cancelled.
 *
 * The call itself always completes ([NonCancellable]): a Binder call can't be
 * interrupted anyway. If the caller was cancelled meanwhile, `withContext` would
 * silently drop the result, so [release] is run on it instead and the cancellation
 * is rethrown.
 */
internal suspend fun <T> completeOrRelease(
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    block: () -> T,
    release: (T) -> Unit,
): T {
    val result = AtomicReference<Any?>(Unset)
    try {
        withContext(NonCancellable + dispatcher) { result.set(block()) }
    } catch (e: CancellationException) {
        val r = result.get()
        if (r !== Unset) {
            @Suppress("UNCHECKED_CAST")
            runCatching { release(r as T) }
        }
        throw e
    }
    @Suppress("UNCHECKED_CAST")
    return result.get() as T
}

/**
 * The absolute target of symlink [linkPath] whose contents are [target] (relative
 * targets are relative to the link's directory). Pure path arithmetic; Ferrux still
 * confines the result to `$HOME`.
 */
internal fun resolveLinkTarget(linkPath: String, target: String): String =
    if (target.startsWith("/")) target else linkPath.substringBeforeLast('/', "") + "/" + target
