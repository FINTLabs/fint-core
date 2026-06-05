package no.novari.fint.core.consumer.resource.event

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.consumer.config.EventCacheProperties
import no.novari.fint.core.consumer.config.MongoTestcontainerInitializer
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the Mongo-backed [EventStatusStore] against a Testcontainers Mongo instance.
 */
class EventStatusStoreTest {
    private lateinit var mongoTemplate: MongoTemplate
    private lateinit var store: EventStatusStore

    @BeforeEach
    fun setUp() {
        val factory =
            SimpleMongoClientDatabaseFactory(
                MongoTestcontainerInitializer.MONGO.getReplicaSetUrl("fintcache-event"),
            )
        mongoTemplate = MongoTemplate(factory)
        mongoTemplate.dropCollection(EventStatusStore.COLLECTION)
        store = EventStatusStore(mongoTemplate, jacksonObjectMapper(), EventCacheProperties())
    }

    @Test
    fun `storeRequest makes the request exist with no response yet`() {
        store.storeRequest(request("c1"))

        assertTrue(store.requestExists("c1"))
        assertNull(store.getResponse("c1"))
    }

    @Test
    fun `attachResponse on an existing request stores it`() {
        store.storeRequest(request("c1"))

        assertTrue(store.attachResponse("c1", response("c1")))
        assertEquals("c1", store.getResponse("c1")?.corrId)
    }

    @Test
    fun `attachResponse with no request is dropped`() {
        assertFalse(store.attachResponse("missing", response("missing")))
        assertNull(store.getResponse("missing"))
        assertFalse(store.requestExists("missing"))
    }

    @Test
    fun `ttl index is configured to expire on the document date`() {
        store.storeRequest(request("c1"))

        val ttlIndex =
            mongoTemplate
                .getCollection(EventStatusStore.COLLECTION)
                .listIndexes()
                .firstOrNull { it.getString("name") == "event_status_ttl_idx" }

        assertEquals(0L, (ttlIndex?.get("expireAfterSeconds") as Number).toLong())
    }

    private fun request(corrId: String) =
        RequestFintEvent().apply {
            this.corrId = corrId
            resourceName = "elevfravar"
            created = System.currentTimeMillis()
        }

    private fun response(corrId: String) = ResponseFintEvent.builder().corrId(corrId).build()
}
