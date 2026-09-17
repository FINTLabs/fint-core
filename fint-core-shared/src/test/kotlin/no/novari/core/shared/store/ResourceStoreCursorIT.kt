package no.novari.core.shared.store

import com.mongodb.client.MongoClients
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant

/**
 * Tests the reads behind cursor paging. Seven entries are stored, and three of them share the
 * timestamp 20 so that the tie handling is exercised. The order is createdAt and then id, so the
 * entries come out as A, B1, B2, B3, C, D, E. An anchor is the last entry of the page the client
 * already has. A page after it starts with the entries that share its timestamp and have a larger
 * id, then continues with later entries. A page before it works the same way backwards and is
 * still returned in ascending order.
 */
@Testcontainers
class ResourceStoreCursorIT {
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
            listOf(
                "A" to 10L,
                "B1" to 20L,
                "B2" to 20L,
                "B3" to 20L,
                "C" to 30L,
                "D" to 40L,
                "E" to 50L,
            ).map { (id, timestamp) -> save(id, timestamp) },
        )
    }

    @Test
    fun `the first page without an anchor is the same as the offset page at zero`() {
        assertThat(ids(store.findPageAfter(null, null, 3, collection))).containsExactly("A", "B1", "B2")
        assertThat(ids(store.findPageAfter(null, null, 3, collection))).isEqualTo(
            ids(
                store.findPage(
                    null,
                    3,
                    0,
                    collection,
                ),
            ),
        )
    }

    @Test
    fun `a page after an anchor continues with the entries that follow it`() {
        assertThat(ids(store.findPageAfter(anchor("C"), null, 2, collection))).containsExactly("D", "E")
    }

    @Test
    fun `a page after an anchor skips entries with the same timestamp and a lower id`() {
        assertThat(ids(store.findPageAfter(anchor("B1"), null, 3, collection))).containsExactly("B2", "B3", "C")
    }

    @Test
    fun `a page after an anchor can be filled by entries with the same timestamp alone`() {
        assertThat(ids(store.findPageAfter(anchor("B1"), null, 2, collection))).containsExactly("B2", "B3")
    }

    @Test
    fun `a page after the last entry is empty`() {
        assertThat(store.findPageAfter(anchor("E"), null, 3, collection)).isEmpty()
    }

    @Test
    fun `a page after an anchor stays inside the timestamp filter`() {
        assertThat(ids(store.findPageAfter(anchor("B1"), since(30, 3), 2, collection))).containsExactly("C", "D")
        assertThat(ids(store.findPageAfter(anchor("C"), since(30, 3), 2, collection))).containsExactly("D", "E")
    }

    @Test
    fun `a page after an anchor gives the same rows whichever index serves the filter`() {
        val storeReadingThroughCreatedAt = ResourceStore(template, FintResourceBsonConverter(), deltaHintThreshold = 0)

        val throughLastModified = ids(store.findPageAfter(anchor("B1"), since(30, 3), 3, collection))
        val throughCreatedAt = ids(storeReadingThroughCreatedAt.findPageAfter(anchor("B1"), since(30, 3), 3, collection))

        assertThat(throughLastModified).containsExactly("C", "D", "E")
        assertThat(throughCreatedAt).isEqualTo(throughLastModified)
    }

    @Test
    fun `an entry updated after the anchor keeps its place in the page`() {
        store.saveAll(listOf(save("B2", 60)))

        assertThat(ids(store.findPageAfter(anchor("B1"), null, 3, collection))).containsExactly("B2", "B3", "C")
    }

    @Test
    fun `a page before an anchor returns the preceding entries in ascending order`() {
        assertThat(ids(store.findPageBefore(anchor("D"), null, 2, collection))).containsExactly("B3", "C")
    }

    @Test
    fun `a page before an anchor takes entries with the same timestamp and a lower id first`() {
        assertThat(ids(store.findPageBefore(anchor("B3"), null, 2, collection))).containsExactly("B1", "B2")
        assertThat(ids(store.findPageBefore(anchor("B2"), null, 3, collection))).containsExactly("A", "B1")
    }

    @Test
    fun `a page before the first entry is empty`() {
        assertThat(store.findPageBefore(anchor("A"), null, 3, collection)).isEmpty()
    }

    @Test
    fun `a page before an anchor stays inside the timestamp filter`() {
        assertThat(ids(store.findPageBefore(anchor("E"), since(30, 3), 3, collection))).containsExactly("C", "D")
    }

    private fun anchor(id: String): PageAnchor {
        val entry = store.findByResourceId(id, collection) ?: error("no entry $id")
        return PageAnchor(entry.createdAt, entry.id)
    }

    private fun since(
        timestamp: Long,
        matches: Long,
    ) = SinceFilter(Instant.ofEpochMilli(timestamp), matches)

    private fun ids(entries: List<ResourceEntry>) = entries.map { it.id }

    private fun save(
        id: String,
        timestamp: Long,
    ) = Save(id, collection, Elev(systemId = Identifikator(identifikatorverdi = id)), Instant.ofEpochMilli(timestamp))
}
