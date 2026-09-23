package no.fintlabs.adapter.gateway.sync

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.gateway.config.EvictionProperties
import no.fintlabs.adapter.gateway.mongoTestContainer
import no.fintlabs.adapter.gateway.storage.EvictionService
import no.fintlabs.adapter.gateway.storage.InlineEvictionRunner
import no.fintlabs.adapter.gateway.storage.MongoTransactions
import no.fintlabs.adapter.gateway.storage.ResourceWritePipeline
import no.fintlabs.adapter.models.sync.SyncType
import no.novari.core.shared.json.FintJson
import no.novari.core.shared.kafka.EntityHeaders.DOMAIN_NAME
import no.novari.core.shared.kafka.EntityHeaders.LAST_MODIFIED
import no.novari.core.shared.kafka.EntityHeaders.ORG_ID
import no.novari.core.shared.kafka.EntityHeaders.PACKAGE_NAME
import no.novari.core.shared.kafka.EntityHeaders.RESOURCE_NAME
import no.novari.core.shared.kafka.EntityHeaders.SYNC_CORRELATION_ID
import no.novari.core.shared.kafka.EntityHeaders.SYNC_MARKER
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TOTAL_SIZE
import no.novari.core.shared.kafka.EntityHeaders.SYNC_TYPE
import no.novari.core.shared.kafka.toHeaderBytes
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeFactory
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.relation.RelationEdgeWrite
import no.novari.core.shared.relation.mergeInto
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.Save
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.apache.kafka.clients.consumer.ConsumerRecord
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

