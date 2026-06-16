package no.fintlabs.consumer.resource.event

import no.fintlabs.adapter.models.event.EventBodyResponse
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceConverter
import no.fintlabs.consumer.resource.event.RequestFailed.FailureType
import no.novari.fint.model.resource.FintResource
import org.springframework.stereotype.Service
import java.net.URI

@Service
class RequestStatusService(
    private val eventStatusCache: EventStatusCache,
    private val cacheService: CacheService,
    private val resourceConverter: ResourceConverter,
    private val linkService: LinkService,
) {
    fun getStatusResponse(
        resourceName: String,
        corrId: String,
    ): RequestStatus =
        eventStatusCache
            .getResponse(corrId)
            ?.takeIf { eventStatusCache.requestExists(corrId) }
            ?.let { handleFinishedEvent(resourceName, it) }
            ?: handleUnknownOrRunningEvent(corrId)

    private fun handleFinishedEvent(
        resourceName: String,
        response: ResponseFintEvent,
    ): RequestStatus =
        if (response.isError()) {
            handleErrorResponse(resourceName, response)
        } else {
            handleSuccessfulResponse(resourceName, response)
        }

    private fun handleSuccessfulResponse(
        resourceName: String,
        response: ResponseFintEvent,
    ): RequestStatus =
        when (response.operationType) {
            OperationType.VALIDATE -> RequestValidated(EventBodyResponse.ofResponseEvent(response))

            OperationType.DELETE -> ResourceDeleted

            // For Create/Update, we must verify the Cache contains the new data before confirming 201 Created
            OperationType.CREATE,
            OperationType.UPDATE,
            -> ensureCacheConsistency(resourceName, response)
        }

    /**
     * Fetches the resource if the Cache has "caught up" with the Event processing.
     * We compare the timestamp of the data in the cache vs. when the adapter handled the event.
     * Ref: https://github.com/FINTLabs/novari-architecture-documentation/blob/main/Core/v2/status_handling.md
     */
    private fun ensureCacheConsistency(
        resourceName: String,
        response: ResponseFintEvent,
    ): RequestStatus =
        response
            .fetchConsistentResource(resourceName)
            ?.let { ResourceCreated(it, it.createSelfLinkUri()) }
            ?: handleUnknownOrRunningEvent(response.corrId)

    private fun handleUnknownOrRunningEvent(corrId: String): RequestStatus =
        if (eventStatusCache.requestExists(corrId)) RequestAccepted else RequestGone

    /**
     * Retrieves the cached resource only if its timestamp matches this event's [ResponseFintEvent.handledAt].
     *
     * This relies on the Provider updating the cache using the event's `handledAt` time,
     * confirming that the data in the cache is the direct result of this specific operation.
     */
    private fun ResponseFintEvent.fetchConsistentResource(resourceName: String): FintResource? {
        val cache = cacheService.getCache(resourceName)
        val cacheTimestamp = cache.lastUpdatedByResourceId(value.identifier)

        return if (cacheTimestamp == handledAt) cache.get(value.identifier) else null
    }

    fun handleErrorResponse(
        resourceName: String,
        response: ResponseFintEvent,
    ): RequestStatus =
        if (response.isFailed) {
            RequestFailed(EventBodyResponse.ofResponseEvent(response), FailureType.ERROR)
        } else if (response.isRejected) {
            RequestFailed(EventBodyResponse.ofResponseEvent(response), FailureType.REJECTED)
        } else if (response.isConflicted) {
            RequestFailed(response.convertResourceAndMapLinks(resourceName), FailureType.CONFLICT)
        } else {
            throw IllegalStateException(
                "Event response is considered an error, but no specific error flag (failed, rejected, conflicted) is set.",
            )
        }

    private fun ResponseFintEvent.convertResourceAndMapLinks(resourceName: String): FintResource =
        resourceConverter
            .convert(resourceName, value.resource)
            .also { linkService.mapLinks(resourceName, it) }

    private fun ResponseFintEvent.isError(): Boolean = isFailed || isRejected || isConflicted

    private fun FintResource.createSelfLinkUri() =
        selfLinks.firstOrNull()?.let { URI.create(it.href) }
            ?: throw RuntimeException("Resource has no self link")
}
