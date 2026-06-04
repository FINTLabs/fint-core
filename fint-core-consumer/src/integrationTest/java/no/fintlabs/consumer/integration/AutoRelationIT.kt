package no.fintlabs.consumer.integration

import no.fintlabs.Application
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheService
import no.fintlabs.utils.EntityProducer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.vurdering.ElevfravarResource
import no.novari.fint.model.resource.utdanning.vurdering.FravarsregistreringResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(
    partitions = 1,
    topics = [
        "foo-org.fint-core.entity.utdanning-vurdering",
    ],
)
@ActiveProfiles("utdanning-vurdering")
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=autorelation-service-it",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.autorelation.enabled=true",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class AutoRelationIT {
    @Autowired
    lateinit var entityProducer: EntityProducer

    @Autowired
    lateinit var cacheService: CacheService

    private val targetResource = "elevfravar"
    private val targetKey = "utdanning_vurdering_elevfravar"
    private val sourceResource = "fravarsregistrering"
    private val sourceKey = "utdanning_vurdering_fravarsregistrering"
    private val forwardRelation = "elevfravar"
    private val backRelation = "fravarsregistrering"

    @AfterEach
    fun tearDown() {
        cacheService.getCache(targetKey).evictExpired(Long.MAX_VALUE)
        cacheService.getCache(sourceKey).evictExpired(Long.MAX_VALUE)
    }

    @Test
    fun `back-link is applied to a cached target when the source arrives`() {
        val targetId = UUID.randomUUID().toString()
        val sourceId = UUID.randomUUID().toString()

        sendEntity(targetResource, elevfravar(targetId), targetId)
        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertNotNull(cacheService.getCache(targetKey).get(targetId))
        }

        sendEntity(sourceResource, fravarsregistrering(sourceId, targetId), sourceId)

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val links =
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation)
            assertNotNull(links)
            assertEquals(1, links.size)
            assertLinkWithSuffixExists(links, "systemid/$sourceId")
        }
    }

    @Test
    fun `back-link materialises when the target arrives after the source`() {
        val targetId = UUID.randomUUID().toString()
        val sourceId = UUID.randomUUID().toString()

        sendEntity(sourceResource, fravarsregistrering(sourceId, targetId), sourceId)
        sendEntity(targetResource, elevfravar(targetId), targetId)

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val links =
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation)
            assertNotNull(links)
            assertLinkWithSuffixExists(links, "systemid/$sourceId")
        }
    }

    @Test
    fun `back-link is preserved when the target is re-cached without it`() {
        val targetId = UUID.randomUUID().toString()
        val sourceId = UUID.randomUUID().toString()

        sendEntity(targetResource, elevfravar(targetId), targetId)
        sendEntity(sourceResource, fravarsregistrering(sourceId, targetId), sourceId)
        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertNotNull(
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation),
            )
        }

        sendEntity(targetResource, elevfravar(targetId), targetId)

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val links =
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation)
            assertNotNull(links)
            assertLinkWithSuffixExists(links, "systemid/$sourceId")
        }
    }

    @Test
    fun `back-link is removed when the source no longer references the target`() {
        val targetId = UUID.randomUUID().toString()
        val sourceId = UUID.randomUUID().toString()

        sendEntity(targetResource, elevfravar(targetId), targetId)
        sendEntity(sourceResource, fravarsregistrering(sourceId, targetId), sourceId)
        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertNotNull(
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation),
            )
        }

        sendEntity(sourceResource, fravarsregistrering(sourceId, null), sourceId)

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val links =
                cacheService
                    .getCache(targetKey)
                    .get(targetId)
                    ?.links
                    ?.get(backRelation)
            assertTrue(links.isNullOrEmpty(), "Back-link should be removed once the source drops the reference")
        }
    }

    private fun elevfravar(id: String): ElevfravarResource =
        ElevfravarResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }

    private fun fravarsregistrering(
        id: String,
        targetId: String?,
    ): FravarsregistreringResource =
        FravarsregistreringResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
            if (targetId != null) addLink(forwardRelation, Link.with("systemid/$targetId"))
        }

    private fun sendEntity(
        resourceName: String,
        resource: FintResource,
        resourceId: String,
    ) {
        entityProducer
            .publish(resourceName, resource, resourceId, SyncType.DELTA, UUID.randomUUID().toString(), 1)
            .get(10, TimeUnit.SECONDS)
    }

    private fun assertLinkWithSuffixExists(
        links: List<Link>,
        suffix: String,
    ) {
        val match = links.any { it.href?.endsWith(suffix, ignoreCase = true) == true }
        assertTrue(match, "Expected link ending with '$suffix' was not found. Found: ${links.map { it.href }}")
    }
}
