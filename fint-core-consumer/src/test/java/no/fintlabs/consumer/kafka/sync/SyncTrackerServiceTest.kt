package no.fintlabs.consumer.kafka.sync

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifySequence
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheEvictionService
import no.fintlabs.consumer.config.CaffeineCacheProperties
import no.fintlabs.consumer.kafka.KafkaConstants.LAST_MODIFIED
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_CORRELATION_ID
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TOTAL_SIZE
import no.fintlabs.consumer.kafka.KafkaConstants.SYNC_TYPE
import no.fintlabs.consumer.kafka.entity.EntityConsumerRecord
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecord.NULL_SIZE
import org.apache.kafka.common.header.internals.RecordHeader
import org.apache.kafka.common.header.internals.RecordHeaders
import org.apache.kafka.common.record.TimestampType
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.util.Optional
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals

class SyncTrackerServiceTest {
    private lateinit var evictionService: CacheEvictionService
    private lateinit var syncStatusProducer: SyncStatusProducer
    private lateinit var syncTracker: SyncTrackerService
    private lateinit var lastFullSync: LastCompletedFullSyncCache
    private val meterRegistry = SimpleMeterRegistry()
    private val cacheProperties: CaffeineCacheProperties = CaffeineCacheProperties()
    private val resourceName = "elevfravar"

    @BeforeEach
    fun setUp() {
        evictionService = mockk(relaxed = true)
        syncStatusProducer = mockk(relaxed = true)
        lastFullSync = mockk(relaxed = true)

        syncTracker =
            SyncTrackerService(
                syncStatusProducer,
                evictionService,
                meterRegistry,
                lastFullSync,
                cacheProperties,
            )
    }

