package no.fintlabs.consumer.resource.event

import com.fasterxml.jackson.databind.ObjectMapper
import com.mongodb.client.MongoCollection
import com.mongodb.client.model.IndexOptions
import com.mongodb.client.model.Indexes
import com.mongodb.client.model.ReplaceOptions
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.consumer.config.EventCacheProperties
import org.bson.Document
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Mongo-backed store for request/response event status, keyed by correlation id.
 *
 * One document per corrId holds the request (written at publish time) and, once it arrives, the
 * response. A TTL index on `expireAt` removes request and response together after the resource's
 * status-retention window, so they expire as one. A response whose request is absent (already
 * expired) is dropped — there are no orphans.
 *
 * Persisting this lets the response consumer continue from its committed offset instead of
 * replaying the event topic on every restart.
 */
@Service
class EventStatusStore(
    private val mongoTemplate: MongoTemplate,
    private val objectMapper: ObjectMapper,
    private val props: EventCacheProperties,
) {
    init {
        collection().createIndex(
            Indexes.ascending(FIELD_EXPIRE_AT),
            IndexOptions().name("event_status_ttl_idx").expireAfter(0, TimeUnit.SECONDS),
        )
    }

    private fun collection(): MongoCollection<Document> = mongoTemplate.getCollection(COLLECTION)

    /**
     * Store the request at publish time. `expireAt` is the resource's status-retention window from
     * now (this runs synchronously right after the request is created), so the doc (request + any
     * later response) ages out together. Measured against real store time rather than the event's
     * `created` so a fixed/test clock can't place it in the past.
     */
    fun storeRequest(request: RequestFintEvent) {
        val expireAt = System.currentTimeMillis() + props.getLifeCycleConfig(request.resourceName).eviction.toMillis()
        collection().replaceOne(
            Document(FIELD_ID, request.corrId),
            Document(FIELD_ID, request.corrId)
                .append(FIELD_REQUEST, objectMapper.writeValueAsString(request))
                .append(FIELD_EXPIRE_AT, Date(expireAt)),
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

    fun getResponse(corrId: String): ResponseFintEvent? =
        collection()
            .find(activeFilter(corrId))
            .first()
            ?.getString(FIELD_RESPONSE)
            ?.let { objectMapper.readValue(it, ResponseFintEvent::class.java) }

    /**
     * Matches a doc only while it is still within its retention window. Reads honour `expireAt`
     * directly so status flips to "gone" promptly; the TTL index only handles physical deletion,
     * which Mongo runs lazily.
     */
    private fun activeFilter(corrId: String): Document =
        Document(FIELD_ID, corrId).append(FIELD_EXPIRE_AT, Document("\$gt", Date()))

    companion object {
        const val COLLECTION = "event_status"
        const val FIELD_ID = "_id"
        const val FIELD_REQUEST = "request"
        const val FIELD_RESPONSE = "response"
        const val FIELD_EXPIRE_AT = "expireAt"
    }
}
