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
 * Takes an adapter's answer to a pending event: claims the event, stores the resource when the
 * answer carries one, and publishes the answer.
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
     * Events are kept per org, so the request is looked up under the org the answer names. An
     * answer that names another of the adapter's orgs is refused rather than reported unknown.
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

    private fun operationOf(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ): OperationType {
        // The request decides. A different operation in the answer is an adapter bug, so log it.
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
        val resource = if (response.carriesResource(request)) readResource(request, response) else null
        // Nothing reads the value here, so drop it rather than store and publish whatever the adapter sent.
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
     * The resource in the answer, bound to the model. An answer without a usable one is refused
     * with 400 and the event stays pending, so the adapter can answer again.
     */
    private fun readResource(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ): FintResource {
        val coordinate = request.toCoordinate()
        val entry =
            response.value
                ?: invalid(
                    "The answer has no value. A successful ${request.operationType} and a conflicted answer need the resource in value.",
                )
        if (entry.identifier.isNullOrBlank()) invalid("The resource in value has no identifier.")
        val raw = entry.resource ?: invalid("The resource in value is null.")

        return runCatching { storageMapper.convertValue(raw, coordinate.toResourceClass()) }
            .getOrElse { invalid("The resource in value is not a valid ${coordinate.resourceName}: ${it.message}") }
    }

    private fun invalid(reason: String): Nothing = throw InvalidResponseFintEventException(reason)

    /** A conflicted answer carries the resource the source system holds, which the client gets. */
    private fun ResponseFintEvent.carriesResource(request: RequestFintEvent): Boolean =
        isConflicted || (succeeded() && request.operationType in STORED_OPERATIONS)

    /** A conflicted UPDATE is stored too, since its resource is what the source system holds. */
    private fun ResponseFintEvent.isStored(request: RequestFintEvent): Boolean =
        when (request.operationType) {
            OperationType.CREATE -> succeeded()
            OperationType.UPDATE -> !isFailed && !isRejected
            else -> false
        }

    private fun ResponseFintEvent.succeeded(): Boolean = !isFailed && !isRejected && !isConflicted

    private fun StoredEvent.isExpired(now: Instant): Boolean = !now.isBefore(deadline)

    private companion object {
        val STORED_OPERATIONS = setOf(OperationType.CREATE, OperationType.UPDATE)
    }
}
