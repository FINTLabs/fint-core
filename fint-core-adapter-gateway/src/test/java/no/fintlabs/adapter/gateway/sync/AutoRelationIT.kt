package no.fintlabs.adapter.gateway.sync

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.fintlabs.adapter.gateway.storage.EvictionService
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.toHeaderBytes
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.mergeInto
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.bson.Document
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.query.Query
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Write and read path of autorelation against a real Mongo, without Spring or Kafka: records are
 * handed straight to [BufferReader.readMessage] and the stores run on a hand-built
 * [MongoTemplate]. The Kafka-to-reader wiring is covered by [BufferIT]. Deliberately no Spring
 * context: the provider's Application component-scans test packages too, so a nested test
 * configuration here would leak into every full-app context.
 */
@Testcontainers
class AutoRelationIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "autorelation-it") }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val evictionService by lazy { EvictionService(resourceStore, relationEdgeStore, SimpleMeterRegistry()) }
    private val syncProgressStore by lazy { SyncProgressStore(mongoTemplate) }
    private val bufferReader by lazy {
        BufferReader(
            ResourceWritePipeline(resourceStore, relationEdgeStore, transactions),
            SyncCompletionTracker(syncProgressStore, evictionService),
        )
    }

    private val edgeCollection = "fintlabs_no_relation_edges"
    private val storageMapper = FintJson.storageMapper()

    @BeforeEach
    fun clean() {
        mongoTemplate.remove(Query(), edgeCollection)
        mongoTemplate.remove(Query(), "fintlabs_no_utdanning_elev_elevforhold")
    }

    @Test
    fun `only qualifying links become edges, with fields as designed`() {
        bufferReader.readMessage(listOf(elevforholdRecord()))

        val edges = allEdges().sortedBy { it.targetType }
        assertEquals(2, edges.size, "expected edges for elev and skole only, got $edges")

        // Verify all expected fields in the RelationEdge
        val elevEdge = edges.first { it.targetType == "utdanning/elev/elev" }
        assertEquals("elevforhold", elevEdge.inverseName)
        assertEquals("elevnummer", elevEdge.targetIdField)
        assertEquals("E-456", elevEdge.targetIdValue)
        assertEquals("systemid", elevEdge.sourceIdField)
        assertEquals("EF-123", elevEdge.sourceIdValue)
        assertNotNull(elevEdge.createdAt)

        val skoleEdge = edges.first { it.targetType == "utdanning/utdanningsprogram/skole" }
        assertEquals("elevforhold", skoleEdge.inverseName)

        val storedResource =
            mongoTemplate.findById(
                "EF-123",
                Document::class.java,
                "fintlabs_no_utdanning_elev_elevforhold",
            )
        assertNotNull(storedResource, "the resource itself is still written alongside its edges")
    }

    @Test
    fun `reprocessing the same record changes nothing (keeps createdAt timestamp)`() {
        bufferReader.readMessage(listOf(elevforholdRecord()))
        val firstPass = allEdges().associateBy { it.id }

        bufferReader.readMessage(listOf(elevforholdRecord()))
        val secondPass = allEdges().associateBy { it.id }

        assertEquals(firstPass.keys, secondPass.keys)
        firstPass.forEach { (id, edge) ->
            assertEquals(edge.createdAt, secondPass[id]!!.createdAt, "createdAt must survive a re-sync")
        }
    }

    @Test
    fun `a tombstone for a resource that was never stored does not fail the batch`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord(resourceId = "EF-GONE", resource = null),
                elevforholdRecord(),
            ),
        )

        assertEquals(2, allEdges().size)
        assertNull(
            mongoTemplate.findById("EF-GONE", Document::class.java, "fintlabs_no_utdanning_elev_elevforhold"),
        )
    }

    @Test
    fun `a tombstone removes the edges the resource created and leaves other sources alone`() {
        bufferReader.readMessage(listOf(elevforholdRecord(resourceId = "EF-123"), elevforholdRecord(resourceId = "EF-999")))
        assertEquals(4, allEdges().size)

        bufferReader.readMessage(listOf(elevforholdRecord(resourceId = "EF-123", resource = null)))

        val remaining = allEdges()
        assertEquals(2, remaining.size)
        assertTrue(remaining.all { it.sourceId == "EF-999" })
        assertNull(mongoTemplate.findById("EF-123", Document::class.java, "fintlabs_no_utdanning_elev_elevforhold"))
        assertNotNull(mongoTemplate.findById("EF-999", Document::class.java, "fintlabs_no_utdanning_elev_elevforhold"))
    }

    @Test
    fun `written edges are found and merged onto the target they point at`() {
        bufferReader.readMessage(listOf(elevforholdRecord()))

        val elev = Elev(elevnummer = Identifikator(identifikatorverdi = "E-456"))
        val entry =
            ResourceEntry(
                id = "E-456",
                data = Document(),
                identifiers = listOf(IdentifierRef("elevnummer", "E-456"), IdentifierRef("systemid", "SYS-9")),
                createdAt = Instant.EPOCH,
                lastModified = Instant.EPOCH,
            )

        val edges =
            relationEdgeStore.findByTargets(
                edgeCollection,
                "utdanning/elev/elev",
                entry.identifiers,
            )
        edges.mergeInto(listOf(entry to (elev as FintResource)))

        val links = elev.links["elevforhold"]
        assertNotNull(links, "the merged back-link lands under the inverse relation name")
        assertEquals(listOf("systemid" to "EF-123"), links.map { it.idField to it.idValue })

        val allForType = relationEdgeStore.findAllByTargetType(edgeCollection, "utdanning/elev/elev")
        assertEquals(1, allForType.size)

        val unrelated =
            relationEdgeStore.findByTargets(
                edgeCollection,
                "utdanning/elev/elev",
                listOf(IdentifierRef("elevnummer", "E-999")),
            )
        assertTrue(unrelated.isEmpty())
    }

    @Test
    fun `a batch holding an old and a new version of the same source derives edges only from the new version`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord(resource = elevforhold(elevLink = "E-NEW"), lastModified = 2_000L),
                elevforholdRecord(resource = elevforhold(elevLink = "E-OLD"), lastModified = 1_000L),
            ),
        )

        val elevTargets = allEdges().filter { it.targetType == "utdanning/elev/elev" }.map { it.targetIdValue }
        assertEquals(listOf("E-NEW"), elevTargets)
    }

    @Test
    fun `a tombstone older than the stored resource leaves the resource and its edges alone`() {
        bufferReader.readMessage(listOf(elevforholdRecord(lastModified = 2_000L)))

        bufferReader.readMessage(listOf(elevforholdRecord(resource = null, lastModified = 1_000L)))

        assertEquals(2, allEdges().size)
        assertNotNull(mongoTemplate.findById("EF-123", Document::class.java, "fintlabs_no_utdanning_elev_elevforhold"))
    }

    private fun elevforhold(
        systemId: String = "EF-123",
        elevLink: String = "E-456",
    ) = Elevforhold(systemId = Identifikator(identifikatorverdi = systemId)).apply {
        addLink("elev", Link("elevnummer", elevLink))
        addLink("skole", Link("skolenummer", "S-1"))
        addLink("fravarsregistreringer", Link("systemid", "FR-1"))
        addLink("kategori", Link("systemid", "K-1"))
    }

    private fun elevforholdRecord(
        resourceId: String = "EF-123",
        resource: Elevforhold? = elevforhold(resourceId),
        lastModified: Long? = null,
    ): ConsumerRecord<String, String> =
        ConsumerRecord<String, String>(
            "buffer-topic",
            0,
            0L,
            resourceId,
            resource?.let(storageMapper::writeValueAsString),
        ).apply {
            headers().add(ORG_ID, "fintlabs.no".toByteArray())
            headers().add(DOMAIN_NAME, "utdanning".toByteArray())
            headers().add(PACKAGE_NAME, "elev".toByteArray())
            headers().add(RESOURCE_NAME, "elevforhold".toByteArray())
            lastModified?.let { headers().add(LAST_MODIFIED, it.toHeaderBytes()) }
        }

    private fun allEdges(): List<RelationEdge> = mongoTemplate.find(Query(), RelationEdge::class.java, edgeCollection)
}
