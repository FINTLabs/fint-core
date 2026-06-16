package no.fintlabs.consumer.integration

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.Application
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheService
import no.fintlabs.utils.EntityProducer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.timeplan.FagResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=legacy-resource-topic-it",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://foo.org",
        "fint.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=timeplan",
        "fint.consumer.kafka.consume-legacy-resource-topics=true",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class LegacyResourceTopicIT {
    @LocalServerPort
    private var port: Int = 0

    private val client by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port/utdanning/timeplan")
            .responseTimeout(Duration.ofSeconds(10))
            .build()
    }

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var cacheService: CacheService

    @Autowired
    lateinit var entityProducer: EntityProducer

    private val clock: Clock = Clock.systemUTC()

    @AfterEach
    fun tearDown() {
        cacheService.getCache("fag").evictExpired(Long.MAX_VALUE)
    }

    @Test
    fun `resource published to legacy topic with resource-name header is cached`() {
        entityProducer
            .publishToLegacyResourceTopic(
                resourceName = "fag",
                resource = createFag("1", "Fag 1"),
                resourceId = "1",
                syncType = SyncType.FULL,
                syncCorrId = UUID.randomUUID().toString(),
                syncTotalSize = 1,
                timestamp = clock.millis(),
                includeResourceNameHeader = true,
            ).get(10, TimeUnit.SECONDS)

        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            val resources = fetchAllFagResources()
            assertEquals(1, resources.size)
            assertEquals("Fag 1", resources.first().navn)
        }
    }

    @Test
    fun `resource published to legacy topic without resource-name header falls back to topic name and is cached`() {
        entityProducer
            .publishToLegacyResourceTopic(
                resourceName = "fag",
                resource = createFag("2", "Fag 2"),
                resourceId = "2",
                syncType = SyncType.FULL,
                syncCorrId = UUID.randomUUID().toString(),
                syncTotalSize = 1,
                timestamp = clock.millis(),
                includeResourceNameHeader = false,
            ).get(10, TimeUnit.SECONDS)

        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            val resources = fetchAllFagResources()
            assertEquals(1, resources.size)
            assertEquals("Fag 2", resources.first().navn)
        }
    }

    private fun createFag(
        id: String,
        navn: String,
    ): FagResource {
        val fag = FagResource()
        fag.systemId = Identifikator().apply { identifikatorverdi = "systemid-fag-$id" }
        fag.navn = navn
        fag.links["self"] = listOf(Link("https://foo.org/utdanning/timeplan/fag/systemid/$id"))
        return fag
    }

    private fun fetchAllFagResources(): List<FagResource> {
        val page =
            client
                .get()
                .uri("/fag")
                .exchange()
                .expectStatus()
                .isOk
                .expectBody(FintResourcesPage::class.java)
                .returnResult()
                .responseBody ?: return emptyList()
        return page.getResources(objectMapper, FagResource::class.java)
    }
}
