package com.smartring.app.sync.platform

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs a blocking call that coroutine cancellation cannot interrupt, and makes cancellation
 * *work* anyway by closing whatever it is blocked on.
 *
 * A Bluetooth socket's `read()` and `accept()` ignore thread interruption: cancel the coroutine
 * and the thread stays parked in the kernel until bytes arrive or the peer goes away — which is
 * how a "cancelled" handshake keeps a socket open for minutes. Closing the socket is the one
 * thing that does unblock it, so a watcher child of the same scope does exactly that, and only
 * if the block was still running when the cancellation arrived.
 *
 * The flag matters: without it, the watcher would also close the socket on the *normal* path,
 * when the block finishes and the watcher is cancelled to clean up.
 */
internal suspend fun <T> blockingCancellable(onCancel: () -> Unit, block: () -> T): T =
    withContext(Dispatchers.IO) {
        val finished = AtomicBoolean(false)
        val watcher = launch {
            try {
                awaitCancellation()
            } catch (e: CancellationException) {
                if (!finished.get()) onCancel()
                throw e
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }
