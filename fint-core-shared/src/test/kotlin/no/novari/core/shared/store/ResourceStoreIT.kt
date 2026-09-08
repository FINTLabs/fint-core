package no.novari.core.shared.store

import com.mongodb.client.MongoClients
import org.bson.Document
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.query.Criteria
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals

/**
 * Tests the store methods behind `total_items`. Five entries are stored with timestamps 10 to 50.
 * A timestamp filter uses `lastModified >= since`, so the entry exactly at the timestamp is
 * included. The count and the page use the same filter, so the count always matches what the
 * page shows.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ResourceStoreIT {
    private val mongo = MongoDBContainer("mongo:7.0")
    private lateinit var template: MongoTemplate
    private lateinit var store: ResourceStore
    private val collection = "fintlabs_no_utdanning_elev_elev"

    @BeforeAll
    fun start() {
        mongo.start()
        template = MongoTemplate(SimpleMongoClientDatabaseFactory(MongoClients.create(mongo.connectionString), "test"))
        store = ResourceStore(template, FintResourceBsonConverter())
    }

    @AfterAll
    fun stop() = mongo.stop()

    @BeforeEach
    fun seed() {
        template.dropCollection(collection)
        listOf("A" to 10L, "B" to 20L, "C" to 30L, "D" to 40L, "E" to 50L).forEach { (id, timestamp) ->
            template.save(
                Document("_id", id)
                    .append("data", Document())
                    .append("identifiers", emptyList<Document>())
                    .append("createdAt", Date.from(Instant.ofEpochMilli(timestamp)))
                    .append("lastModified", Date.from(Instant.ofEpochMilli(timestamp))),
                collection,
            )
        }
    }

    @Test
    fun `count without criteria is the collection size`() {
        assertEquals(5, store.count(null, collection))
    }

    @Test
    fun `count with a timestamp includes entries at the boundary`() {
        assertEquals(3, store.count(since(30), collection))
    }

    @Test
    fun `count with a timestamp after every entry is 0`() {
        assertEquals(0, store.count(since(100), collection))
    }

    @Test
    fun `a page without timestamp skips by offset`() {
        assertEquals(listOf("C", "D"), store.findPage(null, 2, 2, collection).map { it.id })
    }

    @Test
    fun `a page with a timestamp starts at the boundary`() {
        assertEquals(listOf("C", "D"), store.findPage(since(30), 2, 0, collection).map { it.id })
    }

    @Test
    fun `offset counts from the timestamp, not from the start of the collection`() {
        assertEquals(listOf("E"), store.findPage(since(30), 2, 2, collection).map { it.id })
    }

    @Test
    fun `findAll with a timestamp returns everything from the timestamp onward`() {
        assertEquals(listOf("C", "D", "E"), store.findAll(since(30), collection).map { it.id })
    }

    private fun since(timestamp: Long) = Criteria.where("lastModified").gte(Instant.ofEpochMilli(timestamp))
}
