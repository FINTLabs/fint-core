package no.novari.core.shared.event

import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import org.springframework.data.domain.Sort
import org.springframework.data.mongodb.core.FindAndModifyOptions
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.index.Index
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.stereotype.Service
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

sealed interface ClaimOutcome {
    data object Claimed : ClaimOutcome

    data object AlreadyAnswered : ClaimOutcome

    data object Expired : ClaimOutcome

    data object NotFound : ClaimOutcome
}

@Service
class EventStore(
    private val template: MongoTemplate,
) {
    private val indexedCollections = ConcurrentHashMap.newKeySet<String>()

    fun save(
        request: EventRequest,
        expireAt: Instant,
        collectionName: String,
    ) {
        ensureIndexes(collectionName)
        template.insert(request.toEventDocument(expireAt), collectionName)
    }

    fun findByCorrId(
        corrId: String,
        collectionName: String,
    ): StoredEvent? = template.findById(corrId, EventDocument::class.java, collectionName)?.toStoredEvent()

    /**
     * The pending events a v1 adapter is served. Reads are left out, because a v1 adapter cannot
     * read them. Documents from before the v2 shape have no operation field and are not reads.
     */
    fun findPending(
        collectionName: String,
        now: Instant,
        scope: EventScope? = null,
        limit: Int = 0,
    ): List<EventRequest> =
        findInState(
            collectionName,
            listOfNotNull(
                Criteria.where(DEADLINE).gt(now),
                Criteria.where(OPERATION).ne(EventOperation.READ.name),
                scope?.toCriteria(),
            ),
            limit,
        )

    /**
     * The pending events a v2 adapter is served: only the resources and operations in [scopes].
     */
    fun findPendingFor(
        collectionName: String,
        now: Instant,
        scopes: Collection<OperationScope>,
        limit: Int = 0,
    ): List<EventRequest> {
        if (scopes.isEmpty()) return emptyList()

        return findInState(
            collectionName,
            listOf(
                Criteria.where(DEADLINE).gt(now),
                Criteria().orOperator(scopes.map { it.toCriteria() }),
            ),
            limit,
        )
    }

    fun findExpired(
        collectionName: String,
        now: Instant,
    ): List<EventRequest> = findInState(collectionName, listOf(Criteria.where(DEADLINE).lte(now)))

    /**
     * Stores the answer if the event is still pending and [handledAt] is before its deadline. The
     * request is written again in the v2 shape, so a document from before the v2 shape never
     * ends up with a v1 request and a v2 answer.
     */
    fun markAnswered(
        request: EventRequest,
        response: EventResponse,
        handledAt: Instant,
        collectionName: String,
    ): ClaimOutcome {
        val claimed =
            template.findAndModify(
                Query.query(
                    Criteria().andOperator(
                        Criteria.where(ID).`is`(response.corrId),
                        Criteria.where(STATUS).`is`(EventState.PENDING),
                        Criteria.where(DEADLINE).gt(handledAt),
                    ),
                ),
                Update()
                    .set(STATUS, EventState.ANSWERED)
                    .set(REQUEST, request.toStoredJson())
                    .set(RESPONSE, response.toStoredJson())
                    .set(HANDLED_AT, handledAt)
                    .set(FORMAT, CURRENT_FORMAT)
                    .set(OPERATION, request.operation?.name),
                FindAndModifyOptions.options().returnNew(true),
                EventDocument::class.java,
                collectionName,
            )

        if (claimed != null) return ClaimOutcome.Claimed

        val existing =
            template.findById(response.corrId, EventDocument::class.java, collectionName)
                ?: return ClaimOutcome.NotFound

        return when (existing.status) {
            EventState.ANSWERED -> ClaimOutcome.AlreadyAnswered
            else -> ClaimOutcome.Expired
        }
    }

    fun markExpired(
        corrId: String,
        collectionName: String,
        now: Instant,
    ): Boolean =
        template.findAndModify(
            Query.query(
                Criteria().andOperator(
                    Criteria.where(ID).`is`(corrId),
                    Criteria.where(STATUS).`is`(EventState.PENDING),
                    Criteria.where(DEADLINE).lte(now),
                ),
            ),
            Update().set(STATUS, EventState.EXPIRED),
            FindAndModifyOptions.options().returnNew(true),
            EventDocument::class.java,
            collectionName,
        ) != null

    private fun findInState(
        collectionName: String,
        criteria: List<Criteria>,
        limit: Int = 0,
    ): List<EventRequest> {
        ensureIndexes(collectionName)

        val filters = listOf(Criteria.where(STATUS).`is`(EventState.PENDING)) + criteria

        val query =
            Query
                .query(Criteria().andOperator(*filters.toTypedArray()))
                .with(Sort.by(Sort.Direction.ASC, CREATED))

        if (limit > 0) query.limit(limit)

        return template
            .find(query, EventDocument::class.java, collectionName)
            .map { it.parseRequest() }
    }

    private fun EventScope.toCriteria(): Criteria =
        Criteria().andOperator(
            listOfNotNull(
                Criteria.where(DOMAIN_NAME).`is`(domainName),
                packageName?.let { Criteria.where(PACKAGE_NAME).`is`(it) },
                resourceName?.let { Criteria.where(RESOURCE_NAME).`is`(it) },
            ),
        )

    private fun OperationScope.toCriteria(): Criteria =
        Criteria().andOperator(
            Criteria.where(DOMAIN_NAME).`is`(domainName),
            Criteria.where(PACKAGE_NAME).`is`(packageName),
            Criteria.where(RESOURCE_NAME).`is`(resourceName),
            Criteria.where(OPERATION).`in`(operations.map { it.name }),
        )

    private fun ensureIndexes(collectionName: String) {
        if (!indexedCollections.add(collectionName)) return

        template.indexOps(collectionName).createIndex(
            Index().on(EXPIRE_AT, Sort.Direction.ASC).expire(0),
        )
        template.indexOps(collectionName).createIndex(
            Index().on(STATUS, Sort.Direction.ASC).on(DEADLINE, Sort.Direction.ASC),
        )
        template.indexOps(collectionName).createIndex(
            Index().on(CREATED, Sort.Direction.ASC),
        )
    }

    companion object {
        private const val ID = "_id"
        private const val STATUS = "status"
        private const val REQUEST = "request"
        private const val RESPONSE = "response"
        private const val FORMAT = "format"
        private const val OPERATION = "operation"
        private const val HANDLED_AT = "handledAt"
        private const val DEADLINE = "deadline"
        private const val CREATED = "created"
        private const val EXPIRE_AT = "expireAt"
        private const val DOMAIN_NAME = "domainName"
        private const val PACKAGE_NAME = "packageName"
        private const val RESOURCE_NAME = "resourceName"
    }
}
