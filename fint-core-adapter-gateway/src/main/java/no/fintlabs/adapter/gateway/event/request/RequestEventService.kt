package no.fintlabs.adapter.gateway.event.request

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.OperationScope
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toRequestFintEvent
import no.novari.core.shared.model.OrgId
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class RequestEventService(
    private val eventStore: EventStore,
    private val clock: Clock,
) {
    fun getEvents(
        orgs: Collection<OrgId>,
        scopes: Collection<EventScope>,
        size: Int = 0,
    ): List<RequestFintEvent> {
        val now = clock.instant()

        return orgs
            .flatMap { org ->
                scopes.flatMap { scope ->
                    eventStore.findPending(org.toEventCollectionName(), now, scope, size)
                }
            }.sortedBy { it.created }
            .let { if (size > 0) it.take(size) else it }
            .map { it.toRequestFintEvent() }
    }

    /**
     * The pending events for a v2 adapter: only the resources and operations its contract for
     * each org lists in eventCapabilities.
     */
    fun getEventsByCapability(
        scopesByOrg: Map<OrgId, List<OperationScope>>,
        size: Int = 0,
    ): List<EventRequest> {
        val now = clock.instant()

        return scopesByOrg
            .flatMap { (org, scopes) -> eventStore.findPendingFor(org.toEventCollectionName(), now, scopes, size) }
            .sortedBy { it.created }
            .let { if (size > 0) it.take(size) else it }
    }
}
