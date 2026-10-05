package no.novari.core.shared.store

import com.mongodb.client.MongoClients
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.bson.Document
import org.bson.types.Binary
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.mongodb.MongoDBContainer
import java.time.Instant
import java.util.Date

@Testcontainers
class ResourceStoreIT {
    private val store = ResourceStore(template, FintResourceBsonConverter())

    companion object {
        @Container
        val mongo = MongoDBContainer("mongo:8.0.4")

        private val collection = "test_org_no_utdanning_elev_elev"
        private val otherCollection = "other_org_no_utdanning_elev_elev"
        private val personCollection = "test_org_no_utdanning_elev_person"
        private val coordinate = ResourceCoordinate("test.org.no", "utdanning", "elev", "elev")
        private val base = Instant.parse("2026-09-11T10:00:00Z")
        private val template by lazy { MongoTemplate(MongoClients.create(mongo.connectionString), "test") }
    }

    @BeforeEach
    fun dropCollections() {
        template.dropCollection(collection)
        template.dropCollection(otherCollection)
        template.dropCollection(personCollection)
        template.dropCollection("test_org_no_relation_edges")
    }

    @Test
    fun `the first save stores the content with both timestamps and the hash`() {
        val outcomes = store.saveAll(listOf(save("1", base)))

        val entry = store.findByResourceId("1", collection)!!
        assertThat(outcomes.map { it.result }).containsExactly(WriteResult.NEW)
        assertThat(entry.lastModified).isEqualTo(base)
        assertThat(entry.lastDelivered).isEqualTo(base)
        assertThat(storedHash("1")).isNotNull()
    }

    @Test
    fun `saving the same content again keeps lastModified and the hash and moves lastDelivered`() {
        store.saveAll(listOf(save("1", base)))
        val hash = storedHash("1")

        val outcomes = store.saveAll(listOf(save("1", base.plusSeconds(60))))

        val entry = store.findByResourceId("1", collection)!!
        assertThat(outcomes.map { it.result }).containsExactly(WriteResult.UNCHANGED)
        assertThat(outcomes.single().changedData).isFalse()
        assertThat(entry.lastModified).isEqualTo(base)
        assertThat(entry.lastDelivered).isEqualTo(base.plusSeconds(60))
        assertThat(storedHash("1")).isEqualTo(hash)
    }

    @Test
    fun `saving changed content moves both timestamps and stores the new hash`() {
        store.saveAll(listOf(save("1", base)))
        val hash = storedHash("1")

        val outcomes = store.saveAll(listOf(Save("1", collection, elevWithNumber("1"), base.plusSeconds(60))))

        val entry = store.findByResourceId("1", collection)!!
        assertThat(outcomes.map { it.result }).containsExactly(WriteResult.CHANGED)
        assertThat(outcomes.single().changedData).isTrue()
        assertThat(entry.lastModified).isEqualTo(base.plusSeconds(60))
        assertThat(entry.lastDelivered).isEqualTo(base.plusSeconds(60))
        assertThat(entry.identifiers).hasSize(2)
        assertThat(storedHash("1")).isNotEqualTo(hash)
    }

    @Test
    fun `a save with changed content but older than the stored delivery is stale`() {
        store.saveAll(listOf(save("1", base.plusSeconds(60))))

        val outcomes = store.saveAll(listOf(Save("1", collection, elevWithNumber("1"), base)))

        val entry = store.findByResourceId("1", collection)!!
        assertThat(outcomes.map { it.result }).containsExactly(WriteResult.STALE)
        assertThat(entry.identifiers).hasSize(1)
        assertThat(entry.lastDelivered).isEqualTo(base.plusSeconds(60))
    }

    @Test
    fun `an unchanged save older than the stored delivery is stale and leaves lastDelivered alone`() {
        store.saveAll(listOf(save("1", base.plusSeconds(60))))

        val outcomes = store.saveAll(listOf(save("1", base)))

        assertThat(outcomes.map { it.result }).containsExactly(WriteResult.STALE)
        assertThat(store.findByResourceId("1", collection)!!.lastDelivered).isEqualTo(base.plusSeconds(60))
    }

    @Test
    fun `a document stored before lastDelivered existed is guarded by its lastModified`() {
        insertWithoutDeliveryTime("1", base)

        val stale = store.saveAll(listOf(save("1", base.minusSeconds(60))))
        assertThat(stale.map { it.result }).containsExactly(WriteResult.STALE)

        val redelivered = store.saveAll(listOf(save("1", base.plusSeconds(60))))

        val entry = store.findByResourceId("1", collection)!!
        assertThat(redelivered.map { it.result }).containsExactly(WriteResult.CHANGED)
        assertThat(entry.lastDelivered).isEqualTo(base.plusSeconds(60))
        assertThat(storedHash("1")).isNotNull()
    }

