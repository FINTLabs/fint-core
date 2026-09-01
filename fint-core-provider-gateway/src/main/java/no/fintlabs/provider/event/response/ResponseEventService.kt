package no.fintlabs.provider.event.response

import com.mongodb.MongoException
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.provider.event.InvalidResponseFintEventException
import no.fintlabs.provider.event.NoRequestFoundException
import no.fintlabs.provider.storage.ResourceIngest
import no.fintlabs.provider.storage.ResourceWritePipeline
import no.fintlabs.provider.sync.InvalidSyncPageEntryException
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.StoredEvent
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.model.toResourceClass
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant

/**
 * The answer claim and the resource write happen in one Mongo transaction: either the event is
 * marked answered AND the resource is in the store, or neither happened. The claim runs first
 * inside the transaction so a lost race does no entity work, and the feed publish comes last,
 * after commit. handledAt is stamped from the provider's clock at receipt, so every storage
 * timestamp comparison stays on one clock. An answer arriving after the deadline is rejected
 * like an unknown corrId, both up front and inside the claim itself, so the provider and the
 * consumer's status derivation agree on when an event died.
 */
@Service
class ResponseEventService(
    private val eventStore: EventStore,
    private val resourceWritePipeline: ResourceWritePipeline,
    private val responseFintEventProducer: ResponseFintEventProducer,
    private val mongoTransactionTemplate: TransactionTemplate,
    private val clock: Clock,
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

        validateEvent(responseFintEvent)
        responseFintEvent.handledAt = now.toEpochMilli()

        resourceWritePipeline.prepare(stored.toCoordinate())

        val outcome =
            inTransactionWithRetry {
                val claim = eventStore.markAnswered(responseFintEvent, collectionName)
                if (claim == ClaimOutcome.Claimed) persistEntity(stored.request, responseFintEvent)
                claim
            }

        if (outcome != ClaimOutcome.Claimed) throw NoRequestFoundException(responseFintEvent.corrId)

        responseFintEventProducer.publish(responseFintEvent)
    }

    private fun persistEntity(
        request: RequestFintEvent,
        response: ResponseFintEvent,
    ) {
        if (createRequestFailed(response) || response.operationType == OperationType.VALIDATE) {
            logger.info("Not sending entity to storage because it is a validate event or create request failed")
            return
        }

        val coordinate =
            ResourceCoordinate(
                request.orgId,
                request.domainName,
                request.packageName,
                request.resourceName,
            )

        resourceWritePipeline.apply(
            ResourceIngest(
                coordinate = coordinate,
                resourceId = response.value.identifier,
                resource = storageMapper.convertValue(response.value.resource, coordinate.toResourceClass()),
                timestamp = Instant.ofEpochMilli(response.handledAt),
            ),
        )
    }

    /**
     * Runs the block inside one Mongo transaction, retrying up to [TRANSACTION_ATTEMPTS] times
     * when Mongo flags the failure as transient (for example a write conflict with the expiry
     * sweeper). Any other failure, or running out of attempts, rethrows and rolls back.
     *
     * The `!!` is there because [TransactionTemplate.execute] is a Java API that only returns
     * null when the callback itself returns null. Our block always returns a value, and Spring
     * reports rollback and errors by throwing, never by returning null, so null cannot happen
     * here.
     */
    private fun <T> inTransactionWithRetry(block: () -> T): T {
        var attempts = 0
        while (true) {
            try {
                return mongoTransactionTemplate.execute { block() }!!
            } catch (exception: RuntimeException) {
                attempts++
                if (attempts >= TRANSACTION_ATTEMPTS || !exception.isTransientTransactionError()) throw exception
                logger.warn("Retrying Mongo transaction after transient error (attempt {})", attempts, exception)
            }
        }
    }

    /**
     * Walks the cause chain (this, cause, cause of cause, and so on) and returns true if any
     * exception in it is a [MongoException] labeled transient. [generateSequence] builds that
     * chain lazily: it starts with this and keeps calling `it.cause` until it hits null. Needed
     * because Spring wraps the driver's exception, so the label is rarely on the outermost one.
     */
    private fun Throwable.isTransientTransactionError(): Boolean =
        generateSequence(this) { it.cause }.any {
            (it as? MongoException)?.hasErrorLabel(MongoException.TRANSIENT_TRANSACTION_ERROR_LABEL) == true
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

    private fun StoredEvent.toCoordinate(): ResourceCoordinate =
        ResourceCoordinate(
            request.orgId,
            request.domainName,
            request.packageName,
            request.resourceName,
        )

    companion object {
        private const val TRANSACTION_ATTEMPTS = 3
    }
}
