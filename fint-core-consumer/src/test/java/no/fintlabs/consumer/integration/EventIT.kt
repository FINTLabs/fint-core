package no.fintlabs.consumer.integration

import no.fintlabs.Application
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.cache.CacheService
import no.fintlabs.config.KafkaTestConfig
import no.fintlabs.utils.EntityProducer
import no.fintlabs.utils.ResponseEventProducer
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import org.springframework.test.web.reactive.server.returnResult
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
    ],
)
@Import(KafkaTestConfig::class)
@DirtiesContext
class EventIT {
    @LocalServerPort
    private var port: Int = 0

    private val client by lazy {
        WebTestClient
            .bindToServer()
            .baseUrl("http://localhost:$port/utdanning/elev")
            .responseTimeout(Duration.ofSeconds(10))
            .build()
    }

    @Autowired
    lateinit var entityProducer: EntityProducer

    @Autowired
    lateinit var responseEventProducer: ResponseEventProducer

    @Autowired
    lateinit var cacheService: CacheService

    private val resourceName = "elev"
    private val elevId = "123"

    @AfterEach
    fun tearDown() {
        cacheService.getCache(resourceName).evictExpired(Long.MAX_VALUE)
    }

    @Test
    fun `POST resource - adapter responds FAILED - status endpoint returns 500`() {
        val corrId = postResourceAndGetCorrId()

        responseEventProducer.publish(buildResponse(corrId, OperationType.CREATE, failed = true)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .is5xxServerError
        }
    }

    @Test
    fun `POST resource - adapter responds REJECTED - status endpoint returns 400`() {
        val corrId = postResourceAndGetCorrId()

        responseEventProducer.publish(buildResponse(corrId, OperationType.CREATE, rejected = true)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `POST resource - adapter responds CONFLICTED - status endpoint returns 409`() {
        val corrId = postResourceAndGetCorrId()

        responseEventProducer.publish(buildResponse(corrId, OperationType.CREATE, conflicted = true)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isEqualTo(409)
        }
    }

    @Test
    fun `POST resource with validate=true - adapter responds VALIDATED - status endpoint returns 200`() {
        val corrId = postResourceAndGetCorrId(validate = true)

        responseEventProducer.publish(buildResponse(corrId, OperationType.VALIDATE)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isOk
        }
    }

    @Test
    fun `POST resource - adapter responds CREATE success - status endpoint returns 201 once cache is consistent`() {
        val handledAt = System.currentTimeMillis()

        // Put resource in cache with the same timestamp as handledAt to satisfy cache consistency check
        entityProducer
            .publish(
                resourceName = resourceName,
                resource = createElev(),
                resourceId = elevId,
                syncType = SyncType.FULL,
                syncCorrId = UUID.randomUUID().toString(),
                syncTotalSize = 1,
                timestamp = handledAt,
            ).get()

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assert(cacheService.getCache(resourceName).get(elevId) != null)
        }

        val corrId = postResourceAndGetCorrId()

        responseEventProducer.publish(buildResponse(corrId, OperationType.CREATE, handledAt = handledAt)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isCreated
        }
    }

    @Test
    fun `status endpoint returns 202 while adapter has not yet responded`() {
        val corrId = postResourceAndGetCorrId()

        // Wait until the request event has been consumed and tracked
        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isAccepted
        }
    }

    @Test
    fun `PUT resource - adapter responds UPDATE success - status endpoint returns 201 once cache is consistent`() {
        val handledAt = System.currentTimeMillis()

        entityProducer
            .publish(
                resourceName = resourceName,
                resource = createElev(),
                resourceId = elevId,
                syncType = SyncType.FULL,
                syncCorrId = UUID.randomUUID().toString(),
                syncTotalSize = 1,
                timestamp = handledAt,
            ).get()

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            assert(cacheService.getCache(resourceName).get(elevId) != null)
        }

