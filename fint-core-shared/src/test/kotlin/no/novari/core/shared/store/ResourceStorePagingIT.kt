package no.novari.core.shared.store

import com.mongodb.client.MongoClients
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant

/**
 * Tests the store reads behind `total_items` and `sinceTimeStamp`. Five entries are stored with
 * timestamps 10 to 50. A timestamp filter uses `lastModified >= since`, so the entry exactly at
 * the timestamp is included. The count and the page use the same filter, so the count always
 * matches what the page shows. Pages are ordered by `createdAt`, so an entry that is updated
 * later keeps its place in the order. The count without a filter comes from the counter Mongo
 * keeps for the collection, so one test deletes an entry and checks that the counter followed.
 */
@Testcontainers
class ResourceStorePagingIT {
    private val store = ResourceStore(template, FintResourceBsonConverter())

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:8.0.4")

        private val collection = "test_org_no_utdanning_elev_elev"
        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun seed() {
        template.dropCollection(collection)
        store.saveAll(
            listOf("A" to 10L, "B" to 20L, "C" to 30L, "D" to 40L, "E" to 50L).map { (id, timestamp) ->
                save(id, timestamp)
            },
        )
    }

    @Test
    fun `count without criteria is the collection size`() {
        assertThat(store.count(null, collection)).isEqualTo(5)
    }

    @Test
    fun `count without criteria follows a delete`() {
        store.applyAll(listOf(Delete("A", collection, Instant.ofEpochMilli(100))))

        assertThat(store.count(null, collection)).isEqualTo(4)
    }

    @Test
    fun `count with a timestamp includes entries at the boundary`() {
        assertThat(store.count(since(30), collection)).isEqualTo(3)
    }

    @Test
    fun `count with a timestamp after every entry is 0`() {
        assertThat(store.count(since(100), collection)).isZero()
    }

    @Test
    fun `a page without timestamp skips by offset`() {
        assertThat(ids(store.findPage(null, 2, 2, collection))).containsExactly("C", "D")
    }

    @Test
    fun `a page with a timestamp starts at the boundary`() {
        assertThat(ids(store.findPage(since(30), 2, 0, collection))).containsExactly("C", "D")
    }

    @Test
    fun `offset counts from the timestamp, not from the start of the collection`() {
        assertThat(ids(store.findPage(since(30), 2, 2, collection))).containsExactly("E")
    }

    @Test
    fun `findAll with a timestamp returns everything from the timestamp onward`() {
        assertThat(ids(store.findAll(since(30), collection))).containsExactly("C", "D", "E")
    }

    @Test
    fun `an entry updated after the timestamp keeps its original place in the page`() {
        store.saveAll(listOf(save("A", 60)))

        assertThat(store.count(since(30), collection)).isEqualTo(4)
        assertThat(ids(store.findPage(since(30), 2, 0, collection))).containsExactly("A", "C")
    }

    private fun since(timestamp: Long) = Criteria.where("lastModified").gte(Instant.ofEpochMilli(timestamp))

    private fun ids(entries: List<ResourceEntry>) = entries.map { it.id }

    private fun save(
        id: String,
        timestamp: Long,
    ) = Save(id, collection, Elev(systemId = Identifikator(identifikatorverdi = id)), Instant.ofEpochMilli(timestamp))
}
