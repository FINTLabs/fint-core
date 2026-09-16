package no.fintlabs.provider.storage

import com.mongodb.client.MongoClients
import no.fintlabs.provider.mongoTestContainer
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.ResourceEntry
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.Save
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.mongodb.MongoTransactionManager
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import org.springframework.data.mongodb.core.query.Criteria
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Update
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Instant
import java.util.Date
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs the transaction around one batch against a real Mongo replica set, without Spring.
 *
 * When [MongoTransactions] opens a transaction, Spring keeps the Mongo session for it on the
 * current thread, stored under the database factory that opened it. Every [MongoTemplate]
 * built on that factory checks the thread for such a session before each call and, if one is
 * there, runs the call inside that transaction. So the template, the stores and the pipeline
 * in this test all share one [factory] and end up in the same transaction.
*/
@Testcontainers
class ResourceWritePipelineIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val DATABASE = "pipeline-it"
        private const val RESOURCE_COLLECTION = "fintlabs_no_utdanning_elev_elevforhold"
        private const val EDGE_COLLECTION = "fintlabs_no_relation_edges"
        private val base = Instant.parse("2026-09-15T10:00:00Z")
        private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")
    }

    private val client by lazy { MongoClients.create(MONGO.connectionString) }
    private val factory by lazy { SimpleMongoClientDatabaseFactory(client, DATABASE) }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy {
        MongoTransactions(
            TransactionTemplate(MongoTransactionManager(factory)),
            factory,
        )
    }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val pipeline by lazy { ResourceWritePipeline(resourceStore, relationEdgeStore, transactions) }

    private val outsideTemplate by lazy { MongoTemplate(SimpleMongoClientDatabaseFactory(client, DATABASE)) }
    private val outsideStore by lazy { ResourceStore(outsideTemplate, FintResourceBsonConverter()) }

    @BeforeEach
    fun clean() {
        mongoTemplate.remove(Query(), RESOURCE_COLLECTION)
        mongoTemplate.remove(Query(), EDGE_COLLECTION)
    }

    @Test
    fun `when the edge write fails the resource write is rolled back with it`() {
        val failingEdgeStore =
            object : RelationEdgeStore(mongoTemplate) {
                override fun applyAll(writes: List<RelationEdgeWrite>): Unit = throw IllegalStateException("edge write failed")
            }
        val failingPipeline = ResourceWritePipeline(resourceStore, failingEdgeStore, transactions)

        assertThrows<IllegalStateException> { failingPipeline.applyAll(listOf(save("EF-123", base))) }

        assertNull(storedResource("EF-123"))
        assertTrue(allEdges().isEmpty())
    }

    @Test
    fun `a nested call joins the open transaction and is rolled back with it`() {
        pipeline.prepare(coordinate)
        var attempts = 0

        assertThrows<IllegalStateException> {
            transactions.inTransaction {
                attempts++
                pipeline.applyAll(listOf(save("EF-123", base)))
                throw IllegalStateException("outer failure")
            }
        }

        assertEquals(1, attempts)
        assertNull(storedResource("EF-123"))
        assertTrue(allEdges().isEmpty())
    }

    @Test
    fun `a write landing between the read and the update aborts the batch and the retry decides again`() {
        pipeline.applyAll(listOf(save("EF-123", base, elevLink = "E-456")))
        val bumped = base.plusSeconds(120)
        var attempts = 0

        transactions.inTransaction {
            attempts++
            mongoTemplate.findById("EF-123", ResourceEntry::class.java, RESOURCE_COLLECTION)
            if (attempts == 1) bumpOutside("EF-123", bumped)
            pipeline.applyAll(listOf(save("EF-123", base.plusSeconds(60), elevLink = "E-NEW")))
        }

        assertEquals(2, attempts)
        assertEquals(bumped, storedResource("EF-123")!!.lastModified)
        assertEquals(listOf("E-456"), elevTargets())
    }

    @Test
    fun `an insert landing between the read and the update aborts the batch and the retry decides again`() {
        pipeline.prepare(coordinate)
        val inserted = base.plusSeconds(120)
        var attempts = 0

        transactions.inTransaction {
            attempts++
            mongoTemplate.findById("EF-123", ResourceEntry::class.java, RESOURCE_COLLECTION)
            if (attempts == 1) insertOutside("EF-123", inserted)
            pipeline.applyAll(listOf(save("EF-123", base.plusSeconds(60), elevLink = "E-NEW")))
        }

        assertEquals(2, attempts)
        assertEquals(inserted, storedResource("EF-123")!!.lastModified)
        assertTrue(allEdges().isEmpty())
    }

    private fun bumpOutside(
        id: String,
        lastModified: Instant,
    ) {
        outsideTemplate.updateFirst(
            Query.query(Criteria.where("_id").`is`(id)),
            Update().set("lastModified", Date.from(lastModified)),
            RESOURCE_COLLECTION,
        )
    }

    private fun insertOutside(
        id: String,
        lastModified: Instant,
    ) {
        outsideStore.saveAll(listOf(Save(id, RESOURCE_COLLECTION, elevforhold(id, "E-456"), lastModified)))
    }

    private fun save(
        id: String,
        timestamp: Instant,
        elevLink: String = "E-456",
    ) = ResourceIngest.Save(
        resource = elevforhold(id, elevLink),
        coordinate = coordinate,
        resourceId = id,
        timestamp = timestamp,
    )

    private fun elevforhold(
        systemId: String,
        elevLink: String,
    ) = Elevforhold(systemId = Identifikator(identifikatorverdi = systemId)).apply {
        addLink("elev", Link("elevnummer", elevLink))
        addLink("skole", Link("skolenummer", "S-1"))
    }

    private fun storedResource(id: String) = resourceStore.findByResourceId(id, RESOURCE_COLLECTION)

    private fun allEdges() = mongoTemplate.find(Query(), RelationEdge::class.java, EDGE_COLLECTION)

    private fun elevTargets() = allEdges().filter { it.targetType == "utdanning/elev/elev" }.map { it.targetIdValue }
}
