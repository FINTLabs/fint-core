package no.fintlabs.cache

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.RelationEventService
import no.fintlabs.consumer.config.AutorelationConfig
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.OrgId
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

class CacheEvictionServiceTest {
    private lateinit var cacheService: CacheService
    private lateinit var relationEventService: RelationEventService
    private lateinit var consumerConfiguration: ConsumerConfiguration
    private lateinit var cacheEvictionService: CacheEvictionService

    @BeforeEach
    fun setUp() {
        cacheService = CacheService()
        relationEventService = mockk(relaxed = true)
        consumerConfiguration =
            mockk {
                every { orgId } returns OrgId.from("org-123")
                every { autorelation } returns AutorelationConfig(enabled = true)
            }
        cacheEvictionService =
            CacheEvictionService(
                cacheService = cacheService,
                relationEventService = relationEventService,
                consumerConfiguration = consumerConfiguration,
                meterRegistry = SimpleMeterRegistry(),
            )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `eviction on empty cache or with unknown resource name does not call relationEventService`() {
        val resourceName = "unknown-resource"
        cacheEvictionService.evictExpired(resourceName, Long.MAX_VALUE)

        verify { relationEventService wasNot Called }
    }

    @Test
    fun `calls removeRelations for every evicted object when autorelation enabled`() {
        val resourceName = "elevfravar"
        val key1 = "k1"
        val key2 = "k2"

        val cache = cacheService.getCache(resourceName)
        val resource1 = ElevfravarResource()
        val resource2 = ElevfravarResource()
        cache.put(key1, resource1, 1)
        cache.put(key2, resource2, 2)
        cacheEvictionService.evictExpired(resourceName, Long.MAX_VALUE)

        verify(exactly = 1) {
            relationEventService.removeRelations(resourceName, key1, resource1)
            relationEventService.removeRelations(resourceName, key2, resource2)
        }
    }

    @Test
    fun `skips removeRelations for evicted objects when autorelation disabled`() {
        every { consumerConfiguration.autorelation } returns AutorelationConfig(enabled = false)
        val resourceName = "elevfravar"

        val cache = cacheService.getCache(resourceName)
        cache.put("k1", ElevfravarResource(), 1)
        cache.put("k2", ElevfravarResource(), 2)
        cacheEvictionService.evictExpired(resourceName, Long.MAX_VALUE)

        verify(exactly = 0) { relationEventService.removeRelations(any(), any(), any()) }
    }

    @Test
    fun `concurrent eviction trigger for same resource is queued and reruns with latest start timestamp`() {
        val resourceName = "elevfravar"
        val firstStartTimestamp = 10L
        val secondStartTimestamp = 20L
        val mockedCacheService = mockk<CacheService>()
        val cache = mockk<FintCache<FintResource>>(relaxed = true)
        every { mockedCacheService.getCache(resourceName) } returns cache

        val service =
            CacheEvictionService(
                cacheService = mockedCacheService,
                relationEventService = relationEventService,
                consumerConfiguration = consumerConfiguration,
                meterRegistry = SimpleMeterRegistry(),
            )

        val firstRunStarted = CountDownLatch(1)
        val allowFirstRunToFinish = CountDownLatch(1)
        val evictionRuns = AtomicInteger(0)

        every { cache.evictExpired(any()) } answers {
            if (evictionRuns.incrementAndGet() == 1) {
                firstRunStarted.countDown()
                allowFirstRunToFinish.await(2, TimeUnit.SECONDS)
            }
            emptySet()
        }

        val first = CompletableFuture.runAsync { service.evictExpired(resourceName, firstStartTimestamp) }
        assertTrue(firstRunStarted.await(2, TimeUnit.SECONDS), "First eviction run did not start in time")

        val second = CompletableFuture.runAsync { service.evictExpired(resourceName, secondStartTimestamp) }

        allowFirstRunToFinish.countDown()

        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)

        verify(exactly = 1) { cache.evictExpired(firstStartTimestamp) }
        verify(exactly = 1) { cache.evictExpired(secondStartTimestamp) }
    }
}
