package no.fintlabs.adapter.gateway.event.v2

import no.fintlabs.adapter.gateway.config.EventProperties
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.novari.core.shared.json.FintJson
import org.springframework.stereotype.Component

/**
 * Checks a v2 answer against the stored request it answers, following the table on
 * [EventResponse]. These rules need the request from Mongo, so they run in the service after the
 * lookup. Checks that only need the answer itself (status set, identifiers present) are Bean
 * Validation on the request body and run before the controller:
 *
 * | Operation          | SUCCEEDED       | CONFLICT  | REJECTED / ERROR |
 * |--------------------|-----------------|-----------|------------------|
 * | READ with filter   | 0 to maxResults | not valid | 0                |
 * | READ by id         | 0 or 1          | not valid | 0                |
 * | CREATE / UPDATE    | exactly 1       | exactly 1 | 0                |
 * | VALIDATE / DELETE  | 0               | exactly 1 | 0                |
 *
 * A read answer must also fit within [EventProperties.maxReadAnswerSize] as JSON, because the
 * results are stored on the event. Every failure tells the adapter what to send instead.
 */
@Component
class EventAnswerRules(
    private val eventProperties: EventProperties,
) {
    private val mapper = FintJson.storageMapper()

    fun validate(
        request: EventRequest,
        response: EventResponse,
    ) {
        val status = checkNotNull(response.status) { "status is checked by Bean Validation before this runs" }
        val operation = request.operation
        val resources = response.resources.orEmpty()
        val allowed = allowedCount(request, status) ?: invalid("$status is not a valid answer to a $operation event.")

        if (operation == EventOperation.READ && request.id == null && status == EventStatus.SUCCEEDED && resources.size > allowed.last) {
            invalid(
                "The read found ${resources.size} resources, more than maxResults (${allowed.last}). " +
                    "Answer REJECTED and ask the client to narrow the filter.",
            )
        }
        if (resources.size !in allowed) {
            invalid("A $status answer to a $operation event must carry ${allowed.describe()}, but it carried ${resources.size}.")
        }
        if (operation == EventOperation.READ) requireSizeWithinLimit(response)
    }

    private fun allowedCount(
        request: EventRequest,
        status: EventStatus,
    ): IntRange? =
        when (request.operation) {
            EventOperation.READ -> {
                when (status) {
                    EventStatus.SUCCEEDED -> if (request.id != null) 0..1 else 0..(request.maxResults ?: Int.MAX_VALUE)
                    EventStatus.REJECTED, EventStatus.ERROR -> NONE
                    EventStatus.CONFLICT -> null
                }
            }

            EventOperation.CREATE, EventOperation.UPDATE -> {
                when (status) {
                    EventStatus.SUCCEEDED, EventStatus.CONFLICT -> ONE
                    EventStatus.REJECTED, EventStatus.ERROR -> NONE
                }
            }

            EventOperation.VALIDATE, EventOperation.DELETE, null -> {
                when (status) {
                    EventStatus.CONFLICT -> ONE
                    EventStatus.SUCCEEDED, EventStatus.REJECTED, EventStatus.ERROR -> NONE
                }
            }
        }

    private fun requireSizeWithinLimit(response: EventResponse) {
        val size = mapper.writeValueAsBytes(response).size.toLong()
        val limit = eventProperties.maxReadAnswerSize

        if (size > limit.toBytes()) {
            invalid(
                "The answer is $size bytes, more than the limit of ${limit.toMegabytes()} MB. " +
                    "Answer REJECTED and ask the client to narrow the filter.",
            )
        }
    }

    private fun IntRange.describe(): String =
        when {
            first == last -> "exactly $first resource${if (first == 1) "" else "s"}"
            last == Int.MAX_VALUE -> "at least $first resources"
            else -> "$first to $last resources"
        }

    private fun invalid(message: String): Nothing = throw InvalidEventAnswerException(message)

    private companion object {
        val NONE = 0..0
        val ONE = 1..1
    }
}
