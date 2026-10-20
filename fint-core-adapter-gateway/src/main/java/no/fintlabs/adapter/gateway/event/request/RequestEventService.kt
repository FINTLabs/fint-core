package no.fintlabs.adapter.gateway.event.request

import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toResourceRef
import no.novari.core.shared.model.OrgId
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration

/**
 * Serves pending events to an adapter: each org's events within the scope, filtered by what the
 * org is served, oldest first, then cut to size.
 */
@Service
class RequestEventService(
    private val eventStore: EventStore,
    private val longPollProperties: EventLongPollProperties,
    private val clock: Clock,
) {
    @Volatile
    private var shuttingDown = false

    fun getEvents(
        orgs: Map<OrgId, EventCapabilities>,
        scope: EventScope,
        size: Int = 0,
    ): List<RequestFintEvent> {
        val now = clock.instant()

        return orgs
            .flatMap { (orgId, capabilities) ->
                eventStore
                    .findPending(orgId.toEventCollectionName(), now, scope)
                    .filter { capabilities.covers(it.toResourceRef(), it.operationType) }
            }.sortedBy { it.created }
            .let { if (size > 0) it.take(size) else it }
    }

    /** Like [getEvents], but an empty answer is held for up to [wait] and returned as soon as an event is pending. */
    fun awaitEvents(
        orgs: Map<OrgId, EventCapabilities>,
        scope: EventScope,
        size: Int,
        wait: Duration,
    ): List<RequestFintEvent> {
        // Monotonic, so a wall-clock jump cannot stretch the hold toward the access gateway's limit.
        val deadline = System.nanoTime() + wait.toNanos()

        while (true) {
            val events = getEvents(orgs, scope, size)
            val remaining = Duration.ofNanos(deadline - System.nanoTime())
            if (events.isNotEmpty() || !remaining.isPositive || shuttingDown) return events

            try {
                // client-api stores events without telling the gateway, so look again after a while.
                Thread.sleep(minOf(remaining, longPollProperties.recheckInterval))
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return events
            }
        }
    }

    /** Lets held polls go when the application starts closing, so a shutdown does not wait them out. */
    @EventListener(ContextClosedEvent::class)
    fun releaseHeldRequests() {
        shuttingDown = true
    }
}