    @Test
    fun `full-sync with one record and total size 1 shall trigger eviction and send sync-status`() {
        val correlationId = "test-corr-id"
        val timestamp = System.currentTimeMillis()
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "some-key",
                resourceName,
                timestamp,
                SyncType.FULL,
                correlationId,
                totalSize = 1,
            ),
        )

        verify(exactly = 1) { evictionService.evictExpired(resourceName, timestamp) }
        verify(exactly = 1) {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationId, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains("Completed", it.status)
                },
            )
        }
    }

    /**
     * Interval for full-syncs is set in days. Full-syncs for the same resource shall therefore never
     * happen concurrently. Concurrent full-syncs for the same resource likely either means two adapters
     * synchronizes the same resource or that there is an internal bug in an adapter. If this situation
     * occurs, the first full-sync is marked as failed and reported to the sync status service. The last
     * full-sync is continued.
     */
    @Test
    fun `overlapping full-syncs for same resource shall fail the existing full-sync`() {
        val correlationIdA = "corr-id-A"
        val correlationIdB = "corr-id-B"

        // Process first of two records of sync A => Start tracking of sync A
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                resourceName,
                1234,
                SyncType.FULL,
                correlationIdA,
                totalSize = 2,
            ),
        )
        verify { evictionService wasNot Called }
        clearAllMocks()

        // Process first of two records of sync B => Fail sync A and publish failure. Start tracking of sync B
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                resourceName,
                1234,
                SyncType.FULL,
                correlationIdB,
                totalSize = 2,
            ),
        )
        verify { evictionService wasNot Called }
        verify {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationIdA, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains("Concurrent full-sync of $resourceName resource", it.status)
                },
            )
        }
        clearAllMocks()

        // Process last of two records of sync B => Complete sync B and publish complete full-sync
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                resourceName,
                1235,
                SyncType.FULL,
                correlationIdB,
                totalSize = 2,
            ),
        )
        verify(exactly = 1) {
            evictionService.evictExpired(
                resourceName,
                1234,
            )
        } // Timestamp of earlies record for sync B
        verify(exactly = 1) {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationIdB, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains("Completed", it.status)
                },
            )
        }
        clearAllMocks()

        // Process last of two records of sync A => Do nothing. Will be removed by expiry
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                resourceName,
                1234,
                SyncType.FULL,
                correlationIdA,
                totalSize = 2,
            ),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
    }

    @Test
    fun `change of resource name for a ongoing sync shall fail that sync`() {
        val correlationId = "corr-id-A"

        // Process first of three records
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceName, 4, SyncType.FULL, correlationId, totalSize = 3),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
        clearAllMocks()

        // Process second of three records, but with another resource name => Fail sync and publish failure
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                "another-resource-name",
                5,
                SyncType.FULL,
                correlationId,
                totalSize = 3,
            ),
        )
        verify { evictionService wasNot Called }
        verify {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationId, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Resource name changed")
                },
            )
        }
        clearAllMocks()

        // Process last of three records => Sync is failed. Do nothing
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                "another-resource-name",
                6,
                SyncType.FULL,
                correlationId,
                totalSize = 3,
            ),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
    }

    @Test
    fun `full-syncs for different resource can be interleaved`() {
        val correlationIdA = "corr-id-A"
        val correlationIdB = "corr-id-B"
        val correlationIdC = "corr-id-C"

        val resourceNameA = "resource-name-A"
        val resourceNameB = "resource-name-B"
        val resourceNameC = "resource-name-C"

        // Process first of three records
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 1, SyncType.FULL, correlationIdA, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 1, SyncType.FULL, correlationIdB, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameC, 1, SyncType.FULL, correlationIdC, totalSize = 3),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
        clearAllMocks()

        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 2, SyncType.FULL, correlationIdA, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 2, SyncType.FULL, correlationIdB, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameC, 2, SyncType.FULL, correlationIdC, totalSize = 3),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
        clearAllMocks()

        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 3, SyncType.FULL, correlationIdA, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 3, SyncType.FULL, correlationIdB, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameC, 3, SyncType.FULL, correlationIdC, totalSize = 3),
        )

        verifySequence {
            evictionService.evictExpired(resourceNameA, 1)
            evictionService.evictExpired(resourceNameB, 1)
            evictionService.evictExpired(resourceNameC, 1)
        }
        verifySequence {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationIdA, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Completed")
                },
            )
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationIdB, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Completed")
                },
            )
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationIdC, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Completed")
                },
            )
        }
    }

    @Test
    fun `correlation id can be reused when not interleaved`() {
        val correlationId = "corr-id-A"

        val resourceNameA = "resource-name-A"
        val resourceNameB = "resource-name-B"

        // Process all three records of first full-sync
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 1, SyncType.FULL, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 2, SyncType.FULL, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 3, SyncType.FULL, correlationId, totalSize = 3),
        )
        verify {
            evictionService.evictExpired(resourceNameA, 1)
        }
        verify {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationId, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Completed")
                },
            )
        }
        clearAllMocks()

        // Process all three records of a delta-sync
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 4, SyncType.DELTA, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 5, SyncType.DELTA, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameB, 6, SyncType.DELTA, correlationId, totalSize = 3),
        )
        verify { evictionService wasNot Called }
        verify { syncStatusProducer wasNot Called }
        clearAllMocks()

        // Process all three records of a full-sync
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 7, SyncType.FULL, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 8, SyncType.FULL, correlationId, totalSize = 3),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceNameA, 9, SyncType.FULL, correlationId, totalSize = 3),
        )
        verify {
            evictionService.evictExpired(resourceNameA, 7)
        }
        verify {
            syncStatusProducer.publish(
                withArg {
                    assertEquals(correlationId, it.corrId)
                    assertEquals(SyncType.FULL, it.type)
                    assertContains(it.status, "Completed")
                },
            )
        }
    }

    @Test
    fun `completed full-sync registers timestamp in last-completed-full-sync cache`() {
        val correlationId = "last-sync-corr-id"
        val timestamp = 5_000L

        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "some-key",
                resourceName,
                timestamp,
                SyncType.FULL,
                correlationId,
                totalSize = 1,
            ),
        )

        verify(exactly = 1) { lastFullSync.registerTimestamp(resourceName, timestamp) }
    }

    @Test
    fun `completed delta-sync does not register timestamp`() {
        val correlationId = "delta-corr-id"

        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "some-key",
                resourceName,
                1234,
                SyncType.DELTA,
                correlationId,
                totalSize = 1,
            ),
        )

        verify { lastFullSync wasNot Called }
    }

    @Test
    fun `failed full-sync does not register timestamp`() {
        val correlationId = "failed-corr-id"

        syncTracker.processRecordMetadata(
            createEntityConsumerRecord("resource-key", resourceName, 1, SyncType.FULL, correlationId, totalSize = 2),
        )
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "resource-key",
                "another-resource-name",
                2,
                SyncType.FULL,
                correlationId,
                totalSize = 2,
            ),
        )

        verify { lastFullSync wasNot Called }
    }

    @Test
    fun `records develop sync metrics and new lock metric for completed full sync`() {
        val correlationId = "metrics-corr-id"
        syncTracker.processRecordMetadata(
            createEntityConsumerRecord(
                "some-key",
                resourceName,
                1234,
                SyncType.FULL,
                correlationId,
                totalSize = 1,
            ),
        )

        verifyTimer("sync.processRecordMetadata")
        verifyTimer("sync.state.load")
        verifyTimer("sync.state.transition")
        verifyTimer("sync.full.updateTracking")
        verifyTimer("sync.state.invalidate")
        verifyTimer("sync.full.evictExpired")
        verifyTimer("sync.full.removeTracking")
        verifyTimer("sync.status.publish.completed")
    }

    private fun createEntityConsumerRecord(
        resourceId: String,
        resourceName: String = this.resourceName,
        timestamp: Long = System.currentTimeMillis(),
        type: SyncType = SyncType.FULL,
        corrId: String = UUID.randomUUID().toString(),
        totalSize: Long = 10L,
    ): EntityConsumerRecord {
        val headers = RecordHeaders()
        val timestampBytes =
            ByteBuffer
                .allocate(Long.SIZE_BYTES)
                .putLong(timestamp)
                .array()
        headers.add(RecordHeader(LAST_MODIFIED, timestampBytes))
        headers.add(RecordHeader(SYNC_TYPE, byteArrayOf(type.ordinal.toByte())))
        headers.add(RecordHeader(SYNC_CORRELATION_ID, corrId.toByteArray()))
        headers.add(
            RecordHeader(
                SYNC_TOTAL_SIZE,
                ByteBuffer
                    .allocate(Long.SIZE_BYTES)
                    .putLong(totalSize)
                    .array(),
            ),
        )

        val resource = createResource(resourceId)

        return EntityConsumerRecord(
            resourceName = resourceName,
            resource = createResource(resourceId),
            record =
                ConsumerRecord<String, Any?>(
                    "test-topic",
                    0,
                    0,
                    timestamp,
                    TimestampType.CREATE_TIME,
                    NULL_SIZE,
                    NULL_SIZE,
                    resourceId,
                    resource,
                    headers,
                    Optional.empty(),
                ),
        )
    }

    private fun createResource(id: String) =
        ElevfravarResource().apply {
            systemId =
                Identifikator().apply {
                    identifikatorverdi = id
                }
        }

    private fun verifyTimer(operation: String) {
        val timers = meterRegistry.find("core.consumer.sync.processing").tag("operation", operation).timers()

        check(timers.isNotEmpty()) { "Expected timer for operation $operation" }
        assertEquals(1, timers.sumOf { it.count() })
    }
}
