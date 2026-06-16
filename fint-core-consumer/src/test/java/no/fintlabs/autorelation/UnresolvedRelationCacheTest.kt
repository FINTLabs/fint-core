package no.fintlabs.autorelation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.autorelation.buffer.UnresolvedRelationCache
import no.fintlabs.consumer.config.AutorelationConfig
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.fint.model.resource.Link
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration

class UnresolvedRelationCacheTest {
    private lateinit var service: UnresolvedRelationCache
    private lateinit var metricService: MetricService

    private val resource = "person"
    private val resourceId = "123"
    private val relation = "manager"

    private fun cacheWithTtl(
        ttl: Duration,
        metrics: MetricService = mockk(relaxed = true),
    ): UnresolvedRelationCache {
        val config = mockk<ConsumerConfiguration>()
        every { config.autorelation } returns AutorelationConfig(buffer = AutorelationConfig.BufferConfig(ttl = ttl))
        return UnresolvedRelationCache(config, metrics)
    }

    @BeforeEach
    fun setup() {
        metricService = mockk(relaxed = true)
        service = cacheWithTtl(Duration.ofDays(7), metricService)
    }

    @Test
    fun `registerRelation stores link and takeRelations retrieves it`() {
        val link = Link.with("http://test-link")

        service.registerRelation(resource, resourceId, relation, link, System.currentTimeMillis())

        val result = service.takeRelations(resource, resourceId, relation)
        assertEquals(listOf(link), result)
    }

    @Test
    fun `takeRelations returns empty list when no links exist`() {
        val result = service.takeRelations(resource, resourceId, relation)
        assertEquals(emptyList<Link>(), result)
    }

    @Test
    fun `takeRelations clears the entry after retrieval`() {
        val link = Link.with("http://test-link")
        service.registerRelation(resource, resourceId, relation, link, System.currentTimeMillis())

        service.takeRelations(resource, resourceId, relation)
        val secondCall = service.takeRelations(resource, resourceId, relation)

        assertEquals(emptyList<Link>(), secondCall)
    }

    @Test
    fun `registerRelation appends when called multiple times`() {
        val l1 = Link.with("http://link-1")
        val l2 = Link.with("http://link-2")
        val now = System.currentTimeMillis()

        service.registerRelation(resource, resourceId, relation, l1, now)
        service.registerRelation(resource, resourceId, relation, l2, now)

        val result = service.takeRelations(resource, resourceId, relation)
        assertEquals(listOf(l1, l2), result)
    }

    @Test
    fun `removeRelation removes specific link`() {
        val l1 = Link.with("http://link-1")
        val l2 = Link.with("http://link-2")
        val now = System.currentTimeMillis()

        service.registerRelation(resource, resourceId, relation, l1, now)
        service.registerRelation(resource, resourceId, relation, l2, now)

        service.removeRelation(resource, resourceId, relation, l1)

        val result = service.takeRelations(resource, resourceId, relation)
        assertEquals(listOf(l2), result)
    }

    @Test
    fun `removeRelation cleans up entry when last link is removed`() {
        val link = Link.with("http://link-1")
        service.registerRelation(resource, resourceId, relation, link, System.currentTimeMillis())

        service.removeRelation(resource, resourceId, relation, link)

        val result = service.takeRelations(resource, resourceId, relation)
        assertEquals(emptyList<Link>(), result)
    }

    @Nested
    inner class DynamicRetentionScenarios {
        @Test
        fun `entry with expired timestamp is evicted`() {
            val eightDaysAgo = System.currentTimeMillis() - Duration.ofDays(8).toMillis()
            val link = Link.with("http://expired-link")

            service.registerRelation(resource, resourceId, relation, link, eightDaysAgo)
            service.cleanUp()

            assertEquals(emptyList<Link>(), service.takeRelations(resource, resourceId, relation))
        }

        @Test
        fun `entry with recent timestamp is retained`() {
            val oneDayAgo = System.currentTimeMillis() - Duration.ofDays(1).toMillis()
            val link = Link.with("http://fresh-link")

            service.registerRelation(resource, resourceId, relation, link, oneDayAgo)
            service.cleanUp()

            assertEquals(listOf(link), service.takeRelations(resource, resourceId, relation))
        }

        @Test
        fun `entry exactly at TTL boundary is evicted`() {
            val exactlySevenDaysAgo = System.currentTimeMillis() - Duration.ofDays(7).toMillis()
            val link = Link.with("http://boundary-link")

            service.registerRelation(resource, resourceId, relation, link, exactlySevenDaysAgo)
            service.cleanUp()

            assertEquals(emptyList<Link>(), service.takeRelations(resource, resourceId, relation))
        }

        @Test
        fun `expired entry triggers incrementBufferExpired tagged with link count`() {
            val eightDaysAgo = System.currentTimeMillis() - Duration.ofDays(8).toMillis()
            val link = Link.with("http://expired-link")

            service.registerRelation(resource, resourceId, relation, link, eightDaysAgo)
            service.cleanUp()

            verify(exactly = 1) { metricService.incrementBufferExpired(resource, relation, 1) }
        }
    }

    @Nested
    inner class ConfigurableTtl {
        @Test
        fun `custom short TTL evicts entries older than that duration`() {
            val cache = cacheWithTtl(Duration.ofMinutes(5))
            val link = Link.with("http://expired-link")
            val tenMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(10).toMillis()

            cache.registerRelation(resource, resourceId, relation, link, tenMinutesAgo)
            cache.cleanUp()

            assertEquals(emptyList<Link>(), cache.takeRelations(resource, resourceId, relation))
        }

        @Test
        fun `custom short TTL retains entries younger than that duration`() {
            val cache = cacheWithTtl(Duration.ofMinutes(10))
            val link = Link.with("http://fresh-link")
            val fiveMinutesAgo = System.currentTimeMillis() - Duration.ofMinutes(5).toMillis()

            cache.registerRelation(resource, resourceId, relation, link, fiveMinutesAgo)
            cache.cleanUp()

            assertEquals(listOf(link), cache.takeRelations(resource, resourceId, relation))
        }

        @Test
        fun `default TTL is 30 days`() {
            assertEquals(Duration.ofDays(30), AutorelationConfig.BufferConfig().ttl)
        }
    }
}
