package no.fintlabs.adapter.gateway.event.request

import no.fintlabs.adapter.gateway.register.RegisteredContract
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toResourceRef
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Serves pending events to an adapter, org by org. Each org's events are read within the
 * scopes the adapter asked for and then kept only when that org's contract covers the event's
 * resource and operation, so a READ never reaches an adapter that did not list it. The cut to
 * [getEvents]'s size happens after that filter, on the oldest events first.
 */
@Service
class RequestEventService(
    private val eventStore: EventStore,
    private val clock: Clock,
) {
    fun getEvents(
        contracts: Collection<RegisteredContract>,
        scopes: Collection<EventScope>,
        size: Int = 0,
    ): List<RequestFintEvent> {
        val now = clock.instant()

        return contracts
            .flatMap { contract ->
                scopes
                    .flatMap { scope -> eventStore.findPending(contract.orgId.toEventCollectionName(), now, scope) }
                    .filter { contract.eventCapabilities.covers(it.toResourceRef(), it.operationType) }
            }.sortedBy { it.created }
            .let { if (size > 0) it.take(size) else it }
    }
}
