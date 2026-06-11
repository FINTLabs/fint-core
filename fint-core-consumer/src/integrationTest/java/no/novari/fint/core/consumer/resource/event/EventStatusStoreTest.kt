package no.novari.fint.core.consumer.resource.event

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.consumer.config.MongoTestcontainerInitializer
import no.novari.fint.core.shared.event.EventStatusStore
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the Mongo-backed [EventStatusStore] against a Testcontainers Mongo instance, including
 * the pending-request query the provider serves adapters from.
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
        store = EventStatusStore(mongoTemplate, jacksonObjectMapper())
    }

    @Test
    fun `storeRequest makes the request exist with no response yet`() {
        store.storeRequest(request("c1"), retention())

        assertTrue(store.requestExists("c1"))
        assertNull(store.getResponse("c1"))
        assertEquals("c1", store.getRequest("c1")?.corrId)
    }

    @Test
    fun `attachResponse on an existing request stores it`() {
        store.storeRequest(request("c1"), retention())

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
    fun `pending requests exclude responded and ttl-expired ones`() {
        store.storeRequest(request("pending"), retention())
        store.storeRequest(request("answered"), retention())
        store.storeRequest(request("expired", ttlMillis = -1_000), retention())
        store.attachResponse("answered", response("answered"))

        val pending = store.findPendingRequests(setOf(ORG), null, null, null, 0)

        assertEquals(listOf("pending"), pending.map { it.corrId })
    }

    @Test
    fun `pending requests filter by org domain package resource and honour the limit`() {
        store.storeRequest(request("other-org").apply { orgId = "rogfk.no" }, retention())
        store.storeRequest(request("other-domain").apply { domainName = "okonomi" }, retention())
        store.storeRequest(request("match-1", ttlMillis = 60_000), retention())
        store.storeRequest(request("match-2", ttlMillis = 120_000), retention())

        val filtered = store.findPendingRequests(setOf(ORG), "utdanning", "vurdering", "elevfravar", 0)
        assertEquals(listOf("match-1", "match-2"), filtered.map { it.corrId })

        val limited = store.findPendingRequests(setOf(ORG), null, null, null, 1)
        assertEquals(1, limited.size)
    }

    @Test
    fun `ttl index is configured to expire on the document date`() {
        store.storeRequest(request("c1"), retention())

        val ttlIndex =
            mongoTemplate
                .getCollection(EventStatusStore.COLLECTION)
                .listIndexes()
                .firstOrNull { it.getString("name") == "event_status_ttl_idx" }

        assertEquals(0L, (ttlIndex?.get("expireAfterSeconds") as Number).toLong())
    }

    private fun retention(): Long = System.currentTimeMillis() + 60 * 60 * 1000

    private fun request(
        corrId: String,
        ttlMillis: Long = 120_000,
    ) = RequestFintEvent().apply {
        this.corrId = corrId
        orgId = ORG
        domainName = "utdanning"
        packageName = "vurdering"
        resourceName = "elevfravar"
        created = System.currentTimeMillis()
        timeToLive = System.currentTimeMillis() + ttlMillis
    }

    private fun response(corrId: String) = ResponseFintEvent.builder().corrId(corrId).build()

    companion object {
        private const val ORG = "fintlabs.no"
    }
}
