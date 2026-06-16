package no.fintlabs.consumer.kafka.entity

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.autorelation.MetricService
import no.fintlabs.autorelation.RelationEventService
import no.fintlabs.cache.CacheService
import no.fintlabs.cache.FintCache
import no.fintlabs.consumer.config.AutorelationConfig
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.kafka.KafkaConstants
import no.fintlabs.consumer.kafka.sync.SyncTrackerService
import no.fintlabs.consumer.links.LinkService
import no.fintlabs.consumer.resource.ResourceLockService
import no.novari.fint.model.resource.FintResource
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.internals.RecordHeaders
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer

class EntityProcessingServiceTest {
    private val linkService = mockk<LinkService>(relaxed = true)
    private val cacheService = mockk<CacheService>()
    private val autoRelationService = mockk<AutoRelationService>(relaxed = true)
    private val relationEventService = mockk<RelationEventService>(relaxed = true)
    private val consumerConfiguration = mockk<ConsumerConfiguration>()
    private val syncTrackerService = mockk<SyncTrackerService>(relaxed = true)
    private val cache = mockk<FintCache<FintResource>>(relaxed = true)
    private val meterRegistry = SimpleMeterRegistry()
    private val metricService = mockk<MetricService>(relaxed = true)
    private var resourceLockService: ResourceLockService =
        mockk {
            every { withLock(any(), any(), any()) } answers {
                val block = thirdArg<() -> Unit>()
                block()
            }
        }

    private lateinit var service: EntityProcessingService

    @BeforeEach
    fun setup() {
        service =
            EntityProcessingService(
                linkService,
                cacheService,
                autoRelationService,
                relationEventService,
                consumerConfiguration,
                syncTrackerService,
                meterRegistry,
                resourceLockService,
                metricService,
            )
        every { cacheService.getCache(any()) } returns cache
        every { consumerConfiguration.orgId } returns OrgId.from("org-123")
        every { consumerConfiguration.autorelation } returns AutorelationConfig(enabled = false)
    }

    @Test
    fun `null resource triggers delete path`() {
        val record = recordWith(resource = null, syncType = null)
        every { cache.get(any()) } returns null

        service.processEntityConsumerRecord(record)

        verify { cache.remove(any(), any()) }
        verify(exactly = 0) { cache.put(any(), any(), any()) }
    }

    @Test
    fun `non-null resource triggers add to cache`() {
        val resource = mockk<FintResource>()
        val record = recordWith(resource = resource, syncType = null)

        service.processEntityConsumerRecord(record)

        verify { cache.put(any(), resource, any()) }
        verify(exactly = 0) { cache.remove(any(), any()) }
    }

    @Test
    fun `delete removes relations when cache entry exists and autorelation enabled`() {
        every { consumerConfiguration.autorelation } returns AutorelationConfig(enabled = true)
        val existing = mockk<FintResource>()
        val record = recordWith(resource = null, syncType = null)
        every { cache.get(record.key) } returns existing

        service.processEntityConsumerRecord(record)

        verify(exactly = 1) { relationEventService.removeRelations(record.resourceName, record.key, existing) }
    }

    @Test
    fun `delete skips removeRelations when autorelation disabled`() {
        val existing = mockk<FintResource>()
        val record = recordWith(resource = null, syncType = null)
        every { cache.get(record.key) } returns existing

        service.processEntityConsumerRecord(record)

        verify(exactly = 0) { relationEventService.removeRelations(any(), any(), any()) }
    }

    @Test
    fun `delete skips removeRelations when cache entry is absent`() {
        val record = recordWith(resource = null, syncType = null)
        every { cache.get(any()) } returns null

        service.processEntityConsumerRecord(record)

        verify(exactly = 0) { relationEventService.removeRelations(any(), any(), any()) }
    }

    @Test
    fun `non-null type triggers syncTrackerService`() {
        val record = recordWith(resource = mockk(), syncType = 0)

        service.processEntityConsumerRecord(record)

        verify { syncTrackerService.processRecordMetadata(record) }
    }

    @Test
    fun `null type skips syncTrackerService`() {
        val record = recordWith(resource = mockk(), syncType = null)

        service.processEntityConsumerRecord(record)

        verify(exactly = 0) { syncTrackerService.processRecordMetadata(any()) }
    }

    @Test
    fun `autorelation enabled calls mapLinks and reconcileLinks`() {
        every { consumerConfiguration.autorelation } returns AutorelationConfig(enabled = true)
        val resource = mockk<FintResource>()
        val record = recordWith(resource = resource, syncType = null)

        service.processEntityConsumerRecord(record)

        verify(exactly = 1) { linkService.mapLinks(record.resourceName, record.resource) }
        verify(exactly = 1) { autoRelationService.reconcileLinks(record.resourceName, record.key, resource) }
    }

    @Test
    fun `autorelation disabled calls mapLinks and skips reconcileLinks`() {
        val resource = mockk<FintResource>()
        val record = recordWith(resource = resource, syncType = null)

        service.processEntityConsumerRecord(record)

        verify { linkService.mapLinks(record.resourceName, resource) }
        verify(exactly = 0) { autoRelationService.reconcileLinks(any(), any(), any()) }
    }

    @Test
    fun `records develop metrics and new lock metric for add path`() {
        val resource = mockk<FintResource>()
        val record = recordWith(resource = resource, syncType = 0)

        service.processEntityConsumerRecord(record)

        verifyTimer("record.process.total")
        verifyTimer("record.addPath")
        verifyTimer("cache.getCache")
        verifyTimer("links.map")
        verifyTimer("cache.put")
        verifyTimer("sync.processRecordMetadata")
    }

    private fun recordWith(
        resource: FintResource?,
        syncType: Int?,
    ): EntityConsumerRecord = EntityConsumerRecord("test-resource", resource, mockConsumerRecord(syncType))

    private fun mockConsumerRecord(syncType: Int?) =
        mockk<ConsumerRecord<String, Any?>> {
            every { key() } returns "test-key"
            every { headers() } returns
                RecordHeaders().apply {
                    add(KafkaConstants.LAST_MODIFIED, ByteBuffer.allocate(8).putLong(1000L).array())
                    if (syncType != null) {
                        add(KafkaConstants.SYNC_TYPE, byteArrayOf(syncType.toByte()))
                    }
                }
        }

    private fun verifyTimer(operation: String) {
        val timers = meterRegistry.find("core.consumer.processing").tag("operation", operation).timers()

        check(timers.isNotEmpty()) { "Expected timer for operation $operation" }
        kotlin.test.assertEquals(1, timers.sumOf { it.count() })
    }
}
