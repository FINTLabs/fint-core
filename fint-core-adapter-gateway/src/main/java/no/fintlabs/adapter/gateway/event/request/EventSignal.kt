package no.fintlabs.adapter.gateway.event.request

import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Wakes held requests for events when an event may have arrived in a collection they wait on.
 * A signal is a hint and carries no event. The woken request reads the events from the store.
 */
@Component
class EventSignal : SmartLifecycle {
    private val subscriptions = ConcurrentHashMap<String, MutableSet<Subscription>>()

    @Volatile
    private var stopped = false

    /** Subscribes to signals for the given event collections. Close the subscription when the request ends. */
    fun subscribe(collectionNames: Collection<String>): Subscription {
        val subscription = Subscription(collectionNames.toSet())
        subscription.collectionNames.forEach { name ->
            subscriptions.compute(name) { _, waiting ->
                (waiting ?: ConcurrentHashMap.newKeySet()).apply { add(subscription) }
            }
        }
        return subscription
    }

    fun signal(collectionName: String) {
        subscriptions[collectionName]?.forEach { it.wake() }
    }

    /** Wakes every held request. */
    fun signalAll() {
        subscriptions.values.forEach { waiting -> waiting.forEach { it.wake() } }
    }

    override fun start() {
        stopped = false
    }

    // The default phase stops this before the web server, so held requests return and do not
    // delay a graceful shutdown.
    override fun stop() {
        stopped = true
        signalAll()
    }

    override fun isRunning(): Boolean = !stopped

    inner class Subscription internal constructor(
        internal val collectionNames: Set<String>,
    ) : AutoCloseable {
        private val signals = Semaphore(0)

        /** Waits for a signal for at most [timeout]. Returns at once when one came since the last wait. */
        fun await(timeout: Duration) {
            if (stopped) return
            signals.tryAcquire(timeout.toNanos(), TimeUnit.NANOSECONDS)
            signals.drainPermits()
        }

        internal fun wake() = signals.release()

        override fun close() {
            collectionNames.forEach { name ->
                subscriptions.computeIfPresent(name) { _, waiting ->
                    waiting.remove(this)
                    waiting.takeUnless { it.isEmpty() }
                }
            }
        }
    }
}
