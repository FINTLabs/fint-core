package no.fintlabs.consumer.resource.event

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
