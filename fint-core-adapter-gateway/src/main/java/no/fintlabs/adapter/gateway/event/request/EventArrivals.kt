package no.fintlabs.adapter.gateway.event.request

import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Lets held long polls sleep until an event may have arrived. Each woken poll runs its own query to find out. */
@Component
class EventArrivals {
    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()

    // Moves on with every signal, so a poll that read it before its query cannot sleep through an event stored since.
    @Volatile
    private var signals: Long = 0

    val version: Long get() = signals

    // True while the change stream is up and sending signals.
    @Volatile
    var watched: Boolean = false

    fun signal() {
        lock.withLock {
            signals++
            arrived.signalAll()
        }
    }

    /** Waits until [version] has moved past [seen], or until [timeout] has passed. */
    fun awaitAfter(
        seen: Long,
        timeout: Duration,
    ) {
        lock.withLock {
            var remaining = timeout.toNanos()
            while (signals == seen && remaining > 0) {
                remaining = arrived.awaitNanos(remaining)
            }
        }
    }
}
