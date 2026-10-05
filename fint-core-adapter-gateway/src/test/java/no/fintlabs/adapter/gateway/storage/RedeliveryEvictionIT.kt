package no.fintlabs.adapter.gateway.storage

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.gateway.config.EvictionProperties
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elevforhold
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
import java.util.Date
import kotlin.test.assertEquals

/**
 * Eviction after a full sync that delivers resources again without changing them. Such a
 * delivery only moves `lastDelivered`, so eviction has to read that, never `lastModified`.
 * Documents written before `lastDelivered` existed have only `lastModified`, which was their
 * delivery time back then, so eviction reads that for them.
 */
@Testcontainers
class RedeliveryEvictionIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val RESOURCE_COLLECTION = "fintlabs_no_utdanning_elev_elevforhold"
        private const val EDGE_COLLECTION = "fintlabs_no_relation_edges"
        private val before = Instant.parse("2026-09-23T01:00:00Z")
        private val syncStart = Instant.parse("2026-09-23T02:00:00Z")
        private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "redelivery-it") }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val pipeline by lazy { ResourceWritePipeline(resourceStore, relationEdgeStore, transactions, SimpleMeterRegistry()) }
    private val evictionService by lazy {
        EvictionService(resourceStore, relationEdgeStore, transactions, SimpleMeterRegistry(), EvictionProperties(batchSize = 2))
    }

    @BeforeEach
    fun clean() {
        mongoTemplate.remove(Query(), RESOURCE_COLLECTION)
        mongoTemplate.remove(Query(), EDGE_COLLECTION)
    }

    @Test
    fun `a full sync that delivers every resource unchanged evicts nothing`() {
        pipeline.applyAll(listOf(save("EF-1", before), save("EF-2", before)))

        pipeline.applyAll(listOf(save("EF-1", syncStart), save("EF-2", syncStart)))
        evictionService.evict(coordinate, syncStart)

        assertEquals(listOf("EF-1", "EF-2"), storedIds())
        assertEquals(listOf("EF-1", "EF-2"), edgeSources())
        storedIds().forEach { id ->
            val entry = resourceStore.findByResourceId(id, RESOURCE_COLLECTION)!!
            assertEquals(before, entry.lastModified, "$id did not change, so its content timestamp stays")
            assertEquals(syncStart, entry.lastDelivered, "$id was delivered by the sync")
        }
    }

    @Test
    fun `a full sync that leaves out an unchanged resource evicts it with its edges`() {
        pipeline.applyAll(listOf(save("EF-1", before), save("EF-2", before)))

        pipeline.applyAll(listOf(save("EF-1", syncStart)))
        evictionService.evict(coordinate, syncStart)

        assertEquals(listOf("EF-1"), storedIds())
        assertEquals(listOf("EF-1"), edgeSources())
    }

    @Test
    fun `an eviction larger than one batch spares every resource the sync delivered unchanged`() {
        pipeline.applyAll((1..5).map { save("EF-OLD-$it", before) } + (1..3).map { save("EF-KEEP-$it", before) })

        pipeline.applyAll((1..3).map { save("EF-KEEP-$it", syncStart) })
        val result = evictionService.evict(coordinate, syncStart)

        assertEquals(5, result.resources)
        assertEquals(listOf("EF-KEEP-1", "EF-KEEP-2", "EF-KEEP-3"), storedIds())
    }

    @Test
    fun `a document from before lastDelivered existed goes when its lastModified is older than the sync`() {
        insertWithoutDeliveryTime("EF-OLD", before)

        pipeline.applyAll(listOf(save("EF-KEEP", syncStart)))
        evictionService.evict(coordinate, syncStart)

        assertEquals(listOf("EF-KEEP"), storedIds())
    }

    @Test
    fun `a document from before lastDelivered existed stays when its lastModified is newer than the sync`() {
        insertWithoutDeliveryTime("EF-EVENT", syncStart.plusSeconds(1))

        pipeline.applyAll(listOf(save("EF-KEEP", syncStart)))
        evictionService.evict(coordinate, syncStart)

        assertEquals(listOf("EF-EVENT", "EF-KEEP"), storedIds())
    }

    /**
     * Writes a document the way the store did before `lastDelivered` and `contentHash` existed.
     */
    private fun insertWithoutDeliveryTime(
        id: String,
        lastModified: Instant,
    ) {
        mongoTemplate.save(
            Document("_id", id)
                .append("data", FintResourceBsonConverter().toDocument(elevforhold(id)))
                .append("identifiers", listOf(Document("field", "systemid").append("value", id)))
                .append("createdAt", Date.from(lastModified))
                .append("lastModified", Date.from(lastModified)),
            RESOURCE_COLLECTION,
        )
    }

    private fun save(
        id: String,
        timestamp: Instant,
    ) = ResourceIngest.Save(
        resource = elevforhold(id),
        coordinate = coordinate,
        resourceId = id,
        timestamp = timestamp,
    )

    private fun elevforhold(systemId: String) =
        Elevforhold(systemId = Identifikator(identifikatorverdi = systemId)).apply {
            addLink("elev", Link("elevnummer", "E-1"))
        }

    private fun storedIds(): List<String> = resourceStore.findAll(null, RESOURCE_COLLECTION).map { it.id }.sorted()

    private fun edgeSources(): List<String> =
        relationEdgeStore
            .findByTargets(EDGE_COLLECTION, "utdanning/elev/elev", listOf(IdentifierRef("elevnummer", "E-1")))
            .map { it.sourceId }
            .sorted()
}
