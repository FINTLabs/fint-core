package no.fintlabs.adapter.gateway.event.response

import no.fintlabs.adapter.gateway.event.InvalidResponseFintEventException
import no.fintlabs.adapter.gateway.event.NoRequestFoundException
import no.fintlabs.adapter.gateway.security.EventAuthorization
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceIngest
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
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
import no.novari.fint.core.model.FintResource
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * Takes an adapter's answer to a stored request. The stored request decides what kind of event
 * it is, never the answer: an answer that names another operation is accepted and corrected, as
 * provider-gateway accepted it. A READ is handed to [ReadAnswerService]. For a write, the answer
 * claim and the resource write happen in one Mongo transaction.
 *
 * The answer's [ResponseFintEvent.value] matters only where it is used: a successful CREATE or
 * UPDATE stores it, and a conflicted answer hands it to the client as the resource the source
 * system holds. There it must be a resource the model can read. Everywhere else, on a failed or
 * rejected answer and on a VALIDATE that passed, it is dropped: adapters send an empty entry
 * there, which provider-gateway passed on as it was.
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
        val stored = findRequest(responseFintEvent, collectionName)
        if (stored.status != EventState.PENDING || stored.isExpired(now)) {
            throw NoRequestFoundException(responseFintEvent.corrId)
        }

        eventAuthorization.requireAnswerAllowed(stored.request)
        responseFintEvent.handledAt = now.toEpochMilli()
        responseFintEvent.operationType = operationOf(stored.request, responseFintEvent)

        when (stored.request.operationType) {
            OperationType.READ -> readAnswerService.accept(stored.request, responseFintEvent, collectionName)
            else -> acceptWriteAnswer(stored.request, responseFintEvent, collectionName)
        }
    }

    /**
     * Events are kept per org, so the request is looked up under the org the answer names. When
     * it is not there but is under another of the adapter's orgs, the answer named the wrong org
     * and is refused, rather than reported as an unknown event.
     */
    private fun findRequest(
        response: ResponseFintEvent,
        collectionName: String,
    ): StoredEvent {
        eventStore.findByCorrId(response.corrId, collectionName)?.let { return it }

        val underOtherOrg =
            eventAuthorization
                .answerableOrgs()
                .map { it.toEventCollectionName() }
                .filter { it != collectionName }
                .any { eventStore.findByCorrId(response.corrId, it) != null }
        if (underOtherOrg) eventAuthorization.denyAnswerForOtherOrg()

        throw NoRequestFoundException(response.corrId)
    }

    /**
     * The request's operation. An answer that says another is accepted, as on provider-gateway:
     * adapter-common copies the request's, so a difference is an adapter bug, and it is logged
     * because nothing else shows it.
     */
    private fun operationOf(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ): OperationType {
        if (response.operationType != request.operationType) {
            logger.warn(
                "Answer to event {} from adapter {} says {} but the request is a {}; the request decides",
                response.corrId,
                response.adapterId,
                response.operationType,
                request.operationType,
            )
        }
        return request.operationType
    }

    private fun acceptWriteAnswer(
        request: RequestFintEvent,
        response: ResponseFintEvent,
        collectionName: String,
    ) {
        val resource = if (response.carriesResource(request)) readResource(request, response, collectionName) else null
        if (resource == null) response.value = null

        val toStore = resource?.takeIf { response.isStored(request) }
        if (toStore != null) resourceWritePipeline.prepare(request.toCoordinate())

        val outcome =
            transactions.inTransaction {
                val claim = eventStore.markAnswered(response, collectionName)
                if (claim == ClaimOutcome.Claimed && toStore != null) persistEntity(request, response, toStore)
                claim
            }

        if (outcome != ClaimOutcome.Claimed) throw NoRequestFoundException(response.corrId)

        responseFintEventProducer.publish(response)
    }

    private fun persistEntity(
        request: RequestFintEvent,
        response: ResponseFintEvent,
        resource: FintResource,
    ) {
        resourceWritePipeline.apply(
            ResourceIngest.Save(
                coordinate = request.toCoordinate(),
                resourceId = response.value.identifier,
                resource = resource,
                timestamp = Instant.ofEpochMilli(response.handledAt),
            ),
        )
    }

    /**
     * The resource in the answer, bound to the model. An answer without one, or with one the
     * model cannot read, is refused with a 400, and the event is closed as failed with the same
     * reason, so the client gets the error now instead of at the deadline. provider-gateway
     * passed such answers on as they were, which nothing can depend on.
     */
    private fun readResource(
        request: RequestFintEvent,
        response: ResponseFintEvent,
        collectionName: String,
    ): FintResource {
        val coordinate = request.toCoordinate()
        val entry =
            response.value
                ?: refuse(
                    response,
                    collectionName,
                    "The answer has no value. A successful ${request.operationType} and a conflicted answer need the resource in value.",
                )
        if (entry.identifier.isNullOrBlank()) refuse(response, collectionName, "The resource in value has no identifier.")
        val raw = entry.resource ?: refuse(response, collectionName, "The resource in value is null.")

        return runCatching { storageMapper.convertValue(raw, coordinate.toResourceClass()) }
            .getOrElse {
                refuse(
                    response,
                    collectionName,
                    "The resource in value is not a valid ${coordinate.resourceName}: ${it.message}",
                )
            }
    }

    private fun refuse(
        response: ResponseFintEvent,
        collectionName: String,
        reason: String,
    ): Nothing {
        val failed = response.failedBecause(reason)
        if (eventStore.markAnswered(failed, collectionName) == ClaimOutcome.Claimed) {
            responseFintEventProducer.publish(failed)
        }
        throw InvalidResponseFintEventException(reason)
    }

    /**
     * Where the resource is used: the result of a successful CREATE or UPDATE, which is stored,
     * and the resource the source system holds on a conflict, which the client gets.
     */
    private fun ResponseFintEvent.carriesResource(request: RequestFintEvent): Boolean =
        isConflicted || (succeeded() && request.operationType in STORED_OPERATIONS)

    /**
     * Stored: a successful CREATE or UPDATE, and a conflicted UPDATE, whose resource is what the
     * source system holds. A conflicted CREATE stores nothing, as before.
     */
    private fun ResponseFintEvent.isStored(request: RequestFintEvent): Boolean =
        when (request.operationType) {
            OperationType.CREATE -> succeeded()
            OperationType.UPDATE -> !isFailed && !isRejected
            else -> false
        }

    private fun ResponseFintEvent.succeeded(): Boolean = !isFailed && !isRejected && !isConflicted

    private fun ResponseFintEvent.failedBecause(reason: String): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = this@failedBecause.corrId
            orgId = this@failedBecause.orgId
            adapterId = this@failedBecause.adapterId
            operationType = this@failedBecause.operationType
            handledAt = this@failedBecause.handledAt
            isFailed = true
            errorMessage = reason
        }

    private fun StoredEvent.isExpired(now: Instant): Boolean = !now.isBefore(deadline)

    private companion object {
        val STORED_OPERATIONS = setOf(OperationType.CREATE, OperationType.UPDATE)
    }
}
