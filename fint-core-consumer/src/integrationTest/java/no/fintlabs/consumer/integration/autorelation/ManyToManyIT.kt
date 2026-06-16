package no.fintlabs.consumer.integration.autorelation

import no.fintlabs.Application
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheService
import no.fintlabs.utils.EntityProducer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.KontaktlarergruppeResource
import no.novari.fint.model.resource.utdanning.elev.UndervisningsforholdResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.time.Clock
import java.time.Duration
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * ManyToMany autorelation (source side only): the trigger resource holds MANY links to targets,
 * and each target accumulates MANY back-links.
 *
 * Only the source side drives updates — the inverse side (e.g. Undervisningsforhold) must
 * NOT trigger back-updates on Kontaktlarergruppe.
 *
 * Scenario: Kontaktlarergruppe (MANY undervisningsforhold) → Undervisningsforhold (MANY kontaktlarergruppe back-links)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1, topics = ["foo-org.fint-core.entity.utdanning-elev-relation-update"])
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.consumer.autorelation.enabled=true",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class ManyToManyIT {
    @Autowired
    lateinit var entityProducer: EntityProducer

    @Autowired
    lateinit var cacheService: CacheService

    private lateinit var kafkaTemplate: KafkaTemplate<String, String>
    private lateinit var entityTopic: String

    private val clock: Clock = Clock.systemUTC()

    private val gruppeId = "gruppe-1"
    private val undervisningId1 = "und-1"
    private val undervisningId2 = "und-2"
    private val undervisningId3 = "und-3"

    private val expectedBackLinkHref =
        "https://test.felleskomponent.no/utdanning/elev/kontaktlarergruppe/systemId/$gruppeId"

    @AfterEach
    fun tearDown() {
        cacheService.getCache("undervisningsforhold").evictExpired(Long.MAX_VALUE)
        cacheService.getCache("kontaktlarergruppe").evictExpired(Long.MAX_VALUE)
    }

    @Test
    fun `should add kontaktlarergruppe back-link to all linked Undervisningsforhold`() {
        populateCacheWithUndervisningsforhold()

        sendEntityRecord(
            createKontaktlarergruppe(gruppeId).apply {
                addUndervisningsforhold(Link.with("systemid/$undervisningId1"))
                addUndervisningsforhold(Link.with("systemid/$undervisningId2"))
                addUndervisningsforhold(Link.with("systemid/$undervisningId3"))
            },
            "kontaktlarergruppe",
        )

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val resource = cacheService.getCache("kontaktlarergruppe").get(gruppeId)
            assertNotNull(resource)
        }

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertBackLinkExistsOnUndervisningsforhold(undervisningId1)
            assertBackLinkExistsOnUndervisningsforhold(undervisningId2)
            assertBackLinkExistsOnUndervisningsforhold(undervisningId3)
        }
    }

    @Test
    fun `should remove kontaktlarergruppe back-link when link is dropped from Kontaktlarergruppe`() {
        populateCacheWithUndervisningsforhold()

        sendEntityRecord(
            createKontaktlarergruppe(gruppeId).apply {
                addUndervisningsforhold(Link.with("systemid/$undervisningId1"))
                addUndervisningsforhold(Link.with("systemid/$undervisningId2"))
                addUndervisningsforhold(Link.with("systemid/$undervisningId3"))
            },
            "kontaktlarergruppe",
        )

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertBackLinkExistsOnUndervisningsforhold(undervisningId1)
        }

        // Re-publish without undervisningId2 — its back-link should be pruned
        sendEntityRecord(
            createKontaktlarergruppe(gruppeId).apply {
                addUndervisningsforhold(Link.with("systemid/$undervisningId1"))
                addUndervisningsforhold(Link.with("systemid/$undervisningId3"))
            },
            "kontaktlarergruppe",
        )

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertBackLinkExistsOnUndervisningsforhold(undervisningId1)
            assertBackLinkExistsOnUndervisningsforhold(undervisningId3)

            val dropped = cacheService.getCache("undervisningsforhold").get(undervisningId2)
            assertBackLinkAbsentOnResource(dropped, "kontaktlarergruppe", expectedBackLinkHref)
        }
    }

    @Test
    fun `should NOT update Kontaktlarergruppe when Undervisningsforhold arrives (inverse side doesnt update)`() {
        sendEntityRecord(createKontaktlarergruppe(gruppeId), "kontaktlarergruppe")

        sendEntityRecord(
            createUndervisningsforhold(undervisningId1).apply {
                addLink("kontaktlarergruppe", Link.with("systemid/$gruppeId"))
            },
            "undervisningsforhold",
        )

        // Wait until both messages are confirmed processed — then any side-effect would have occurred
        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertNotNull(cacheService.getCache("kontaktlarergruppe").get(gruppeId))
            assertNotNull(cacheService.getCache("undervisningsforhold").get(undervisningId1))
        }

        val cachedGruppe = cacheService.getCache("kontaktlarergruppe").get(gruppeId)!!
        val links = cachedGruppe.links["undervisningsforhold"]
        assertTrue(
            links.isNullOrEmpty(),
            "Kontaktlarergruppe must not be updated by Undervisningsforhold (non-source side)",
        )
    }

    @Test
    fun `should preserve kontaktlarergruppe back-link on Undervisningsforhold after it is re-published`() {
        populateCacheWithUndervisningsforhold()

        sendEntityRecord(
            createKontaktlarergruppe(gruppeId).apply {
                addUndervisningsforhold(Link.with("systemid/$undervisningId1"))
            },
            "kontaktlarergruppe",
        )

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            assertBackLinkExistsOnUndervisningsforhold(undervisningId1)
        }

        // Adapter re-publishes Undervisningsforhold without any back-links
        sendEntityRecord(createUndervisningsforhold(undervisningId1), "undervisningsforhold")

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val cached = cacheService.getCache("undervisningsforhold").get(undervisningId1)
            assertNotNull(cached)
            assertBackLinkExistsOnResource(cached, "kontaktlarergruppe", expectedBackLinkHref)
        }
    }

    private fun populateCacheWithUndervisningsforhold() {
        val corrId = UUID.randomUUID().toString()
        sendEntityRecord(createUndervisningsforhold(undervisningId1), "undervisningsforhold", corrId, 3)
        sendEntityRecord(createUndervisningsforhold(undervisningId2), "undervisningsforhold", corrId, 3)
        sendEntityRecord(createUndervisningsforhold(undervisningId3), "undervisningsforhold", corrId, 3)

        await.atMost(Duration.ofSeconds(15)).untilAsserted {
            val cache = cacheService.getCache("undervisningsforhold")
            assertNotNull(cache.get(undervisningId1))
            assertNotNull(cache.get(undervisningId2))
            assertNotNull(cache.get(undervisningId3))
        }
    }

    private fun sendEntityRecord(
        resource: FintResource,
        resourceName: String,
        corrId: String = UUID.randomUUID().toString(),
        totalSize: Long = 1,
        timestamp: Long = clock.millis(),
    ) {
        val key =
            requireNotNull(resource.identifikators["systemId"]?.identifikatorverdi) {
                "Missing systemId on $resourceName"
            }
        entityProducer
            .publish(
                resourceName,
                resource,
                key,
                SyncType.FULL,
                corrId,
                totalSize,
                timestamp,
            ).get()
    }

    private fun assertBackLinkExistsOnUndervisningsforhold(undervisningId: String) {
        val resource = cacheService.getCache("undervisningsforhold").get(undervisningId)
        assertBackLinkExistsOnResource(resource, "kontaktlarergruppe", expectedBackLinkHref)
    }

    private fun assertBackLinkExistsOnResource(
        resource: FintResource?,
        relationName: String,
        expectedHref: String,
    ) {
        assertNotNull(resource, "Resource should be present in cache")
        val links = resource.links[relationName]
        assertNotNull(links, "Relation '$relationName' should exist")
        assertTrue(
            links.any { it.href.equals(expectedHref, ignoreCase = true) },
            "Expected '$expectedHref' in '$relationName'. Found: ${links.map { it.href }}",
        )
    }

    private fun assertBackLinkAbsentOnResource(
        resource: FintResource?,
        relationName: String,
        unexpectedHref: String,
    ) {
        assertNotNull(resource, "Resource should be present in cache")
        val links = resource.links[relationName]
        if (links.isNullOrEmpty()) return
        assertFalse(
            links.any { it.href.equals(unexpectedHref, ignoreCase = true) },
            "'$unexpectedHref' should NOT be present in '$relationName'",
        )
    }

    private fun createKontaktlarergruppe(id: String) =
        KontaktlarergruppeResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
            addSkole(Link.with("systemid/dummy-skole"))
        }

    private fun createUndervisningsforhold(id: String) =
        UndervisningsforholdResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }
}
