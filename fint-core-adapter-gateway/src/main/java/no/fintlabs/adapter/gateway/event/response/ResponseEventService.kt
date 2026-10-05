package no.fintlabs.adapter.gateway.event.response

import no.fintlabs.adapter.gateway.event.EventAnswering
import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.gateway.sync.InvalidSyncPageEntryException
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.event.toEventResponse
import no.novari.core.shared.model.OrgId
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Handles answers from v1 adapters. The answer is stored in the v2 shape through
 * [EventAnswering], and the v1 answer as received goes to the Kafka feed after commit.
 * handledAt is stamped from the provider's clock at receipt, so every storage timestamp
 * comparison stays on one clock. An answer arriving after the deadline is rejected like an
 * unknown corrId, both up front and inside the claim itself, so the provider and the consumer's
 * status derivation agree on when an event died. A read event is never served to a v1 adapter,
 * so a v1 answer to one is treated as an unknown corrId too.
 */
@Service
class ResponseEventService(
    private val eventStore: EventStore,
    private val eventAnswering: EventAnswering,
    private val responseFintEventProducer: ResponseFintEventProducer,
    private val clock: Clock,
    private val eventAuthorization: EventAuthorization,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun handleEvent(responseFintEvent: ResponseFintEvent) {
        val collectionName = OrgId.from(responseFintEvent.orgId).toEventCollectionName()

        val now = clock.instant()
        val stored =
            eventStore.findByCorrId(responseFintEvent.corrId, collectionName)
                ?: throw NoRequestFoundException(responseFintEvent.corrId)

        if (stored.status != EventState.PENDING || stored.isExpired(now) || stored.request.operation == EventOperation.READ) {
            throw NoRequestFoundException(responseFintEvent.corrId)
        }

        eventAuthorization.requireRoleFor(stored.request)
        validateEvent(responseFintEvent)
        responseFintEvent.handledAt = now.toEpochMilli()

        val outcome =
            eventAnswering.claim(
                stored,
                collectionName,
                responseFintEvent.toEventResponse(),
                now,
                resourceToSave(responseFintEvent),
            )

        if (outcome != ClaimOutcome.Claimed) throw NoRequestFoundException(responseFintEvent.corrId)

        responseFintEventProducer.publish(responseFintEvent)
    }

    private fun resourceToSave(response: ResponseFintEvent): SyncPageEntry? {
        if (createRequestFailed(response) || response.operationType == OperationType.VALIDATE) {
            logger.info("Not sending entity to storage because it is a validate event or create request failed")
            return null
        }
        return response.value
    }

    // TODO: Use Jakatra validation in fint-core-infra-models instead
    private fun validateEvent(response: ResponseFintEvent) {
        if (response.operationType == null) {
            logger.error(
                "Received event {} with no OperationType from adapter {}, returning BAD_REQUEST",
                response.corrId,
                response.adapterId,
            )
            throw InvalidResponseFintEventException("OperationType is required but was not provided.")
        }

        if (syncPageEntryIsNullWhenRequired(response)) {
            logger.error(
                "Received a SyncPageEntry that is null on event {} from adapter {}",
                response.corrId,
                response.adapterId,
            )
            throw InvalidSyncPageEntryException("SyncPageEntry is null")
        }
    }

    private fun createRequestFailed(response: ResponseFintEvent): Boolean =
        response.operationType == OperationType.CREATE &&
            (response.isFailed || response.isRejected || response.isConflicted)

    private fun syncPageEntryIsNullWhenRequired(response: ResponseFintEvent): Boolean =
        if (response.operationType == OperationType.VALIDATE) {
            response.isConflicted && response.value == null
        } else {
            response.value == null
        }

    private fun StoredEvent.isExpired(now: Instant): Boolean = !now.isBefore(deadline)
}
