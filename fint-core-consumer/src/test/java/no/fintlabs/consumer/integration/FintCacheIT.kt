package no.fintlabs.consumer.integration

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.Application
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.cache.CacheService
import no.fintlabs.utils.EntityProducer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.timeplan.FagResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertNotNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.client.getForEntity
import org.springframework.http.HttpStatus
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.web.client.ResponseErrorHandler
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=entity-cache-it",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://foo.org",
        "fint.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=timeplan",
        "fint.security.enabled=false",
    ],
)
@DirtiesContext
class FintCacheIT {
    @Value("\${fint.org-id}")
    private lateinit var fintOrg: String

    @Value("\${fint.consumer.domain}")
    private lateinit var fintDomain: String

    @Value("\${fint.consumer.package}")
    private lateinit var fintPackage: String

    @Autowired
    lateinit var rest: TestRestTemplate

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @Autowired
    lateinit var cacheService: CacheService

    @Autowired
    lateinit var entityProducer: EntityProducer

    private val clock: Clock = Clock.systemUTC()

    @BeforeEach
    fun setUp() {
        rest.restTemplate.errorHandler = ResponseErrorHandler { false }
    }

    @AfterEach
    fun tearDown() {
        cacheService.getCache("fag").evictExpired(Long.MAX_VALUE)
    }

    @Test
    fun `empty list returned for empty FINT cache`() {
        assertEquals(fetchAllFag().totalItems, 0, "Expected empty response when FINT cache is empty")
    }

