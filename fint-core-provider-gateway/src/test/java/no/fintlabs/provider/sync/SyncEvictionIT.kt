package no.fintlabs.provider.sync

import com.mongodb.client.MongoClients
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.storage.EvictionService
import no.fintlabs.provider.storage.ResourceWritePipeline
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
import no.novari.core.shared.relation.RelationEdge
import no.novari.core.shared.relation.RelationEdgeStore
import no.novari.core.shared.store.FintResourceBsonConverter
import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.fint.core.model.utdanning.elev.Elevforhold
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Query
import org.testcontainers.containers.MongoDBContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Testcontainers
class SyncEvictionIT {
    companion object {
        @Container
        @JvmStatic
        val MONGO: MongoDBContainer = MongoDBContainer("mongo:7.0")

        private const val BEFORE = 1_000L
        private const val DURING = 2_000L
    }

    private val mongoTemplate by lazy { MongoTemplate(MongoClients.create(MONGO.connectionString), "eviction-it") }
    private val relationEdgeStore by lazy { RelationEdgeStore(mongoTemplate) }
    private val resourceStore by lazy { ResourceStore(mongoTemplate, FintResourceBsonConverter()) }
    private val bufferReader by lazy {
        BufferReader(
            ResourceWritePipeline(resourceStore, relationEdgeStore),
            SyncCompletionTracker(
                SyncProgressStore(mongoTemplate),
                EvictionService(resourceStore, relationEdgeStore, SimpleMeterRegistry()),
            ),
        )
    }

    private val edgeCollection = "fintlabs_no_relation_edges"
    private val elevforholdCollection = "fintlabs_no_utdanning_elev_elevforhold"
    private val elevCollection = "fintlabs_no_utdanning_elev_elev"
    private val storageMapper = FintJson.storageMapper()
    private var nextOffset = 0L

    @BeforeEach
    fun clean() {
        listOf(edgeCollection, elevforholdCollection, elevCollection, SyncProgressStore.COLLECTION_NAME)
            .forEach { mongoTemplate.remove(Query(), it) }
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
    fun `edges pointing at an evicted resource go too`() {
        bufferReader.readMessage(
            listOf(
                elevforholdRecord("EF-1", writtenAt = BEFORE, elevnummer = "E-GONE"),
                elevRecord("E-GONE", writtenAt = BEFORE),
                elevRecord("E-KEEP", writtenAt = BEFORE),
            ),
        )
        assertEquals(1, edgesTargeting("elevnummer", "E-GONE").size)

        bufferReader.readMessage(
            listOf(elevRecord("E-KEEP", writtenAt = DURING, sync = fullSync("S-1", totalSize = 1))),
        )

        assertEquals(listOf("E-KEEP"), storedIds(elevCollection))
        assertTrue(
            edgesTargeting("elevnummer", "E-GONE").isEmpty(),
            "an edge whose target is gone would never render again, and nothing else would remove it",
        )
        assertEquals(
            listOf("EF-1"),
            storedIds(elevforholdCollection),
            "the Elevforhold that owned the edge is untouched, only the edge went",
        )
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
    ): ConsumerRecord<String, String> {
        val elevforhold =
            Elevforhold(systemId = Identifikator(identifikatorverdi = resourceId)).apply {
                addLink("elev", Link("elevnummer", elevnummer))
            }

        return record(resourceId, elevforhold, "elevforhold", writtenAt, sync, partition, offset)
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
