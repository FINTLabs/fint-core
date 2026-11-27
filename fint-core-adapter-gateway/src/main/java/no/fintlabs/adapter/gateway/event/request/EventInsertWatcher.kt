package no.fintlabs.adapter.gateway.event.request

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Signals [EventSignal] when an event is stored, from one change stream for each events
 * collection that a request waits on.
 */
@Component
class EventInsertWatcher(
    private val template: MongoTemplate,
    private val eventSignal: EventSignal,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)

    // A stream on the whole database would be one stream for all orgs, but Azure DocumentDB
    // reports no changes on such a stream.
    private val streams = ConcurrentHashMap<String, CollectionStream>()

    @Volatile
    private var running = false

    /** Opens a stream on each collection that has none. A stream stays open until the gateway stops. */
    fun watch(collectionNames: Collection<String>) {
        if (!running) return
        collectionNames.forEach { name ->
            streams.computeIfAbsent(name) { CollectionStream(it).apply { start() } }
        }
    }

    /** Whether the stream on the collection is open and inserts are signalled. */
    fun isWatching(collectionName: String): Boolean = streams[collectionName]?.open == true

    override fun start() {
        running = true
    }

    override fun stop() {
        running = false
        streams.values.forEach { it.stop() }
        streams.clear()
    }

    override fun isRunning(): Boolean = running

    private inner class CollectionStream(
        private val collectionName: String,
    ) {
        @Volatile
        var open = false
            private set

        @Volatile
        private var thread: Thread? = null

        // Only the thread of this stream reads and writes this.
        private var retryPause = FIRST_RETRY_PAUSE

        fun start() {
            thread = Thread.ofVirtual().name("event-insert-watcher-$collectionName").start(::watchUntilStopped)
        }

        fun stop() {
            thread?.interrupt()
        }

        private fun watchUntilStopped() {
            while (running) {
                try {
                    watch()
                } catch (e: Exception) {
                    if (!running) return
                    logger.warn(
                        "The change stream on {} failed. It opens again in {} s. Held requests are not woken for new events until then.",
                        collectionName,
                        retryPause.toSeconds(),
                        e,
                    )
                    pauseBeforeRetry()
                } finally {
                    open = false
                }
            }
        }

        private fun watch() {
            template.db
                .getCollection(collectionName)
                .watch(INSERTS)
                .maxAwaitTime(EMPTY_READ_TIME.toMillis(), TimeUnit.MILLISECONDS)
                .cursor()
                .use { changes ->
                    open = true
                    logger.info("Watching {} for new events", collectionName)
                    // A request reads the events before it waits, so only a request that already
                    // waits can miss an event that was stored while no stream was open. Those
                    // requests read again now.
                    eventSignal.signal(collectionName)
                    while (running) {
                        val change = changes.tryNext()
                        // The database closes the stream when the collection is dropped. A closed
                        // stream gives no error and no changes, so it must be opened again.
                        if (change == null && changes.serverCursor == null) {
                            throw IllegalStateException("The database closed the change stream")
                        }
                        // Reset after a read that worked, not after the open, so a stream that
                        // opens and then fails each time still backs off.
                        retryPause = FIRST_RETRY_PAUSE
                        if (change != null) eventSignal.signal(collectionName)
                    }
                }
        }

        private fun pauseBeforeRetry() {
            try {
                Thread.sleep(retryPause)
            } catch (_: InterruptedException) {
                // Stopped while it waited. The loop sees that and ends.
            }
            // A short break is over fast. A stream that stays down is retried, and logged, less often.
            retryPause = minOf(retryPause.multipliedBy(2), LONGEST_RETRY_PAUSE)
        }
    }

    private companion object {
        val FIRST_RETRY_PAUSE: Duration = Duration.ofSeconds(1)
        val LONGEST_RETRY_PAUSE: Duration = Duration.ofSeconds(30)

        // The database answers a read of the stream at once when an event is stored. When
        // nothing is stored for this long, it answers with nothing and the watcher reads again.
        val EMPTY_READ_TIME: Duration = Duration.ofSeconds(1)

        // Only the fact of an insert is needed, so the event itself is not sent on the stream.
        val INSERTS =
            listOf(
                Aggregates.match(Filters.eq("operationType", "insert")),
                Aggregates.project(Projections.include("ns", "operationType")),
            )
    }
}
