package no.fintlabs.adapter.gateway.event.response

import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceIngest
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.fintlabs.adapter.gateway.sync.InvalidSyncPageEntryException
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.event.toCoordinate
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.toResourceClass
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Takes an adapter's answer to a stored request. The stored request decides what kind of event
 * it is, never the answer. An answer that names another operation is refused. A READ is handed
 * to [ReadAnswerService]. For a write, the answer claim and the resource write happen in one
 * Mongo transaction.
 */
@Service
class ResponseEventService(
    private val eventStore: EventStore,
    private val resourceWritePipeline: ResourceWritePipeline,
    private val responseFintEventProducer: ResponseFintEventProducer,
    private val clock: Clock,
    private val transactions: MongoTransactions,
    private val eventAuthorization: EventAuthorization,
    private val readAnswerService: ReadAnswerService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val storageMapper = FintJson.storageMapper()

    fun handleEvent(responseFintEvent: ResponseFintEvent) {
        val collectionName = OrgId.from(responseFintEvent.orgId).toEventCollectionName()

        val now = clock.instant()
        val stored =
            eventStore.findByCorrId(responseFintEvent.corrId, collectionName)
                ?: throw NoRequestFoundException(responseFintEvent.corrId)

        if (stored.status != EventState.PENDING || stored.isExpired(now)) {
            throw NoRequestFoundException(responseFintEvent.corrId)
        }

        eventAuthorization.requireAnswerAllowed(stored.request)
        requireSameOperation(stored.request, responseFintEvent)
        responseFintEvent.handledAt = now.toEpochMilli()

        when (stored.request.operationType) {
            OperationType.READ -> readAnswerService.accept(stored.request, responseFintEvent, collectionName)
            else -> acceptWriteAnswer(stored, responseFintEvent, collectionName)
        }
    }

    private fun acceptWriteAnswer(
        stored: StoredEvent,
        response: ResponseFintEvent,
        collectionName: String,
    ) {
        validateWriteAnswer(stored.request, response)

        resourceWritePipeline.prepare(stored.request.toCoordinate())

        val outcome =
            transactions.inTransaction {
                val claim = eventStore.markAnswered(response, collectionName)
                if (claim == ClaimOutcome.Claimed) persistEntity(stored.request, response)
                claim
            }

        if (outcome != ClaimOutcome.Claimed) throw NoRequestFoundException(response.corrId)

        responseFintEventProducer.publish(response)
    }

    private fun persistEntity(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ) {
        if (createRequestFailed(request, response) || request.operationType == OperationType.VALIDATE) {
            logger.info("Not sending entity to storage because it is a validate event or create request failed")
            return
        }

        val coordinate = request.toCoordinate()

        resourceWritePipeline.apply(
            ResourceIngest.Save(
                coordinate = coordinate,
                resourceId = response.value.identifier,
                resource = storageMapper.convertValue(response.value.resource, coordinate.toResourceClass()),
                timestamp = Instant.ofEpochMilli(response.handledAt),
            ),
        )
    }

    private fun requireSameOperation(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ) {
        if (response.operationType == null) {
            logger.error(
                "Received event {} with no OperationType from adapter {}, returning BAD_REQUEST",
                response.corrId,
                response.adapterId,
            )
            throw InvalidResponseFintEventException("OperationType is required but was not provided.")
        }

        if (response.operationType != request.operationType) {
            throw InvalidResponseFintEventException(
                "The answer says ${response.operationType} but the request ${request.corrId} is a ${request.operationType}.",
            )
        }
    }

    private fun validateWriteAnswer(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ) {
        if (syncPageEntryIsNullWhenRequired(request, response)) {
            logger.error(
                "Received a SyncPageEntry that is null on event {} from adapter {}",
                response.corrId,
                response.adapterId,
            )
            throw InvalidSyncPageEntryException("SyncPageEntry is null")
        }
    }

    private fun createRequestFailed(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ): Boolean =
        request.operationType == OperationType.CREATE &&
            (response.isFailed || response.isRejected || response.isConflicted)

    private fun syncPageEntryIsNullWhenRequired(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ): Boolean =
        if (request.operationType == OperationType.VALIDATE) {
            response.isConflicted && response.value == null
        } else {
            response.value == null
        }

    private fun StoredEvent.isExpired(now: Instant): Boolean = !now.isBefore(deadline)
}