    @Test
    fun `a delete older than the stored write leaves the document unchanged`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base.minusSeconds(60))))

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base)
    }

    @Test
    fun `a delete newer than the stored write removes the document`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base.plusSeconds(60))))

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `a delete with the same timestamp as the stored write removes the document`() {
        store.saveAll(listOf(save("1", base)))

        store.applyAll(listOf(Delete("1", collection, base)))

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `a delete is guarded by the last delivery, not by the last content change`() {
        store.saveAll(listOf(save("1", base)))
        store.saveAll(listOf(save("1", base.plusSeconds(60))))

        store.applyAll(listOf(Delete("1", collection, base.plusSeconds(30))))

        assertThat(store.findByResourceId("1", collection)).isNotNull()
    }

    @Test
    fun `a delete of a document stored before lastDelivered existed is guarded by its lastModified`() {
        insertWithoutDeliveryTime("1", base)

        store.applyAll(listOf(Delete("1", collection, base.minusSeconds(60))))
        assertThat(store.findByResourceId("1", collection)).isNotNull()

        store.applyAll(listOf(Delete("1", collection, base)))
        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `in one batch a newer save wins over an older delete listed after it`() {
        store.applyAll(
            listOf(
                save("1", base.plusSeconds(60)),
                Delete("1", collection, base),
            ),
        )

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base.plusSeconds(60))
    }

    @Test
    fun `in one batch a newer delete wins over an older save listed after it`() {
        store.applyAll(
            listOf(
                Delete("1", collection, base.plusSeconds(60)),
                save("1", base),
            ),
        )

        assertThat(store.findByResourceId("1", collection)).isNull()
    }

    @Test
    fun `in one batch two saves for the same id keep the newer one regardless of order`() {
        store.applyAll(
            listOf(
                Save("1", collection, elevWithNumber("1"), base.plusSeconds(60)),
                save("1", base),
            ),
        )

        val entry = store.findByResourceId("1", collection)
        assertThat(entry!!.lastModified).isEqualTo(base.plusSeconds(60))
        assertThat(entry.identifiers).hasSize(2)
    }

    @Test
    fun `a delete for an id that was never stored does nothing`() {
        store.applyAll(listOf(Delete("missing", collection, base)))

        assertThat(template.getCollection(collection).countDocuments()).isZero()
    }

    @Test
    fun `a delete in one collection does not touch the same id in another collection`() {
        store.saveAll(listOf(save("1", base), Save("1", otherCollection, elev("1"), base)))

        store.applyAll(listOf(Delete("1", otherCollection, base.plusSeconds(60))))

        assertThat(store.findByResourceId("1", collection)).isNotNull()
        assertThat(store.findByResourceId("1", otherCollection)).isNull()
    }

    @Test
    fun `applyAll reports a save older than the stored one as stale and a first save as new`() {
        store.saveAll(listOf(save("1", base)))
        val late = save("1", base.minusSeconds(60))
        val fresh = save("2", base)

        val outcomes = store.applyAll(listOf(late, fresh))

        assertThat(outcomes).containsExactlyInAnyOrder(
            WriteOutcome(late, WriteResult.STALE),
            WriteOutcome(fresh, WriteResult.NEW),
        )
    }

    @Test
    fun `applyAll reports only the newest of two saves for the same id`() {
        val newest = save("1", base.plusSeconds(60))

        val outcomes = store.applyAll(listOf(save("1", base), newest))

        assertThat(outcomes).containsExactly(WriteOutcome(newest, WriteResult.NEW))
    }

    @Test
    fun `applyAll reports a delete older than the stored write as stale`() {
        store.saveAll(listOf(save("1", base)))
        val late = Delete("1", collection, base.minusSeconds(60))

        val outcomes = store.applyAll(listOf(late))

        assertThat(outcomes).containsExactly(WriteOutcome(late, WriteResult.STALE))
    }

    @Test
    fun `applyAll reports a delete for an id that was never stored as deleted`() {
        val delete = Delete("missing", collection, base)

        val outcomes = store.applyAll(listOf(delete))

        assertThat(outcomes).containsExactly(WriteOutcome(delete, WriteResult.DELETED))
        assertThat(outcomes.single().changedData).isTrue()
    }

    @Test
    fun `a save less than a millisecond newer than the stored write takes effect and is stored`() {
        store.saveAll(listOf(save("1", base)))
        val slightlyNewer = Save("1", collection, elevWithNumber("1"), base.plusNanos(500_000))

        val outcomes = store.applyAll(listOf(slightlyNewer))

        assertThat(outcomes).containsExactly(WriteOutcome(slightlyNewer, WriteResult.CHANGED))
        assertThat(store.findByResourceId("1", collection)!!.identifiers).hasSize(2)
    }

    @Test
    fun `a save less than a millisecond older than the stored write is stale and not stored`() {
        store.saveAll(listOf(save("1", base.plusMillis(1))))
        val slightlyOlder = Save("1", collection, elevWithNumber("1"), base.plusNanos(999_999))

        val outcomes = store.applyAll(listOf(slightlyOlder))

        assertThat(outcomes).containsExactly(WriteOutcome(slightlyOlder, WriteResult.STALE))
        assertThat(store.findByResourceId("1", collection)!!.identifiers).hasSize(1)
    }

    @Test
    fun `only the ids that are stored come back as stored`() {
        store.saveAll(listOf(save("1", base), save("2", base)))

        assertThat(store.findStoredIds(listOf("1", "2", "9"), collection)).containsExactlyInAnyOrder("1", "2")
    }

    @Test
    fun `the stored coordinates of an org are the model types it has a collection for`() {
        store.saveAll(listOf(save("1", base)))
        store.saveAll(listOf(Save("1", personCollection, Person(), base)))
        store.saveAll(listOf(Save("1", otherCollection, elev("1"), base)))
        template.insert(Document("_id", "edge-1"), "test_org_no_relation_edges")

        val stored = store.storedCoordinates(OrgId.from("test.org.no"))

        assertThat(stored).containsExactly(
            ResourceCoordinate("test.org.no", "utdanning", "elev", "elev"),
            ResourceCoordinate("test.org.no", "utdanning", "elev", "person"),
        )
    }

    @Test
    fun `an emptied collection still counts as a stored coordinate`() {
        store.saveAll(listOf(save("1", base)))
        store.applyAll(listOf(Delete("1", collection, base.plusSeconds(60))))

        assertThat(store.storedCoordinates(OrgId.from("test.org.no")))
            .containsExactly(ResourceCoordinate("test.org.no", "utdanning", "elev", "elev"))
    }

    @Test
    fun `an org with no collections has no stored coordinates`() {
        assertThat(store.storedCoordinates(OrgId.from("test.org.no"))).isEmpty()
    }

    @Test
    fun `findIdsOlderThan reads the last delivery, and lastModified where there is none`() {
        store.saveAll(listOf(save("delivered-early", base), save("delivered-late", base.plusSeconds(120))))
        insertWithoutDeliveryTime("modified-early", base)
        insertWithoutDeliveryTime("modified-late", base.plusSeconds(120))

        val ids = store.findIdsOlderThan(base.plusSeconds(60), 10, collection)

        assertThat(ids).containsExactlyInAnyOrder("delivered-early", "modified-early")
    }

    @Test
    fun `findIdsOlderThan does not read a resource delivered again unchanged as old`() {
        store.saveAll(listOf(save("1", base)))
        store.saveAll(listOf(save("1", base.plusSeconds(120))))

        assertThat(store.findIdsOlderThan(base.plusSeconds(60), 10, collection)).isEmpty()
    }

    @Test
    fun `deleteStaleByIds removes only the entries delivered before the threshold`() {
        store.saveAll(listOf(save("delivered-early", base), save("delivered-late", base.plusSeconds(120))))
        insertWithoutDeliveryTime("modified-early", base)
        insertWithoutDeliveryTime("modified-late", base.plusSeconds(120))
        val all = listOf("delivered-early", "delivered-late", "modified-early", "modified-late")

        val deleted = store.deleteStaleByIds(all, base.plusSeconds(60), collection)

        assertThat(deleted).isEqualTo(2)
        assertThat(store.findAll(null, collection).map { it.id }).containsExactlyInAnyOrder("delivered-late", "modified-late")
    }

    @Test
    fun `getLastUpdated is the newest delivery, even one that changed nothing`() {
        store.saveAll(listOf(save("1", base), save("2", base.plusSeconds(120))))
        assertThat(store.getLastUpdated(coordinate)).isEqualTo(base.plusSeconds(120))

        store.saveAll(listOf(save("1", base.plusSeconds(300))))

        assertThat(store.getLastUpdated(coordinate)).isEqualTo(base.plusSeconds(300))
        assertThat(store.findByResourceId("1", collection)!!.lastModified).isEqualTo(base)
    }

    @Test
    fun `getLastUpdated falls back to lastModified while no document has a delivery time`() {
        insertWithoutDeliveryTime("1", base)
        insertWithoutDeliveryTime("2", base.plusSeconds(120))

        assertThat(store.getLastUpdated(coordinate)).isEqualTo(base.plusSeconds(120))
    }

    @Test
    fun `getLastUpdated is null for a resource type with nothing stored`() {
        assertThat(store.getLastUpdated(coordinate)).isNull()
    }

    private fun storedHash(id: String): Binary? =
        template.findById(id, Document::class.java, collection)?.get("contentHash", Binary::class.java)

    /**
     * Writes a document the way the store did before `lastDelivered` and `contentHash` existed.
     */
    private fun insertWithoutDeliveryTime(
        id: String,
        lastModified: Instant,
    ) {
        template.save(
            Document("_id", id)
                .append("data", FintResourceBsonConverter().toDocument(elev(id)))
                .append("identifiers", listOf(Document("field", "systemid").append("value", id)))
                .append("createdAt", Date.from(lastModified))
                .append("lastModified", Date.from(lastModified)),
            collection,
        )
    }

    private fun save(
        id: String,
        timestamp: Instant,
    ) = Save(id, collection, elev(id), timestamp)

    private fun elevWithNumber(id: String) =
        Elev(
            systemId = Identifikator(identifikatorverdi = id),
            elevnummer = Identifikator(identifikatorverdi = "E-$id"),
        )

    private fun elev(id: String) = Elev(systemId = Identifikator(identifikatorverdi = id))
}
