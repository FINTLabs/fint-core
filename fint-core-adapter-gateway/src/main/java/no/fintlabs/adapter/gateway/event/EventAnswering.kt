package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceIngest
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * Stores an adapter's answer to an event, for both the v1 and the v2 event API. The answer claim
 * and the resource write happen in one Mongo transaction: either the event is marked answered
 * AND the resource is in the store, or neither happened. The claim runs first inside the
 * transaction so a lost race does no resource work. Collections are prepared before the
 * transaction, because Mongo does not allow creating indexes inside one.
 */
@Component
class EventAnswering(
    private val eventStore: EventStore,
    private val resourceWritePipeline: ResourceWritePipeline,
    private val transactions: MongoTransactions,
) {
    private val storageMapper = FintJson.storageMapper()

    fun claim(
        stored: StoredEvent,
        collectionName: String,
        response: EventResponse,
        handledAt: Instant,
        resourceToSave: SyncPageEntry?,
    ): ClaimOutcome {
        val coordinate = stored.request.toCoordinate()
        resourceWritePipeline.prepare(coordinate)

        return transactions.inTransaction {
            val claim = eventStore.markAnswered(stored.request, response, handledAt, collectionName)
            if (claim == ClaimOutcome.Claimed && resourceToSave != null) save(coordinate, resourceToSave, handledAt)
            claim
        }
    }

    private fun save(
        coordinate: ResourceCoordinate,
        entry: SyncPageEntry,
        handledAt: Instant,
    ) {
        resourceWritePipeline.apply(
            ResourceIngest.Save(
                coordinate = coordinate,
                resourceId = entry.identifier,
                resource = storageMapper.convertValue(entry.resource, coordinate.toResourceClass()),
                timestamp = handledAt,
            ),
        )
    }

    private fun EventRequest.toCoordinate(): ResourceCoordinate = ResourceCoordinate(orgId, domainName, packageName, resourceName)
}