        val corrId = putResourceAndGetCorrId()

        responseEventProducer
            .publish(buildResponse(corrId, OperationType.UPDATE, handledAt = handledAt))
            .get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isCreated
        }
    }

    @Test
    fun `POST resource - adapter responds CREATE success but cache not yet consistent - status endpoint returns 202`() {
        val corrId = postResourceAndGetCorrId()

        val unmatchedHandledAt = System.currentTimeMillis() + Duration.ofMinutes(10).toMillis()

        responseEventProducer.publish(buildResponse(corrId, OperationType.CREATE, handledAt = unmatchedHandledAt)).get()

        // Status remains 202: cache consistency check fails because no resource with this timestamp exists
        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isAccepted
        }
    }

    @Test
    fun `POST resource - adapter responds DELETE success - status endpoint returns 204`() {
        val corrId = postResourceAndGetCorrId()

        responseEventProducer.publish(buildResponse(corrId, OperationType.DELETE)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isNoContent
        }
    }

    @Test
    fun `POST resource with validate=true - adapter responds FAILED - status endpoint returns 500`() {
        val corrId = postResourceAndGetCorrId(validate = true)

        responseEventProducer.publish(buildResponse(corrId, OperationType.VALIDATE, failed = true)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .is5xxServerError
        }
    }

    @Test
    fun `POST resource with validate=true - adapter responds REJECTED - status endpoint returns 400`() {
        val corrId = postResourceAndGetCorrId(validate = true)

        responseEventProducer.publish(buildResponse(corrId, OperationType.VALIDATE, rejected = true)).get()

        await.atMost(Duration.ofSeconds(10)).untilAsserted {
            client
                .get()
                .uri("/$resourceName/status/$corrId")
                .exchange()
                .expectStatus()
                .isBadRequest
        }
    }

    @Test
    fun `status endpoint returns 410 for unknown corrId`() {
        client
            .get()
            .uri("/$resourceName/status/unknown-corr-id")
            .exchange()
            .expectStatus()
            .isEqualTo(410)
    }

    private fun putResourceAndGetCorrId(): String {
        val location =
            client
                .put()
                .uri("/$resourceName/systemId/$elevId")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("systemId" to mapOf("identifikatorverdi" to elevId)))
                .exchange()
                .expectStatus()
                .isAccepted
                .returnResult(Void::class.java)
                .responseHeaders
                .location
                ?.toString()
                ?: error("No Location header in response")

        return location.substringAfterLast("/")
    }

    private fun postResourceAndGetCorrId(validate: Boolean = false): String {
        val uri = if (validate) "/$resourceName?validate=true" else "/$resourceName"
        val location =
            client
                .post()
                .uri(uri)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(mapOf("systemId" to mapOf("identifikatorverdi" to elevId)))
                .exchange()
                .expectStatus()
                .isAccepted
                .returnResult<Void>()
                .responseHeaders
                .location
                ?.toString()
                ?: error("No Location header in response")

        return location.substringAfterLast("/")
    }

    private fun buildResponse(
        corrId: String,
        operationType: OperationType,
        failed: Boolean = false,
        rejected: Boolean = false,
        conflicted: Boolean = false,
        handledAt: Long = System.currentTimeMillis(),
    ): ResponseFintEvent =
        ResponseFintEvent
            .builder()
            .corrId(corrId)
            .operationType(operationType)
            .failed(failed)
            .errorMessage(if (failed) "Adapter error" else null)
            .rejected(rejected)
            .rejectReason(if (rejected) "Rejected by adapter" else null)
            .conflicted(conflicted)
            .conflictReason(if (conflicted) "Resource already exists" else null)
            .handledAt(handledAt)
            .value(SyncPageEntry.of(elevId, if (conflicted) createElev() else null))
            .orgId("foo.org")
            .adapterId("test-adapter")
            .build()

    private fun createElev() =
        ElevResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = elevId }
        }
}
