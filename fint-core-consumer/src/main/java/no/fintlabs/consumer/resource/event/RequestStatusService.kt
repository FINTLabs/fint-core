package no.fintlabs.consumer.resource.event

import no.fintlabs.adapter.models.event.EventBodyResponse
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.resource.event.RequestFailed.FailureType
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.json.toLinkResponses
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintResource
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Clock

@Service
class RequestStatusService(
    private val eventStore: EventStore,
    private val resourceStore: ResourceStore,
    private val consumerConfiguration: ConsumerConfiguration,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val storageMapper = FintJson.storageMapper()

    fun getStatusResponse(
        coordinate: ResourceCoordinate,
        corrId: String,
    ): RequestStatus {
        val stored = eventStore.findByCorrId(corrId, coordinate.toEventCollectionName()) ?: return RequestGone

        return when (stored.status) {
            EventState.EXPIRED -> stored.toExpiredFailure()
            EventState.ANSWERED -> handleAnsweredEvent(coordinate, stored)
            EventState.PENDING -> handlePendingEvent(stored)
        }
    }

    private fun handleAnsweredEvent(
        coordinate: ResourceCoordinate,
        stored: StoredEvent,
    ): RequestStatus {
        val response =
            stored.response ?: throw IllegalStateException("Answered event ${stored.request.corrId} has no response")

        return if (response.isError()) {
            handleErrorResponse(coordinate, response)
        } else {
            handleSuccessfulResponse(coordinate, response)
        }
    }

    /**
     * A pending event past its deadline is already dead for the client even if the sweeper has
     * not flipped it yet; the failure is derived here so the answer does not depend on sweep
     * timing.
     */
    private fun handlePendingEvent(stored: StoredEvent): RequestStatus =
        if (clock.instant() < stored.deadline) {
            RequestAccepted
        } else {
            stored.toExpiredFailure()
        }

    private fun StoredEvent.toExpiredFailure(): RequestStatus =
        RequestFailed(EventBodyResponse.ofResponseEvent(toExpiredResponse()), FailureType.ERROR)

    private fun handleSuccessfulResponse(
        coordinate: ResourceCoordinate,
        response: ResponseFintEvent,
    ): RequestStatus =
        when (response.operationType) {
            OperationType.VALIDATE -> RequestValidated(EventBodyResponse.ofResponseEvent(response))
            OperationType.DELETE -> ResourceDeleted
            else -> ensureStoreConsistency(coordinate, response)
        }

    /**
     * The provider writes the resource to storage before it attaches the response, so once a
     * response is visible the store already has the resource and finding it is enough to
     * confirm the 201. A missing entry should not normally happen (a purge or delete racing
     * the poll) and stays accepted rather than serving an error.
     */
    private fun ensureStoreConsistency(
        coordinate: ResourceCoordinate,
        response: ResponseFintEvent,
    ): RequestStatus {
        val entry =
            resourceStore.findByResourceId(response.value.identifier, coordinate.toCollectionName())
                ?: return RequestAccepted

        val resource = storageMapper.convertValue(entry.data, coordinate.toResourceClass())
        return ResourceCreated(resource, resource.toSelfLinkUri())
    }

    private fun handleErrorResponse(
        coordinate: ResourceCoordinate,
        response: ResponseFintEvent,
    ): RequestStatus =
        when {
            response.isFailed -> RequestFailed(EventBodyResponse.ofResponseEvent(response), FailureType.ERROR)

            response.isRejected -> RequestFailed(EventBodyResponse.ofResponseEvent(response), FailureType.REJECTED)

            response.isConflicted -> RequestFailed(response.toConflictResource(coordinate), FailureType.CONFLICT)

            else -> throw IllegalStateException(
                "Event response is considered an error, but no specific error flag (failed, rejected, conflicted) is set.",
            )
        }

    private fun ResponseFintEvent.toConflictResource(coordinate: ResourceCoordinate): FintResource =
        storageMapper.convertValue(value.resource, coordinate.toResourceClass())

    private fun ResponseFintEvent.isError(): Boolean = isFailed || isRejected || isConflicted

    private fun StoredEvent.toExpiredResponse(): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = request.corrId
            orgId = request.orgId
            handledAt = deadline.toEpochMilli()
            isFailed = true
            errorMessage = "Event expired."
        }

    private fun FintResource.toSelfLinkUri(): URI =
        toLinkResponses(consumerConfiguration.baseUrl)[FintResource.SELF]
            ?.firstOrNull()
            ?.let { URI.create(it.href) }
            ?: throw IllegalStateException("Resource has no self link")
}
