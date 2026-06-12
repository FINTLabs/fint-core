package no.novari.fint.core.consumer.integration

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.fint.core.consumer.Application
import no.novari.fint.core.consumer.utils.ResponseEventProducer
import no.novari.fint.core.shared.cache.CacheService
import no.novari.fint.core.shared.event.EventStatusStore
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.awaitility.kotlin.await
import org.bson.Document
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.time.Duration
import java.util.Date

/**
 * Covers every branch of the event status endpoint: a client POST publishes a RequestFintEvent, an
 * adapter ResponseFintEvent (faked here via [ResponseEventProducer]) is consumed off Kafka, and
 * GET /status/{corrId} maps the outcome to HTTP. Expiry is forced deterministically by ageing the
 * stored request rather than waiting out a short retention window, so the whole class runs one config.
 */
@SpringBootTest(classes = [Application::class])
@AutoConfigureMockMvc
@EmbeddedKafka(partitions = 1, topics = ["foo-org.fint-core.event.utdanning-elev-response"])
@Import(ResponseEventProducer::class)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.properties.metadata.max.age.ms=500",
        "spring.kafka.properties.metadata.max.age.ms=500",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
        "fint.consumer.event.defaults.eviction=300s",
    ],
)
@DirtiesContext
class EventIT {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var cacheService: CacheService

    @Autowired
    private lateinit var responseEventProducer: ResponseEventProducer

    @Autowired
    private lateinit var eventStatusStore: EventStatusStore

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Test
    fun `create with a consistent cache returns 201`() {
        val id = "create-201"
        val corrId = post(id)
        cacheService.getCache(KEY).put(id, elevWithSelf(id), HANDLED_AT)

        publishUntilConsumed(response(corrId, id))

        getStatus(corrId).andExpect {
            status { isCreated() }
            header { exists("Location") }
        }
    }

    @Test
    fun `create returns 202 while the cache has not caught up`() {
        val id = "create-202"
        val corrId = post(id)
        cacheService.getCache(KEY).put(id, elev(id), HANDLED_AT - 1)

        publishUntilConsumed(response(corrId, id))

        getStatus(corrId).andExpect { status { isAccepted() } }
    }

    @Test
    fun `status returns 202 while no adapter response has arrived yet`() {
        val corrId = post("pending-202")

        getStatus(corrId).andExpect { status { isAccepted() } }
    }

    @Test
    fun `validate returns 200`() {
        val id = "validate-200"
        val corrId = post(id)

        publishUntilConsumed(response(corrId, id, operationType = OperationType.VALIDATE))

        getStatus(corrId).andExpect { status { isOk() } }
    }

    @Test
    fun `delete returns 204`() {
        val id = "delete-204"
        val corrId = post(id)

        publishUntilConsumed(response(corrId, id, operationType = OperationType.DELETE))

        getStatus(corrId).andExpect { status { isNoContent() } }
    }

    @Test
    fun `rejected response returns 400`() {
        val id = "rejected-400"
        val corrId = post(id)

        publishUntilConsumed(response(corrId, id, rejected = true))

        getStatus(corrId).andExpect { status { isBadRequest() } }
    }

    @Test
    fun `conflicted response returns 409`() {
        val id = "conflict-409"
        val corrId = post(id)

        publishUntilConsumed(response(corrId, id, conflicted = true))

        getStatus(corrId).andExpect { status { isConflict() } }
    }

    @Test
    fun `failed response returns 500`() {
        val id = "failed-500"
        val corrId = post(id)

        publishUntilConsumed(response(corrId, id, failed = true))

        getStatus(corrId).andExpect { status { isInternalServerError() } }
    }

    @Test
    fun `expired request returns 410`() {
        val id = "expired-410"
        val corrId = post(id)

        mongoTemplate
            .getCollection(EventStatusStore.COLLECTION)
            .updateOne(
                Document(EventStatusStore.FIELD_ID, corrId),
                Document("\$set", Document(EventStatusStore.FIELD_EXPIRE_AT, Date(0))),
            )

        getStatus(corrId).andExpect { status { isEqualTo(410) } }
    }

    @Test
    fun `request whose time-to-live passed without an adapter response returns 500`() {
        val corrId = "ttl-expired-${System.nanoTime()}"
        eventStatusStore.storeRequest(
            RequestFintEvent().apply {
                this.corrId = corrId
                orgId = "foo.org"
                domainName = "utdanning"
                packageName = "elev"
                resourceName = "elev"
                created = System.currentTimeMillis() - 10_000
                timeToLive = System.currentTimeMillis() - 1_000
            },
            System.currentTimeMillis() + 300_000,
        )

        getStatus(corrId).andExpect { status { isInternalServerError() } }
    }

    private fun post(resourceId: String): String =
        (
            mockMvc
                .post(BASE) {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"systemId":{"identifikatorverdi":"$resourceId"}}"""
                }.andExpect { status { isAccepted() } }
                .andReturn()
                .response
                .getHeader("Location")
                ?: error("No Location header in response")
        ).substringAfterLast("/")

    private fun getStatus(corrId: String) = mockMvc.get("$BASE/status/$corrId")

    private fun publishUntilConsumed(response: ResponseFintEvent) {
        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            responseEventProducer.publish(response, "utdanning", "elev").get()
            assertNotNull(eventStatusStore.getResponse(response.corrId), "response not consumed yet")
        }
    }

    private fun response(
        corrId: String,
        resourceId: String,
        operationType: OperationType = OperationType.CREATE,
        handledAt: Long = HANDLED_AT,
        failed: Boolean = false,
        rejected: Boolean = false,
        conflicted: Boolean = false,
    ): ResponseFintEvent =
        ResponseFintEvent
            .builder()
            .corrId(corrId)
            .orgId("foo.org")
            .operationType(operationType)
            .handledAt(handledAt)
            .failed(failed)
            .errorMessage(if (failed) "error" else null)
            .rejected(rejected)
            .rejectReason(if (rejected) "rejected" else null)
            .conflicted(conflicted)
            .conflictReason(if (conflicted) "conflict" else null)
            .value(SyncPageEntry.of(resourceId, mapOf("systemId" to mapOf("identifikatorverdi" to resourceId))))
            .build()

    private fun elev(id: String): ElevResource =
        ElevResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = id }
        }

    private fun elevWithSelf(id: String): ElevResource =
        elev(id).apply {
            addSelf(Link.with("https://test.felleskomponent.no/utdanning/elev/elev/systemid/$id"))
        }

    private companion object {
        const val BASE = "/utdanning/elev/elev"
        const val KEY = "utdanning_elev_elev"
        const val HANDLED_AT = 1_000_000L
    }
}
