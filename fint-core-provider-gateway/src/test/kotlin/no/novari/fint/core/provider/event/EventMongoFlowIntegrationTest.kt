package no.novari.fint.core.provider.event

import io.mockk.every
import io.mockk.mockk
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.core.provider.event.response.ResponseEventService
import no.novari.fint.core.shared.event.EventStatusStore
import no.novari.resource.server.authentication.CorePrincipal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.context.EmbeddedKafka
import java.util.UUID

/**
 * The provider's Mongo-mediated event flow: a request stored by the consumer is served to the
 * adapter from the shared event_status collection, and the adapter's response marks it handled
 * synchronously so it is never served again — no in-memory cache, no request topic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class EventMongoFlowIntegrationTest
    @Autowired
    constructor(
        private val eventStatusStore: EventStatusStore,
        private val eventController: EventController,
        private val responseEventService: ResponseEventService,
    ) {
        @Test
        fun `pending request is served to the adapter and disappears once the adapter responds`() {
            val corrId = UUID.randomUUID().toString()
            eventStatusStore.storeRequest(request(corrId), retention())

            val served = pendingEvents()
            assertTrue(served.any { it.corrId == corrId }, "the stored request must be served to the adapter")

            responseEventService.handleEvent(response(corrId), mockk<CorePrincipal>(relaxed = true))

            assertTrue(pendingEvents().none { it.corrId == corrId }, "a responded request must not be re-served")
            assertEquals(corrId, eventStatusStore.getResponse(corrId)?.corrId, "the response must be attached to the doc")
        }

        @Test
        fun `expired request is not served to the adapter`() {
            val corrId = UUID.randomUUID().toString()
            eventStatusStore.storeRequest(
                request(corrId).apply { timeToLive = System.currentTimeMillis() - 1_000 },
                retention(),
            )

            assertTrue(pendingEvents().none { it.corrId == corrId })
        }

        private fun pendingEvents(): List<RequestFintEvent> {
            val principal = mockk<CorePrincipal>(relaxed = true)
            every { principal.assets } returns setOf(ORG)
            return eventController.getEvents(principal, "utdanning", "vurdering", "elevfravar", 0).body.orEmpty()
        }

        private fun request(corrId: String) =
            RequestFintEvent().apply {
                this.corrId = corrId
                orgId = ORG
                domainName = "utdanning"
                packageName = "vurdering"
                resourceName = "elevfravar"
                operationType = OperationType.CREATE
                created = System.currentTimeMillis()
                timeToLive = System.currentTimeMillis() + 120_000
            }

        private fun response(corrId: String) =
            ResponseFintEvent().apply {
                this.corrId = corrId
                orgId = ORG
                operationType = OperationType.CREATE
                handledAt = System.currentTimeMillis()
                value =
                    SyncPageEntry().apply {
                        identifier = "systemid/$corrId"
                        resource = mapOf("systemId" to mapOf("identifikatorverdi" to "systemid/$corrId"))
                    }
            }

        private fun retention(): Long = System.currentTimeMillis() + 60 * 60 * 1000

        companion object {
            private const val ORG = "fintlabs.no"
        }
    }
