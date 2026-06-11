package no.novari.fint.core.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.Filters
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import com.mongodb.client.model.Sorts
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import org.bson.Document
import org.bson.conversions.Bson
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Mongo-backed store for request/response event status, keyed by correlation id, shared by both
 * services: the consumer writes the request at publish time and reads status; the provider serves
 * pending requests to adapters straight from this collection (no Kafka request topic, no in-memory
 * cache — the provider stays stateless) and marks them responded synchronously when the adapter
 * answers, so a handled request is never re-served.
 *
 * One document per corrId holds the full serialized request plus queryable top-level fields
 * (orgId, domain/package/resource, timeToLive) and, once it arrives, the response. A TTL index on
 * `expireAt` removes request and response together after the resource's status-retention window.
 */
@Service
class EventStatusStore(
    private val mongoTemplate: MongoTemplate,
    private val objectMapper: ObjectMapper,
) {
    init {
        collection().createIndex(
            Indexes.ascending(FIELD_EXPIRE_AT),
            IndexOptions().name("event_status_ttl_idx").expireAfter(0, TimeUnit.SECONDS),
        )
        collection().createIndex(
            Indexes.ascending(FIELD_ORG_ID, FIELD_TIME_TO_LIVE),
            IndexOptions().name("event_status_pending_idx"),
        )
    }

    private fun collection(): MongoCollection<Document> = mongoTemplate.getCollection(COLLECTION)

    /**
     * Store the request at publish time. `expireAt` is the resource's status-retention window, so
     * the doc (request + any later response) ages out as one.
     */
    fun storeRequest(
        request: RequestFintEvent,
        expireAt: Long,
    ) {
        collection().replaceOne(
            Document(FIELD_ID, request.corrId),
            Document(FIELD_ID, request.corrId)
                .append(FIELD_REQUEST, objectMapper.writeValueAsString(request))
                .append(FIELD_EXPIRE_AT, Date(expireAt))
                .append(FIELD_ORG_ID, request.orgId)
                .append(FIELD_DOMAIN_NAME, request.domainName?.lowercase())
                .append(FIELD_PACKAGE_NAME, request.packageName?.lowercase())
                .append(FIELD_RESOURCE_NAME, request.resourceName?.lowercase())
                .append(FIELD_TIME_TO_LIVE, Date(request.timeToLive)),
            ReplaceOptions().upsert(true),
        )
    }

    /**
     * Attach a response to an existing, non-expired request doc. Returns `false` and stores nothing
     * if the request is absent or already past `expireAt`, so orphan responses are dropped.
     */
    fun attachResponse(
        corrId: String,
        response: ResponseFintEvent,
    ): Boolean =
        collection()
            .updateOne(
                activeFilter(corrId),
                Document("\$set", Document(FIELD_RESPONSE, objectMapper.writeValueAsString(response))),
            ).matchedCount > 0

    fun requestExists(corrId: String): Boolean = collection().find(activeFilter(corrId)).first() != null

    fun getRequest(corrId: String): RequestFintEvent? =
        collection()
            .find(activeFilter(corrId))
            .first()
            ?.getString(FIELD_REQUEST)
            ?.let { objectMapper.readValue(it, RequestFintEvent::class.java) }

    fun getResponse(corrId: String): ResponseFintEvent? =
        collection()
            .find(activeFilter(corrId))
            .first()
            ?.getString(FIELD_RESPONSE)
            ?.let { objectMapper.readValue(it, ResponseFintEvent::class.java) }

    /**
     * Pending = no response attached yet and the request's own time-to-live has not passed. Served
     * oldest-deadline-first so adapters see requests in roughly the order they will expire.
     */
    fun findPendingRequests(
        orgIds: Collection<String>,
        domainName: String?,
        packageName: String?,
        resourceName: String?,
        limit: Int,
    ): List<RequestFintEvent> {
        val filters =
            mutableListOf(
                Filters.`in`(FIELD_ORG_ID, orgIds),
                Filters.gt(FIELD_TIME_TO_LIVE, Date()),
                Filters.exists(FIELD_RESPONSE, false),
            )
        domainName?.takeIf { it.isNotBlank() }?.let { filters += Filters.eq(FIELD_DOMAIN_NAME, it.lowercase()) }
        packageName?.takeIf { it.isNotBlank() }?.let { filters += Filters.eq(FIELD_PACKAGE_NAME, it.lowercase()) }
        resourceName?.takeIf { it.isNotBlank() }?.let { filters += Filters.eq(FIELD_RESOURCE_NAME, it.lowercase()) }

        var cursor =
            collection()
                .find(Filters.and(filters as List<Bson>))
                .sort(Sorts.ascending(FIELD_TIME_TO_LIVE))
        if (limit > 0) cursor = cursor.limit(limit)

        return cursor
            .mapNotNull { doc ->
                doc.getString(FIELD_REQUEST)?.let { objectMapper.readValue(it, RequestFintEvent::class.java) }
            }.toList()
    }

    /**
     * Matches a doc only while it is still within its retention window. Reads honour `expireAt`
     * directly so status flips to "gone" promptly; the TTL index only handles physical deletion,
     * which Mongo runs lazily.
     */
    private fun activeFilter(corrId: String): Document = Document(FIELD_ID, corrId).append(FIELD_EXPIRE_AT, Document("\$gt", Date()))

    companion object {
        const val COLLECTION = "event_status"
        const val FIELD_ID = "_id"
        const val FIELD_REQUEST = "request"
        const val FIELD_RESPONSE = "response"
        const val FIELD_EXPIRE_AT = "expireAt"
        const val FIELD_ORG_ID = "orgId"
        const val FIELD_DOMAIN_NAME = "domainName"
        const val FIELD_PACKAGE_NAME = "packageName"
        const val FIELD_RESOURCE_NAME = "resourceName"
        const val FIELD_TIME_TO_LIVE = "timeToLive"
    }
}
