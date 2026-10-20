package no.fintlabs.adapter.gateway.event.request

import com.mongodb.client.MongoChangeStreamCursor
import com.mongodb.client.model.Aggregates
import com.mongodb.client.model.Filters
import com.mongodb.client.model.Projections
import com.mongodb.client.model.changestream.ChangeStreamDocument
import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.novari.core.shared.event.EVENT_COLLECTION_SUFFIX
import org.bson.Document
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Component
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Wakes held long polls through [EventArrivals] when client-api stores an event. */
@Component
class EventInsertWatcher(
    private val mongoTemplate: MongoTemplate,
    private val eventArrivals: EventArrivals,
    private val properties: EventLongPollProperties,
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var running = false

    override fun start() {
        if (!properties.changeStream.enabled) {
            logger.info(
                "Event change stream is disabled. Held long polls recheck every {}.",
                properties.recheckInterval,
            )
            return
        }
        running = true
        Thread.ofVirtual().name("event-insert-watcher").start(::watch)
    }

    override fun stop() {
        running = false
    }

    override fun isRunning(): Boolean = running

    private fun watch() {
        var failing = false

        while (running) {
            try {
                openStream().use { cursor ->
                    eventArrivals.watched = true
                    // Picks up what arrived while the stream was down, and moves held polls to the long recheck.
                    eventArrivals.signal()
                    logger.info("Watching Mongo for new events.")
                    failing = false

                    while (running) {
                        // No resume token: a woken poll finds its events with its own query, so one lost
                        // notification costs at most a recheck.
                        if (cursor.tryNext() != null) {
                            eventArrivals.signal()
                        } else if (cursor.serverCursor == null) {
                            // Closed by the server, for example after an invalidate. From here tryNext returns
                            // null at once without asking the server, so reading on would spin.
                            throw IllegalStateException("The server closed the event change stream.")
                        }
                    }
                }
            } catch (e: Exception) {
                if (!running) return
                // DocumentDB drops change stream cursors on failover, so this is reopened after a pause.
                if (!failing) {
                    logger.warn(
                        "Lost the event change stream. Held long polls recheck every {} until it is back.",
                        properties.recheckInterval,
                        e,
                    )
                } else {
                    logger.debug("Event change stream is still down.", e)
                }
                failing = true
            } finally {
                eventArrivals.watched = false
                // Moves held polls to the short recheck while the stream is down.
                eventArrivals.signal()
            }

            if (!running) return
            try {
                Thread.sleep(RETRY_DELAY)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    private fun openStream(): MongoChangeStreamCursor<ChangeStreamDocument<Document>> =
        mongoTemplate.db
            .watch(PIPELINE)
            .maxAwaitTime(MAX_AWAIT.toMillis(), TimeUnit.MILLISECONDS)
            .cursor()

    companion object {
        private val RETRY_DELAY = Duration.ofSeconds(5)

        // How long one read of the stream waits on the server, and so how soon stop() is noticed.
        private val MAX_AWAIT = Duration.ofSeconds(1)

        // The whole database, so a sub-org that registers later is covered without reopening the stream.
        private val PIPELINE =
            listOf(
                Aggregates.match(
                    Filters.and(
                        Filters.eq("operationType", "insert"),
                        Filters.regex("ns.coll", "$EVENT_COLLECTION_SUFFIX$"),
                    ),
                ),
                Aggregates.project(Projections.include("operationType", "ns")),
            )
    }
}
