package no.fintlabs.client.resource.event

import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.novari.fint.core.model.FintResource
import java.net.URI

/**
 * Represents the outcome of an event request.
 */
sealed interface RequestStatus {
    val body: Any?
}

data class ResourceCreated(
    override val body: Any?,
    val location: URI,
) : RequestStatus

data class RequestValidated(
    override val body: Any,
) : RequestStatus

data object RequestAccepted : RequestStatus {
    override val body: Nothing? = null
}

data object ResourceDeleted : RequestStatus {
    override val body: Nothing? = null
}

data object RequestGone : RequestStatus {
    override val body: Nothing? = null
}

data class RequestFailed(
    override val body: Any,
    val failureType: FailureType,
) : RequestStatus {
    enum class FailureType { REJECTED, CONFLICT, ERROR }
}

/** The resources a live read by filter found, in the same form as a list read from the cache. An empty list is a result too. */
data class ResourcesRead(
    override val body: FintResourcesResponse,
) : RequestStatus

/** The one resource a live read by id found. */
data class ResourceRead(
    override val body: FintResource,
) : RequestStatus

/** A live read by id found nothing. */
data object ResourceNotRead : RequestStatus {
    override val body: Nothing? = null
}
