package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import no.fintlabs.adapter.gateway.event.request.EventInsertWatcher
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
import org.awaitility.kotlin.until
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class EventLongPollIT : GatewayIntegrationTestBase() {
    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var eventInsertWatcher: EventInsertWatcher

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    private val collection get() = OrgId.from(orgId).toEventCollectionName()

    @BeforeEach
    fun cleanCollection() {
        mongoTemplate.dropCollection(collection)
    }

    @Test
    fun `a held request answers with an event when it is stored`() {
        await until { eventInsertWatcher.watching }

        // The wait is shorter than the timer of a held request, so only the change stream can
        // wake it with the event in time.
        val answer = CompletableFuture.supplyAsync { getEvents(wait = 10) }
        Thread.sleep(500)
        assertThat(answer).isNotDone

        val request = storeRequest()

        val body = answer.get(5, TimeUnit.SECONDS)
        assertThat(body).contains(request.corrId)
    }

    @Test
    fun `a request without a wait answers at once with nothing`() {
        assertThat(getEvents(wait = null)).isEqualTo("[]")
    }

    private fun getEvents(wait: Int?): String {
        val request = get("/provider/event/$domainName").with(authentication(mockPrincipal))
        wait?.let { request.param("wait", it.toString()) }

        return mockMvc
            .perform(request)
            .andReturn()
            .response.contentAsString
    }

    private fun storeRequest(): RequestFintEvent {
        val request =
            RequestFintEvent().apply {
                corrId = UUID.randomUUID().toString()
                orgId = this@EventLongPollIT.orgId
                domainName = this@EventLongPollIT.domainName
                packageName = this@EventLongPollIT.packageName
                resourceName = this@EventLongPollIT.resourceName
                operationType = OperationType.CREATE
                created = System.currentTimeMillis()
                timeToLive = created + 900_000
                value = """{"systemId":{"identifikatorverdi":"123"}}"""
            }

        eventStore.save(request, Instant.ofEpochMilli(request.created).plusSeconds(1_800), collection)
        return request
    }
}
