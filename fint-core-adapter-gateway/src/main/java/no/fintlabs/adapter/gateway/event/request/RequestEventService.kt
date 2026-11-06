package no.fintlabs.adapter.gateway.event.request

import no.fintlabs.adapter.gateway.register.EventCapabilities
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toResourceRef
import no.novari.core.shared.model.OrgId
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * Serves pending events to an adapter: each org's events within the scope, filtered by what the
 * org is served, oldest first, then cut to size.
 */
@Service
class RequestEventService(
    private val eventStore: EventStore,
    private val clock: Clock,
) {
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
}