    @Test
    fun `full sync with single resource yields single entry in cache`() {
        val fagA = updateFag("A")
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(1, fagResources.size, "The cache should contain one entry")
            assertEquals(fagA, fagResources[0])
        }
    }

    @Test
    fun `full sync with new resources purges previously synced resource`() {
        // First full-sync
        updateFag("A")

        // Seconds full-sync
        val corrIdSecondFullSync = UUID.randomUUID().toString()
        val timestamp = clock.millis()
        val fagB =
            updateFag(
                "B",
                timestamp = timestamp,
                corrId = corrIdSecondFullSync,
                syncType = SyncType.FULL,
                totalSize = 2,
            )
        val fagC =
            updateFag(
                "C",
                timestamp = timestamp,
                corrId = corrIdSecondFullSync,
                syncType = SyncType.FULL,
                totalSize = 2,
            )

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(2, fagResources.size, "The cache should contain two entries")
            // Should be ordered by (timestamp, resourceId) — B < C with equal timestamps
            assertEquals(fagB, fagResources[0])
            assertEquals(fagC, fagResources[1])
        }
    }

    @Test
    fun `resources inserted out of timestamp order are returned sorted by timestamp`() {
        val timestamp = clock.millis()
        val corrId = UUID.randomUUID().toString()

        // Insert in reverse timestamp order
        val fagC = updateFag("C", timestamp = timestamp + 2, corrId = corrId, totalSize = 3)
        val fagA = updateFag("A", timestamp = timestamp, corrId = corrId, totalSize = 3)
        val fagB = updateFag("B", timestamp = timestamp + 1, corrId = corrId, totalSize = 3)

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(3, fagResources.size, "The cache should contain three entries")
            assertEquals(fagA, fagResources[0], "Oldest resource should be first")
            assertEquals(fagB, fagResources[1])
            assertEquals(fagC, fagResources[2], "Newest resource should be last")
        }
    }

    @Test
    fun `resources with the same timestamp are ordered by resource id`() {
        val timestamp = clock.millis()
        val corrId = UUID.randomUUID().toString()

        // Insert in non-alphabetical order, all sharing the same timestamp
        val fagZ = updateFag("Z", timestamp = timestamp, corrId = corrId, totalSize = 3)
        val fagA = updateFag("A", timestamp = timestamp, corrId = corrId, totalSize = 3)
        val fagM = updateFag("M", timestamp = timestamp, corrId = corrId, totalSize = 3)

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(3, fagResources.size, "The cache should contain three entries")
            assertEquals(fagA, fagResources[0], "Resource with lowest id should be first")
            assertEquals(fagM, fagResources[1])
            assertEquals(fagZ, fagResources[2], "Resource with highest id should be last")
        }
    }

    @Test
    fun `full sync updates previously synced resources`() {
        // First full-sync
        val corrIdFirst = UUID.randomUUID().toString()
        val timestampFirst = clock.millis()
        updateFag("B", timestamp = timestampFirst, corrId = corrIdFirst, totalSize = 2)
        updateFag("C", timestamp = timestampFirst, corrId = corrIdFirst, totalSize = 2)

        // Seconds full-sync
        val corrIdSecond = UUID.randomUUID().toString()
        val timestampSecond = clock.millis()
        val fagB =
            updateFag(
                "B",
                timestamp = timestampSecond,
                corrId = corrIdSecond,
                totalSize = 2,
                descriptionToken = "Updated",
            )
        val fagC =
            updateFag(
                "C",
                timestamp = timestampSecond,
                corrId = corrIdSecond,
                totalSize = 2,
                descriptionToken = "Updated",
            )

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(2, fagResources.size, "The cache should contain two entries")
            // Should be updated with new description
            assertEquals(fagB, fagResources[0])
            assertEquals(fagC, fagResources[1])
        }
    }

    @Test
    fun `delta sync updates one of two existing resources`() {
        // First full-sync
        val corrIdFirst = UUID.randomUUID().toString()
        val fagB = updateFag("B", corrId = corrIdFirst, totalSize = 2)
        updateFag("C", corrId = corrIdFirst, totalSize = 2)

        // Delta-sync updating one of two resources
        val fagC = updateFag("C", syncType = SyncType.DELTA, descriptionToken = "Delta updated")
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(2, fagResources.size, "The cache should contain two entries")
            // Should be updated with new description
            assertEquals(fagB, fagResources[0])
            assertEquals(fagC, fagResources[1])
        }
    }

    @Test
    fun `delete sync removes one of two existing resources`() {
        // First full-sync
        val corrIdFirst = UUID.randomUUID().toString()
        updateFag("B", corrId = corrIdFirst, totalSize = 2)
        val fagC = updateFag("C", corrId = corrIdFirst, totalSize = 2)

        // Delete sync
        deleteFag("B")

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertEquals(1, fagResources.size, "The cache should contain one entry")
            // Fag C should be updated with a new description
            assertEquals(fagC, fagResources[0])
        }
    }

    @Test
    fun `delete sync removing all resources yields empty cache`() {
        // First full-sync
        val corrIdFullSync = UUID.randomUUID().toString()
        updateFag("B", corrId = corrIdFullSync, totalSize = 2)
        updateFag("C", corrId = corrIdFullSync, totalSize = 2)

        // Delete-sync removing all resources
        val corrIdDelete = UUID.randomUUID().toString()
        deleteFag("B", correlationId = corrIdDelete, totalSize = 2)
        deleteFag("C", correlationId = corrIdDelete, totalSize = 2)

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            val fagResources = fetchAllFagResources()
            assertTrue(fagResources.isEmpty(), "The cache should be empty")
            assertEquals(0, cacheService.getCache("Fag").size)
        }
    }

    @Test
    @Disabled // Disabled until someone can fix the flakiness
    fun `full sync with 10 003 resources yields all records`() {
        val corrId = UUID.randomUUID().toString()
        val resourceCount = 10003
        for (resourceId in 0 until resourceCount) {
            updateFag(resourceId.toString(), corrId = corrId, totalSize = resourceCount)
        }

        await.atMost(Duration.ofSeconds(120)).untilAsserted {
            val fagResources = fetchAllFagResourcesPaginated()
            assertEquals(resourceCount, fagResources.size, "The cache should contain all inserted resources")
            for (resourceId in 0 until resourceCount) {
                val fagResource = fagResources[resourceId]
                assertEquals("systemid-fag-$resourceId", fagResource.systemId.identifikatorverdi)
                assertEquals("Fag-$resourceId", fagResource.navn)
                assertEquals("Beskrivelse fag $resourceId ", fagResource.beskrivelse)
            }
        }
    }

    @Test
    @Disabled // Disabled until someone can fix the flakiness
    fun `fetching 10 003 resources as pages of 1000 yields 11 pages`() {
        val corrId = UUID.randomUUID().toString()
        val resourceCount = 10003
        for (resourceId in 0 until resourceCount) {
            updateFag(resourceId.toString(), corrId = corrId, totalSize = resourceCount)
        }

        val fagResources = mutableListOf<FagResource>()
        val pageSize = 1000

        var uri = "/utdanning/timeplan/fag?size=$pageSize"
        var pageNumber = 0
        var previousTotalElements = -1

        while (uri.isNotBlank()) {
            val response = rest.getForEntity<FintResourcesPage>(uri)

            assertEquals(HttpStatus.OK, response.statusCode, "Page $pageNumber failed")

            val resourcesPage = response.body
            assertNotNull(resourcesPage, "Response body should not be null on page $pageNumber")

            // Detect infinite loop / broken pagination
            assertTrue(
                resourcesPage.totalItems >= previousTotalElements,
                "totalElements should never decrease",
            )
            previousTotalElements = resourcesPage.totalItems

            val pageResources = resourcesPage.getResources(objectMapper, FagResource::class.java)
            fagResources.addAll(pageResources)

            uri = resourcesPage.links["next"]
                ?.firstOrNull()
                ?.href
                ?.replace(Regex("^https?://[^:/]+(:\\d+)?"), "")
                ?.takeIf { it.isNotBlank() }
                ?: ""

            pageNumber++
        }

        val expectedNumberOfPages = (resourceCount + pageSize - 1) / pageSize
        assertEquals(expectedNumberOfPages, pageNumber, "Expected 11 pages to read all resources")
        assertEquals(resourceCount, fagResources.size, "The cache should contain all inserted resources")
        for (resourceId in 0 until resourceCount) {
            val fagResource = fagResources[resourceId]
            assertEquals("systemid-fag-$resourceId", fagResource.systemId.identifikatorverdi)
            assertEquals("Fag-$resourceId", fagResource.navn)
            assertEquals("Beskrivelse fag $resourceId ", fagResource.beskrivelse)
        }
    }

    private fun updateFag(
        id: String,
        syncType: SyncType = SyncType.FULL,
        timestamp: Long = clock.millis(),
        totalSize: Int = 1,
        corrId: String = UUID.randomUUID().toString(),
        descriptionToken: String = "",
    ): FintResource {
        val fag = createFagDto("systemid-fag-$id", "Fag-$id", "Beskrivelse fag $id $descriptionToken")
        sendKafkaEntityRecord(fag, timestamp, syncType, totalSize, corrId, resourceName = "fag")
        return fag
    }

    private fun sendKafkaEntityRecord(
        resource: FintResource,
        timestamp: Long,
        syncType: SyncType,
        totalSize: Int,
        correlationId: String = UUID.randomUUID().toString(),
        resourceName: String,
    ) {
        val key =
            requireNotNull(resource.identifikators["systemId"]?.identifikatorverdi) {
                "Missing value for systemId identifikatorverdi"
            }
        entityProducer
            .publish(resourceName, resource, key, syncType, correlationId, totalSize.toLong(), timestamp)
            .get(10, TimeUnit.SECONDS)
    }

    private fun deleteFag(
        id: String,
        timestamp: Long = clock.millis(),
        totalSize: Long = 1,
        correlationId: String = UUID.randomUUID().toString(),
    ) {
        entityProducer
            .publish("fag", null, "systemid-fag-$id", SyncType.DELETE, correlationId, totalSize, timestamp)
            .get(10, TimeUnit.SECONDS)
    }

    private fun fetchAllFag(): FintResourcesPage {
        val response = rest.getForEntity<FintResourcesPage>("/utdanning/timeplan/fag")
        assertEquals(HttpStatus.OK, response.statusCode)

        val resourcesPage = response.body ?: throw IllegalStateException("Response body is null")
        assertTrue { resourcesPage.embedded.entries.size == resourcesPage.size }
        return resourcesPage
    }

    private fun fetchAllFagResources(): List<FagResource> {
        val fagResponse = fetchAllFag()
        return fagResponse.getResources(objectMapper, FagResource::class.java)
    }

    private fun fetchAllFagResourcesPaginated(
        pageSize: Int = 5000,
        maxPages: Int = 100,
    ): List<FagResource> {
        val allResources = mutableListOf<FagResource>()
        var uri = "/utdanning/timeplan/fag?size=$pageSize"

        for (i in 0 until maxPages) {
            if (uri.isBlank()) break

            val response = rest.getForEntity<FintResourcesPage>(uri)
            assertEquals(HttpStatus.OK, response.statusCode)

            val page = response.body ?: break
            allResources.addAll(page.getResources(objectMapper, FagResource::class.java))

            // Strips the scheme and host (e.g. "https://api.example.com:8080/foo/bar" → "/foo/bar")
            uri = page.links["next"]
                ?.firstOrNull()
                ?.href
                ?.replace(Regex("^https?://[^:/]+(:\\d+)?"), "")
                ?.takeIf { it.isNotBlank() }
                ?: ""
        }

        return allResources
    }

    private fun createFagDto(
        systemId: String,
        navn: String,
        beskrivelse: String,
    ): FagResource {
        val systemIdentifikator = Identifikator().apply { identifikatorverdi = systemId }
        val fag = FagResource()
        fag.systemId = systemIdentifikator
        fag.navn = navn
        fag.beskrivelse = beskrivelse
        fag.links["self"] = listOf(Link("https://$fintOrg/$fintDomain/$fintPackage/fag/systemid/$systemId"))
        return fag
    }
}
