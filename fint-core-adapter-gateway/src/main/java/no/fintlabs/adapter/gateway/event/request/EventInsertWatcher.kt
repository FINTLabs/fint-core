package no.fintlabs.adapter.gateway.event.request

import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.novari.core.shared.event.EVENT_COLLECTION_SUFFIX
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Signals [EventSignal] when an event is stored, from one change stream on the database.
 * The stream is a hint only. Held requests also look for events on a timer, so a stream that
 * fails or misses an insert costs time and loses no event.
 */
@Component
class EventInsertWatcher(
    private val template: MongoTemplate,
    private val eventSignal: EventSignal,
    private val properties: EventLongPollProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var thread: Thread? = null

    @Volatile
    private var streamOpen = false

    // Only the watcher thread reads and writes this.
    private var retryPause = FIRST_RETRY_PAUSE

    /** Whether the stream is open and inserts are signalled. */
    val watching: Boolean get() = streamOpen

    override fun start() {
        if (!properties.changeStream.enabled) return
        thread = Thread.ofVirtual().name("event-insert-watcher").start(::watchUntilStopped)
    }

    override fun stop() {
        val watcher = thread ?: return
        thread = null
        watcher.interrupt()
    }

    override fun isRunning(): Boolean = thread != null

    private fun watchUntilStopped() {
        while (isRunning) {
            try {
                watch()
            } catch (e: Exception) {
                if (!isRunning) return
                logger.warn(
                    "The change stream for new events failed. It opens again in {} s. Held requests find events on their timer until then.",
                    retryPause.toSeconds(),
                    e,
                )
                pauseBeforeRetry()
            } finally {
                streamOpen = false
            }
        }
    }

    private fun watch() {
        // No resume token is kept. A new stream starts at the present, and the timer of the
        // held requests covers the inserts in between.
        template.db
            .watch(INSERTED_EVENTS)
            .maxAwaitTime(properties.changeStream.maxAwait.toMillis(), TimeUnit.MILLISECONDS)
            .cursor()
            .use { changes ->
                streamOpen = true
                logger.info("Watching for new events")
                while (isRunning) {
                    val change = changes.tryNext()
                    // Reset after a read that worked, not after the open, so a stream that
                    // opens and then fails each time still backs off.
                    retryPause = FIRST_RETRY_PAUSE
                    change?.namespace?.collectionName?.let(eventSignal::signal)
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

    private companion object {
        val FIRST_RETRY_PAUSE: Duration = Duration.ofSeconds(1)
        val LONGEST_RETRY_PAUSE: Duration = Duration.ofSeconds(30)

        // Only the collection name is needed, so the event itself is not sent on the stream.
        val INSERTED_EVENTS =
            listOf(
                Aggregates.match(
                    Filters.and(
                        Filters.eq("operationType", "insert"),
                        Filters.regex("ns.coll", "$EVENT_COLLECTION_SUFFIX$"),
                    ),
                ),
                Aggregates.project(Projections.include("ns", "operationType")),
            )
    }
}
