package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.fintlabs.adapter.operation.OperationType
import java.time.Instant

/**
 * Turns a v1 request into the v2 shape. Nothing is lost: every v1 operation exists in v2, and
 * timeToLive already holds the deadline as a point in time.
 */
fun RequestFintEvent.toEventRequest(): EventRequest =
    EventRequest
        .builder()
        .corrId(corrId)
        .orgId(orgId)
        .domainName(domainName)
        .packageName(packageName)
        .resourceName(resourceName)
        .operation(operationType?.let { EventOperation.valueOf(it.name) })
        .created(created)
        .deadline(timeToLive)
        .value(value)
        .build()

/**
 * Turns a v2 request into the v1 shape that v1 adapters read. A read has no v1 shape, so a read
 * must never reach this.
 */
fun EventRequest.toRequestFintEvent(): RequestFintEvent {
    require(operation != EventOperation.READ) { "Read event $corrId has no v1 shape" }

    return RequestFintEvent().also {
        it.corrId = corrId
        it.orgId = orgId
        it.domainName = domainName
        it.packageName = packageName
        it.resourceName = resourceName
        it.operationType = operation?.let { op -> OperationType.valueOf(op.name) }
        it.created = created
        it.timeToLive = deadline
        it.value = value
    }
}

/**
 * Turns a v1 answer into the v2 shape. The flags are checked in the same order as everywhere
 * else in v1 (failed, rejected, conflicted), so the status is the one a client would have seen.
 */
fun ResponseFintEvent.toEventResponse(): EventResponse =
    EventResponse
        .builder()
        .corrId(corrId)
        .orgId(orgId)
        .status(status())
        .message(message())
        .resources(listOfNotNull(value))
        .build()

/**
 * Turns a v2 answer into the v1 shape, for the client status endpoint and the Kafka feed. The
 * operation and the time the answer was handled come from the stored event, since a v2 answer
 * carries neither.
 */
fun EventResponse.toResponseFintEvent(
    operation: EventOperation?,
    handledAt: Instant?,
): ResponseFintEvent =
    ResponseFintEvent().also {
        it.corrId = corrId
        it.orgId = orgId
        it.operationType =
            operation?.takeIf { op -> op != EventOperation.READ }?.let { op -> OperationType.valueOf(op.name) }
        it.handledAt = handledAt?.toEpochMilli() ?: 0
        it.value = resources?.firstOrNull()
        when (status) {
            EventStatus.ERROR -> {
                it.isFailed = true
                it.errorMessage = message
            }

            EventStatus.REJECTED -> {
                it.isRejected = true
                it.rejectReason = message
            }

            EventStatus.CONFLICT -> {
                it.isConflicted = true
                it.conflictReason = message
            }

            EventStatus.SUCCEEDED, null -> {}
        }
    }

private fun ResponseFintEvent.status(): EventStatus =
    when {
        isFailed -> EventStatus.ERROR
        isRejected -> EventStatus.REJECTED
        isConflicted -> EventStatus.CONFLICT
        else -> EventStatus.SUCCEEDED
    }

private fun ResponseFintEvent.message(): String? =
    when {
        isFailed -> errorMessage
        isRejected -> rejectReason
        isConflicted -> conflictReason
        else -> null
    }
