package no.fintlabs.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.Called
import io.mockk.clearAllMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.config.MongoTestcontainerInitializer
import no.fintlabs.consumer.config.AutorelationConfig
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.OrgId
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertTrue

class CacheEvictionServiceTest {
    private lateinit var cacheService: CacheService
    private lateinit var autoRelationService: AutoRelationService
    private lateinit var consumerConfiguration: ConsumerConfiguration
    private lateinit var cacheEvictionService: CacheEvictionService

    private val resourceKey = "utdanning_vurdering_elevfravar"

    @BeforeEach
    fun setUp() {
        val factory =
            SimpleMongoClientDatabaseFactory(
                MongoTestcontainerInitializer.MONGO.getReplicaSetUrl("fintcache-eviction-${UUID.randomUUID()}"),
            )
        val mongoTemplate = MongoTemplate(factory)
        cacheService = CacheService(mongoTemplate, CacheDocumentCodec(objectMapper))
        autoRelationService = mockk(relaxed = true)
        consumerConfiguration =
            mockk {
                every { orgId } returns OrgId.from("org-123")
                every { autorelation } returns AutorelationConfig(enabled = true)
            }
        cacheEvictionService =
            CacheEvictionService(
                cacheService = cacheService,
                autoRelationService = autoRelationService,
                consumerConfiguration = consumerConfiguration,
            )
    }

    @AfterEach
    fun tearDown() {
        clearAllMocks()
    }

    @Test
    fun `eviction on empty cache does not call autoRelationService`() {
        cacheEvictionService.evictExpired("utdanning_vurdering_unknown", Long.MAX_VALUE)

        verify { autoRelationService wasNot Called }
    }

    @Test
    fun `applies removal for every evicted object when autorelation enabled`() {
        val key1 = "k1"
        val key2 = "k2"

        val cache = cacheService.getCache(resourceKey)
        val resource1 = ElevfravarResource()
        val resource2 = ElevfravarResource()
        cache.put(key1, resource1, 1)
        cache.put(key2, resource2, 2)
        cacheEvictionService.evictExpired(resourceKey, Long.MAX_VALUE)

        verify(exactly = 1) {
            autoRelationService.applyRemoval(resourceKey, key1, match { it.javaClass == resource1.javaClass })
        }
        verify(exactly = 1) {
            autoRelationService.applyRemoval(resourceKey, key2, match { it.javaClass == resource2.javaClass })
        }
    }

    @Test
    fun `skips removal for evicted objects when autorelation disabled`() {
        every { consumerConfiguration.autorelation } returns AutorelationConfig(enabled = false)

        val cache = cacheService.getCache(resourceKey)
        cache.put("k1", ElevfravarResource(), 1)
        cache.put("k2", ElevfravarResource(), 2)
        cacheEvictionService.evictExpired(resourceKey, Long.MAX_VALUE)

        verify(exactly = 0) { autoRelationService.applyRemoval(any(), any(), any()) }
    }

    @Test
    fun `concurrent eviction trigger for same resource is queued and reruns with latest start timestamp`() {
        val firstStartTimestamp = 10L
        val secondStartTimestamp = 20L
        val mockedCacheService = mockk<CacheService>()
        val cache = mockk<FintCache>(relaxed = true)
        every { mockedCacheService.getCache(resourceKey) } returns cache

        val service =
            CacheEvictionService(
                cacheService = mockedCacheService,
                autoRelationService = autoRelationService,
                consumerConfiguration = consumerConfiguration,
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

        val first = CompletableFuture.runAsync { service.evictExpired(resourceKey, firstStartTimestamp) }
        assertTrue(firstRunStarted.await(2, TimeUnit.SECONDS), "First eviction run did not start in time")

        val second = CompletableFuture.runAsync { service.evictExpired(resourceKey, secondStartTimestamp) }

        allowFirstRunToFinish.countDown()

        first.get(2, TimeUnit.SECONDS)
        second.get(2, TimeUnit.SECONDS)

        verify(exactly = 1) { cache.evictExpired(firstStartTimestamp) }
        verify(exactly = 1) { cache.evictExpired(secondStartTimestamp) }
    }

    companion object {
        private val objectMapper: ObjectMapper = jacksonObjectMapper()
    }
}
