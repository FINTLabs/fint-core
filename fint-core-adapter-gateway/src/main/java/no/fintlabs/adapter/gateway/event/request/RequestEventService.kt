package no.fintlabs.adapter.gateway.event.request

import no.fintlabs.adapter.gateway.config.EventLongPollProperties
import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toResourceRef
import no.novari.core.shared.model.OrgId
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
    private val clock: Clock,
    private val eventSignal: EventSignal,
    private val longPoll: EventLongPollProperties,
) {
    /**
     * The pending events. With a [wait], an empty answer is held until an event arrives or the
     * wait is over. Pending events are never held back, also those the adapter was served before.
     */
    fun getEvents(
        orgs: Map<OrgId, EventCapabilities>,
        scope: EventScope,
        size: Int = 0,
        wait: Duration = Duration.ZERO,
    ): List<RequestFintEvent> {
        if (wait <= Duration.ZERO) return findEvents(orgs, scope, size)

        // The gateway does not see that an adapter went away while its request is held. The
        // limit ends such a request, which otherwise reads Mongo on every signal and timer.
        val deadline = System.nanoTime() + minOf(wait, longPoll.maxWait).toNanos()

        // Subscribed before the first read, so an event stored between a read and the wait
        // after it still wakes the request.
        return eventSignal.subscribe(orgs.keys.map { it.toEventCollectionName() }).use { subscription ->
            findEventsBefore(deadline, subscription, orgs, scope, size)
        }
    }

    private fun findEventsBefore(
        deadline: Long,
        subscription: EventSignal.Subscription,
        orgs: Map<OrgId, EventCapabilities>,
        scope: EventScope,
        size: Int,
    ): List<RequestFintEvent> {
        val requeryNanos = longPoll.requeryInterval.toNanos()

        while (true) {
            val events = findEvents(orgs, scope, size)
            val remaining = deadline - System.nanoTime()
            if (events.isNotEmpty() || remaining <= 0 || !eventSignal.isRunning) return events

            subscription.await(Duration.ofNanos(minOf(remaining, requeryNanos)))
        }
    }

    private fun findEvents(
        orgs: Map<OrgId, EventCapabilities>,
        scope: EventScope,
        size: Int,
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
}
