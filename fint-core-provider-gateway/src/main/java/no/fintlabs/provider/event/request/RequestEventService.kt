package no.fintlabs.provider.event.request

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import org.springframework.stereotype.Service
import java.time.Clock

@Service
class RequestEventService(
    private val eventStore: EventStore,
    private val clock: Clock,
) {
    fun getEvents(
        assets: Set<String>,
        scope: EventScope,
        size: Int = 0,
    ): List<RequestFintEvent> {
        val now = clock.instant()

        return assets
            .flatMap { asset ->
                eventStore.findPending(
                    OrgId.from(asset).toEventCollectionName(),
                    now,
                    scope,
                    size,
                )
            }.sortedBy { it.created }
            .let { if (size > 0) it.take(size) else it }
    }
}
