package no.novari.fint.core.provider.event.request

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.shared.event.EventStatusStore
import org.springframework.stereotype.Service
import java.util.Optional

@Service
class RequestEventService(
    private val eventStatusStore: EventStatusStore,
) {
    fun getEvents(
        assets: Set<String>,
        domainName: String? = null,
        packageName: String? = null,
        resourceName: String? = null,
        size: Int = 0,
    ): List<RequestFintEvent> = eventStatusStore.findPendingRequests(assets, domainName, packageName, resourceName, size)

    fun getEvent(corrId: String): Optional<RequestFintEvent> = Optional.ofNullable(eventStatusStore.getRequest(corrId))
}
