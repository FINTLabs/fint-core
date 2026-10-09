package no.fintlabs.adapter.gateway.event.response

import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.event.ReadAnswerTooLargeException
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toCoordinate
import no.novari.core.shared.event.toStoredJson
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.springframework.stereotype.Service

/**
 * Takes an adapter's answer to a READ. The resources are checked against the rules of the
 * request and bound to the model the same way the sync door binds a page, so a bad resource is
 * refused here with a 400 instead of failing the client later. What is stored on the event is
 * the storage form of each resource, the same form a cache document holds, so client-api can
 * render a live result exactly like a cache read. Nothing is written to the cache and nothing
 * goes to the feed.
 *
 * The whole answer lives on one event document, and Mongo allows 16 MB per document. An answer
 * past [MAX_ANSWER_BYTES] is therefore not stored. The event is closed with a rejected answer
 * that tells the client to narrow the filter, and the adapter gets a 413, so neither side waits
 * for the deadline.
 */
@Service
class ReadAnswerService(
    private val eventStore: EventStore,
) {
    private val storageMapper = FintJson.storageMapper()

    fun accept(
        request: RequestFintEvent,
        response: ResponseFintEvent,
        collectionName: String,
    ) {
        requireWithinRules(request, response)
        response.values = response.values.map { it.toStorageForm(request.toCoordinate()) }

        val bytes =
            response
                .toStoredJson()
                .toByteArray()
                .size
                .toLong()
        if (bytes > MAX_ANSWER_BYTES) {
            eventStore.markAnswered(response.tooLarge(bytes), collectionName)
            throw ReadAnswerTooLargeException(bytes, MAX_ANSWER_BYTES)
        }

        if (eventStore.markAnswered(response, collectionName) != ClaimOutcome.Claimed) {
            throw NoRequestFoundException(response.corrId)
        }
    }

    private fun requireWithinRules(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ) {
        if (response.isConflicted) invalid("A read answer cannot be conflicted. Use rejected or failed.")
        if (response.value != null) invalid("A read answer returns its resources in values, not in value.")
        if ((response.isFailed || response.isRejected) && response.values.isNotEmpty()) {
            invalid("A failed or rejected read answer carries no values.")
        }
        if (request.id != null && response.values.size > 1) {
            invalid("A read by id returns at most one resource, the answer holds ${response.values.size}.")
        }
    }

    private fun SyncPageEntry.toStorageForm(coordinate: ResourceCoordinate): SyncPageEntry {
        if (identifier.isNullOrBlank()) invalid("A resource in values has no identifier.")
        val resource =
            runCatching { storageMapper.convertValue(this.resource, coordinate.toResourceClass()) }
                .getOrElse { invalid("Resource $identifier is not a valid ${coordinate.resourceName}: ${it.message}") }
        resource.removeSelfLinks()
        return SyncPageEntry.of(identifier, storageMapper.convertValue(resource, Map::class.java))
    }

    private fun ResponseFintEvent.tooLarge(bytes: Long): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = this@tooLarge.corrId
            orgId = this@tooLarge.orgId
            adapterId = this@tooLarge.adapterId
            operationType = OperationType.READ
            handledAt = this@tooLarge.handledAt
            isRejected = true
            rejectReason =
                "The answer was $bytes bytes, and a live read may return at most $MAX_ANSWER_BYTES bytes. Use a narrower filter."
        }

    private fun invalid(message: String): Nothing = throw InvalidResponseFintEventException(message)

    companion object {
        const val MAX_ANSWER_BYTES: Long = 8L * 1024 * 1024
    }
}