@Testcontainers
class SyncEvictionIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO = mongoTestContainer()

        private const val BEFORE = 1_000L
        private const val DURING = 2_000L
        private const val AFTER = 3_000L
        private const val BATCH_SIZE = 2
    }

    private val factory by lazy { SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "eviction-it") }
    private val mongoTemplate by lazy { MongoTemplate(factory) }
    private val transactions by lazy { MongoTransactions(TransactionTemplate(MongoTransactionManager(factory)), factory) }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val meterRegistry = SimpleMeterRegistry()
    private val bufferReader by lazy { readerEvictingWith(resourceStore, relationEdgeStore) }

    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elevforhold")
    private val edgeCollection = "fintlabs_no_relation_edges"
    private val elevforholdCollection = "fintlabs_no_utdanning_elev_elevforhold"
    private val elevCollection = "fintlabs_no_utdanning_elev_elev"
    private val storageMapper = FintJson.storageMapper()
    private var nextOffset = 0L

    @BeforeEach
    fun clean() {
        listOf(
            edgeCollection,
            elevforholdCollection,
            elevCollection,
            SyncProgressStore.COLLECTION_NAME,
            FullSyncStatusStore.COLLECTION_NAME,
        ).forEach { mongoTemplate.remove(Query(), it) }
        nextOffset = 0
    }

    @Test
    fun `a completed full sync removes what it did not carry and keeps what it did`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-OLD", writtenAt = BEFORE),
                elevforholdRecord("EF-KEEP", writtenAt = BEFORE),
            ),
        )

        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertEquals(listOf("EF-KEEP"), storedIds(elevforholdCollection))
    }

    @Test
    fun `the edges an evicted resource owned go with it, so its back-link stops being served`() {
        bufferReader.readMessage(listOf(elevforholdRecord("EF-OLD", writtenAt = BEFORE)))
        assertEquals(listOf("EF-OLD"), edgesTargeting("elevnummer", "E-1").map { it.sourceId })

        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertTrue(
            edgesTargeting("elevnummer", "E-1").none { it.sourceId == "EF-OLD" },
            "the evicted Elevforhold must stop supplying a back-link to the Elev it pointed at",
        )
        assertEquals(
            listOf("EF-KEEP"),
            edgesTargeting("elevnummer", "E-1").map { it.sourceId },
            "the surviving Elevforhold keeps supplying its own",
        )
    }

    @Test
    fun `edges pointing at an evicted resource stay because their sources still declare the link`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-1", writtenAt = BEFORE, elevnummer = "E-GONE"),
                elevRecord("E-GONE", writtenAt = BEFORE),
                elevRecord("E-KEEP", writtenAt = BEFORE),
            ),
        )

        bufferReader.readMessage(
            listOf(elevRecord("E-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertEquals(listOf("E-KEEP"), storedIds(elevCollection))
        assertEquals(listOf("EF-1"), storedIds(elevforholdCollection))
        assertEquals(listOf("EF-1"), edgesTargeting("elevnummer", "E-GONE").map { it.sourceId })
    }

    @Test
    fun `a resource that comes back after eviction is served with its back-links at once`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-1", writtenAt = BEFORE, elevnummer = "E-BACK"),
                elevRecord("E-BACK", writtenAt = BEFORE),
                elevRecord("E-KEEP", writtenAt = BEFORE),
            ),
        )
        bufferReader.readMessage(
            listOf(elevRecord("E-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )
        assertEquals(listOf("E-KEEP"), storedIds(elevCollection))

        bufferReader.readMessage(
            listOf(elevRecord("E-BACK", writtenAt = AFTER, sync = SyncMetadataFixture("S-2", SyncType.DELTA, totalSize = 1))),
        )

        val entry = resourceStore.findByResourceId("E-BACK", elevCollection)!!
        val elev = Elev(elevnummer = Identifikator(identifikatorverdi = "E-BACK"))
        edgesTargeting("elevnummer", "E-BACK").mergeInto(listOf(entry to (elev as FintResource)))

        assertEquals(listOf("systemid" to "EF-1"), elev.links["elevforhold"]?.map { it.idField to it.idValue })
    }

    @Test
    fun `a delta sync evicts nothing`() {
        bufferReader.readMessage(listOf(elevforholdRecord("EF-OLD", writtenAt = BEFORE)))

        bufferReader.readMessage(
            listOf(
                elevforholdRecord(
                    "EF-NEW",
                    writtenAt = DURING,
                    sync = SyncMetadataFixture("S-1", SyncType.DELTA, totalSize = 1),
                ),
            ),
        )

        assertEquals(listOf("EF-NEW", "EF-OLD"), storedIds(elevforholdCollection).sorted())
    }

    @Test
    fun `a full sync that has not delivered everything it announced evicts nothing`() {
        bufferReader.readMessage(listOf(elevforholdRecord("EF-OLD", writtenAt = BEFORE)))

        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-NEW", writtenAt = DURING, sync = fullSync("S-1", totalSize = 2))),
        )

        assertEquals(listOf("EF-NEW", "EF-OLD"), storedIds(elevforholdCollection).sorted())
    }

    @Test
    fun `a completed full sync records when it completed, which is where the resource TTL counts from`() {
        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-1", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertNotNull(fullSyncStatus()?.lastCompletedAt)
    }

    @Test
    fun `a full sync that has not delivered everything it announced records no completion`() {
        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-1", writtenAt = DURING, sync = fullSync("S-1", totalSize = 2))),
        )

        assertNull(fullSyncStatus())
    }

    @Test
    fun `a full sync carrying nothing resets the resource, edges included`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-1", writtenAt = BEFORE),
                elevforholdRecord("EF-2", writtenAt = BEFORE),
            ),
        )
        assertEquals(2, storedIds(elevforholdCollection).size)

        bufferReader.readMessage(listOf(resetMarker("S-1", writtenAt = DURING)))

        assertTrue(storedIds(elevforholdCollection).isEmpty())
        assertTrue(mongoTemplate.find(Query(), RelationEdge::class.java, edgeCollection).isEmpty())
    }

    @Test
    fun `a redelivered batch is not counted twice, so the sync does not finish early`() {
        bufferReader.readMessage(listOf(elevforholdRecord("EF-OLD", writtenAt = BEFORE)))

        val firstHalf =
            listOf(
                elevforholdRecord("EF-A", writtenAt = DURING, sync = fullSync("S-1", totalSize = 4), offset = 10),
                elevforholdRecord("EF-B", writtenAt = DURING, sync = fullSync("S-1", totalSize = 4), offset = 11),
            )

        bufferReader.readMessage(firstHalf)
        bufferReader.readMessage(firstHalf)

        assertTrue(
            storedIds(elevforholdCollection).contains("EF-OLD"),
            "folding the same two records twice must not push the sync to its total of four",
        )

        bufferReader.readMessage(
            firstHalf +
                listOf(
                    elevforholdRecord("EF-C", writtenAt = DURING, sync = fullSync("S-1", totalSize = 4), offset = 12),
                    elevforholdRecord("EF-D", writtenAt = DURING, sync = fullSync("S-1", totalSize = 4), offset = 13),
                ),
        )

        assertEquals(
            listOf("EF-A", "EF-B", "EF-C", "EF-D"),
            storedIds(elevforholdCollection).sorted(),
            "a redelivery overlapping the two already folded still counts the two beyond it",
        )
    }

    @Test
    fun `records of one sync spread across partitions still add up`() {
        bufferReader.readMessage(listOf(elevforholdRecord("EF-OLD", writtenAt = BEFORE)))

        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-A", writtenAt = DURING, sync = fullSync("S-1", totalSize = 2), partition = 0, offset = 40),
                elevforholdRecord("EF-B", writtenAt = DURING, sync = fullSync("S-1", totalSize = 2), partition = 3, offset = 7),
            ),
        )

        assertEquals(listOf("EF-A", "EF-B"), storedIds(elevforholdCollection).sorted())
    }

    @Test
    fun `a resource written after the sync started survives it`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-OLD", writtenAt = BEFORE),
                elevforholdRecord("EF-EVENT", writtenAt = DURING + 500),
            ),
        )

        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-SYNCED", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertEquals(
            listOf("EF-EVENT", "EF-SYNCED"),
            storedIds(elevforholdCollection).sorted(),
            "a client write during the sync is newer than the threshold, so the adapter not naming it does not remove it",
        )
    }

    @Test
    fun `an eviction larger than one batch removes everything the sync did not carry`() {
        bufferReader.readMessage((1..5).map { elevforholdRecord("EF-OLD-$it", writtenAt = BEFORE) })

        bufferReader.readMessage(
            listOf(elevforholdRecord("EF-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertEquals(listOf("EF-KEEP"), storedIds(elevforholdCollection))
        assertEquals(listOf("EF-KEEP"), edgesTargeting("elevnummer", "E-1").map { it.sourceId })
        assertEquals(5.0, evictedCount("fint.core.eviction.resources"), "batches of two, two and one add up to five")
        assertEquals(5.0, evictedCount("fint.core.eviction.edges"))
    }

    @Test
    fun `a batch that fails keeps its resources and their edges together, the batches before it are done`() {
        val failingStore =
            object : ResourceStore(mongoTemplate, FintResourceBsonConverter()) {
                private var deletes = 0

                override fun deleteStaleByIds(
                    ids: Collection<String>,
                    threshold: Instant,
                    collectionName: String,
                ): Long {
                    if (++deletes == 2) throw IllegalStateException("resource delete failed")
                    return super.deleteStaleByIds(ids, threshold, collectionName)
                }
            }
        val reader = readerEvictingWith(failingStore, relationEdgeStore)

        reader.readMessage((1..5).map { elevforholdRecord("EF-OLD-$it", writtenAt = BEFORE) })
        reader.readMessage(
            listOf(elevforholdRecord("EF-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        val remaining = storedIds(elevforholdCollection).sorted()
        assertEquals(4, remaining.size, "the first batch of two is gone, the failed batch and the one after it stay")
        assertTrue("EF-KEEP" in remaining)
        assertEquals(
            remaining,
            edgesTargeting("elevnummer", "E-1").map { it.sourceId }.sorted(),
            "the failed batch had deleted its edges inside the transaction, the rollback must bring them back",
        )
    }

    @Test
    fun `a resource refreshed while its batch is being evicted survives with its edge`() {
        val outsideTemplate =
            MongoTemplate(SimpleMongoClientDatabaseFactory(MongoClients.create(MONGO.connectionString), "eviction-it"))
        val outsideResourceStore =
            ResourceStore(outsideTemplate, FintResourceBsonConverter()).apply { prepareCollection(elevforholdCollection) }
        val outsideEdgeStore = RelationEdgeStore(outsideTemplate).apply { prepareCollection(edgeCollection) }
        var refreshed: String? = null
        val interceptingEdgeStore =
            object : RelationEdgeStore(mongoTemplate) {
                override fun deleteBySources(
                    collectionName: String,
                    sourceType: String,
                    sourceIds: Collection<String>,
                ): Long {
                    if (refreshed == null) {
                        val resourceId = sourceIds.first()
                        refreshed = resourceId
                        refreshOutside(resourceId, AFTER, outsideResourceStore, outsideEdgeStore)
                    }
                    return super.deleteBySources(collectionName, sourceType, sourceIds)
                }
            }
        val reader = readerEvictingWith(resourceStore, interceptingEdgeStore)

        reader.readMessage((1..5).map { elevforholdRecord("EF-OLD-$it", writtenAt = BEFORE) })
        reader.readMessage(
            listOf(elevforholdRecord("EF-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        val survivor = checkNotNull(refreshed)
        assertEquals(listOf("EF-KEEP", survivor).sorted(), storedIds(elevforholdCollection).sorted())
        assertEquals(
            listOf("EF-KEEP", survivor).sorted(),
            edgesTargeting("elevnummer", "E-1").map { it.sourceId }.sorted(),
            "the survivor keeps supplying its back-link, the evicted ones stop",
        )
        assertEquals(
            Instant.ofEpochMilli(AFTER),
            resourceStore.findByResourceId(survivor, elevforholdCollection)!!.lastModified,
            "the refreshed write is what is stored, not the old one",
        )
    }

    private data class SyncMetadataFixture(
        val corrId: String,
        val type: SyncType,
        val totalSize: Long,
    )

    private fun fullSync(
        corrId: String,
        totalSize: Long,
    ) = SyncMetadataFixture(corrId, SyncType.FULL, totalSize)

    private fun elevforholdRecord(
        resourceId: String,
        writtenAt: Long,
        elevnummer: String = "E-1",
        sync: SyncMetadataFixture? = null,
        partition: Int = 0,
        offset: Long = nextOffset++,
    ): ConsumerRecord<String, String> =
        record(resourceId, elevforhold(resourceId, elevnummer), "elevforhold", writtenAt, sync, partition, offset)

    private fun elevforhold(
        resourceId: String,
        elevnummer: String,
    ): Elevforhold =
        Elevforhold(systemId = Identifikator(identifikatorverdi = resourceId)).apply {
            addLink("elev", Link("elevnummer", elevnummer))
        }

    private fun elevRecord(
        resourceId: String,
        writtenAt: Long,
        sync: SyncMetadataFixture? = null,
        partition: Int = 0,
        offset: Long = nextOffset++,
    ): ConsumerRecord<String, String> =
        record(
            resourceId,
            Elev(elevnummer = Identifikator(identifikatorverdi = resourceId)),
            "elev",
            writtenAt,
            sync,
            partition,
            offset,
        )

    private fun resetMarker(
        corrId: String,
        writtenAt: Long,
        partition: Int = 0,
        offset: Long = nextOffset++,
    ): ConsumerRecord<String, String> =
        record(
            corrId,
            null,
            "elevforhold",
            writtenAt,
            SyncMetadataFixture(corrId, SyncType.FULL, totalSize = 0),
            partition,
            offset,
        ).apply { headers().add(SYNC_MARKER, byteArrayOf(1)) }

    private fun record(
        resourceId: String,
        resource: FintResource?,
        resourceName: String,
        writtenAt: Long,
        sync: SyncMetadataFixture?,
        partition: Int,
        offset: Long,
    ): ConsumerRecord<String, String> =
        ConsumerRecord<String, String>(
            "buffer-topic",
            partition,
            offset,
            resourceId,
            resource?.let(storageMapper::writeValueAsString),
        ).apply {
            headers().add(ORG_ID, "fintlabs.no".toByteArray())
            headers().add(DOMAIN_NAME, "utdanning".toByteArray())
            headers().add(PACKAGE_NAME, "elev".toByteArray())
            headers().add(RESOURCE_NAME, resourceName.toByteArray())
            headers().add(LAST_MODIFIED, writtenAt.toHeaderBytes())
            sync?.let {
                headers().add(SYNC_TYPE, byteArrayOf(it.type.ordinal.toByte()))
                headers().add(SYNC_CORRELATION_ID, it.corrId.toByteArray())
                headers().add(SYNC_TOTAL_SIZE, it.totalSize.toHeaderBytes())
            }
        }

    /**
     * Wires a reader whose eviction goes through the given stores, so a test can make eviction
     * fail or act between batches while the writes before it use the plain stores.
     */
    private fun readerEvictingWith(
        evictionResourceStore: ResourceStore,
        evictionEdgeStore: RelationEdgeStore,
    ): BufferReader =
        BufferReader(
            ResourceWritePipeline(resourceStore, relationEdgeStore, transactions),
            SyncCompletionTracker(
                SyncProgressStore(mongoTemplate),
                FullSyncStatusStore(mongoTemplate),
                EvictionService(
                    evictionResourceStore,
                    evictionEdgeStore,
                    transactions,
                    meterRegistry,
                    EvictionProperties(batchSize = BATCH_SIZE),
                ),
                InlineEvictionRunner(),
            ),
        )

    /**
     * Writes the resource again through stores bound to another connection, so the write lands
     * outside the transaction open on the calling thread, the way a concurrent writer's would.
     */
    private fun refreshOutside(
        resourceId: String,
        writtenAt: Long,
        outsideResourceStore: ResourceStore,
        outsideEdgeStore: RelationEdgeStore,
    ) {
        val resource = elevforhold(resourceId, "E-1")
        outsideResourceStore.saveAll(listOf(Save(resourceId, elevforholdCollection, resource, Instant.ofEpochMilli(writtenAt))))
        outsideEdgeStore.applyAll(
            RelationEdgeFactory
                .createRelationEdges(coordinate, resourceId, resource)
                .map { RelationEdgeWrite.Save(edgeCollection, it) },
        )
    }

    private fun evictedCount(counter: String): Double =
        meterRegistry
            .get(counter)
            .tag("resource", "utdanning/elev/elevforhold")
            .counter()
            .count()

    private fun fullSyncStatus(): FullSyncStatus? =
        mongoTemplate.findById(elevforholdCollection, FullSyncStatus::class.java, FullSyncStatusStore.COLLECTION_NAME)

    private fun storedIds(collectionName: String): List<String> =
        mongoTemplate
            .findAll(org.bson.Document::class.java, collectionName)
            .map { it.getString("_id") }

    private fun edgesTargeting(
        field: String,
        value: String,
    ): List<RelationEdge> =
        relationEdgeStore.findByTargets(
            edgeCollection,
            "utdanning/elev/elev",
            listOf(IdentifierRef(field, value)),
        )
}
