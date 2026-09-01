package no.novari.core.shared.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
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
        request: RequestFintEvent,
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

    fun findPending(
        collectionName: String,
        now: Instant,
        scope: EventScope? = null,
        limit: Int = 0,
    ): List<RequestFintEvent> = findInState(collectionName, Criteria.where(DEADLINE).gt(now), scope, limit)

    fun findExpired(
        collectionName: String,
        now: Instant,
    ): List<RequestFintEvent> = findInState(collectionName, Criteria.where(DEADLINE).lte(now))

    fun markAnswered(
        response: ResponseFintEvent,
        collectionName: String,
    ): ClaimOutcome {
        val claimed =
            template.findAndModify(
                Query.query(
                    Criteria().andOperator(
                        Criteria.where(ID).`is`(response.corrId),
                        Criteria.where(STATUS).`is`(EventState.PENDING),
                        Criteria.where(DEADLINE).gt(Instant.ofEpochMilli(response.handledAt)),
                    ),
                ),
                Update()
                    .set(STATUS, EventState.ANSWERED)
                    .set(RESPONSE, response.toStoredJson())
                    .set(HANDLED_AT, Instant.ofEpochMilli(response.handledAt)),
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
        deadlineCriteria: Criteria,
        scope: EventScope? = null,
        limit: Int = 0,
    ): List<RequestFintEvent> {
        ensureIndexes(collectionName)

        val filters =
            buildList {
                add(Criteria.where(STATUS).`is`(EventState.PENDING))
                add(deadlineCriteria)
                scope?.let {
                    add(Criteria.where(DOMAIN_NAME).`is`(it.domainName))
                    it.packageName?.let { name -> add(Criteria.where(PACKAGE_NAME).`is`(name)) }
                    it.resourceName?.let { name -> add(Criteria.where(RESOURCE_NAME).`is`(name)) }
                }
            }

        val query =
            Query
                .query(Criteria().andOperator(*filters.toTypedArray()))
                .with(Sort.by(Sort.Direction.ASC, CREATED))

        if (limit > 0) query.limit(limit)

        return template
            .find(query, EventDocument::class.java, collectionName)
            .map { it.parseRequest() }
    }

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
        private const val RESPONSE = "response"
        private const val HANDLED_AT = "handledAt"
        private const val DEADLINE = "deadline"
        private const val CREATED = "created"
        private const val EXPIRE_AT = "expireAt"
        private const val DOMAIN_NAME = "domainName"
        private const val PACKAGE_NAME = "packageName"
        private const val RESOURCE_NAME = "resourceName"
    }
}
