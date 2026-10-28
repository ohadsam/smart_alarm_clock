package com.smartring.app.sync.platform

import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * At most one conversation with a given phone at a time, in either direction.
 *
 * Two sessions with the same peer would apply the same revisions twice at once, and the
 * check-then-save in the applier is not atomic. The lock is *try*-only on purpose: if both phones
 * connect to each other at the same moment, waiting would make each hold its own lock while its
 * responder waits for the other's — a deadlock that only the 20-second timeouts would break. The
 * loser simply gives up; the next sync does the work.
 */
@Singleton
class PeerLocks @Inject constructor() {
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Runs [block] if nothing else is talking to [deviceId]; returns null, without running it, if so. */
    suspend fun <T> tryRun(deviceId: String, block: suspend () -> T): T? {
        val mutex = locks.getOrPut(deviceId) { Mutex() }
        if (!mutex.tryLock()) return null
        try {
            return block()
        } finally {
            mutex.unlock()
        }
    }
}
