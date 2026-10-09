package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import no.fintlabs.adapter.gateway.security.DenialReason
import no.fintlabs.adapter.models.EventCapability
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.store.ResourceStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

/**
 * The event endpoints seen from an adapter whose contract lists event capabilities. The events
 * are put straight into the org's event collection, the way client-api stores them.
 */
class LiveReadFlowIT : GatewayIntegrationTestBase() {
    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var resourceStore: ResourceStore

    private val eventCollection get() = OrgId.from(orgId).toEventCollectionName()
    private val resourceCollection get() = "test_fintlabs_no_${domainName}_${packageName}_$resourceName"

    @BeforeEach
    fun cleanCollections() {
        mongoTemplate.dropCollection(eventCollection)
        mongoTemplate.dropCollection(resourceCollection)
    }

    @Test
    fun `an adapter is served exactly the operations its contract lists, and the rest as before`() {
        registerAdapterThatReads(resourceName)
        val readElev = seed(OperationType.READ, resourceName)
        seed(OperationType.CREATE, resourceName)
        val createSkoleressurs = seed(OperationType.CREATE, "skoleressurs")

        mockMvc
            .perform(get("/provider/event/$domainName/$packageName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))
            .andExpect(jsonPath("$[?(@.corrId == '${readElev.corrId}')]").exists())
            .andExpect(jsonPath("$[?(@.corrId == '${createSkoleressurs.corrId}')]").exists())
    }

    @Test
    fun `an adapter without event capabilities is never served a READ`() {
        registerAdapter()
        seed(OperationType.READ, resourceName)
        val create = seed(OperationType.CREATE, resourceName)

        mockMvc
            .perform(get("/provider/event/$domainName/$packageName/$resourceName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].corrId").value(create.corrId))
    }

    @Test
    fun `the size cut happens after the contract filter`() {
        registerAdapter()
        seed(OperationType.READ, resourceName)
        seed(OperationType.READ, resourceName)
        val create = seed(OperationType.CREATE, resourceName)

        mockMvc
            .perform(get("/provider/event/$domainName/$packageName").param("size", "1").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].corrId").value(create.corrId))
    }

    @Test
    fun `a read answer is stored on the event, never in the cache, and the event is not served again`() {
        registerAdapterThatReads(resourceName)
        val read = seed(OperationType.READ, resourceName)

        answer(readAnswerFor(read, "123", "456")).andExpect(status().isOk)

        val stored = eventStore.findByCorrId(read.corrId, eventCollection)!!
        assertThat(stored.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored.response!!.values.map { it.identifier }).containsExactly("123", "456")
        assertThat((stored.response!!.values[0].resource as Map<*, *>)["systemId"]).isEqualTo(mapOf("identifikatorverdi" to "123"))
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()

        mockMvc
            .perform(get("/provider/event/$domainName/$packageName/$resourceName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(0))
    }

    @Test
    fun `an answer to a READ the contract does not list is refused and the event stays pending`() {
        registerAdapter()
        val read = seed(OperationType.READ, resourceName)

        answer(readAnswerFor(read, "123"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.EVENT_NOT_IN_CONTRACT.detail))

        assertThat(eventStore.findByCorrId(read.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `an answer to a write the contract lists the resource without is refused`() {
        registerAdapterThatReads(resourceName)
        val create = seed(OperationType.CREATE, resourceName)

        answer(writeAnswerFor(create, OperationType.CREATE))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.EVENT_NOT_IN_CONTRACT.detail))

        assertThat(eventStore.findByCorrId(create.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `an answer that names another operation than the request is refused and nothing is written`() {
        registerAdapter()
        val validate = seed(OperationType.VALIDATE, resourceName)

        answer(writeAnswerFor(validate, OperationType.CREATE)).andExpect(status().isBadRequest)

        assertThat(eventStore.findByCorrId(validate.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()
    }

    private fun registerAdapterThatReads(resource: String) {
        val contract =
            adapterContract().apply {
                eventCapabilities =
                    setOf(
                        EventCapability().apply {
                            domainName = this@LiveReadFlowIT.domainName
                            packageName = this@LiveReadFlowIT.packageName
                            resourceName = resource
                            operations = setOf(OperationType.READ)
                        },
                    )
            }
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    private fun answer(response: ResponseFintEvent): ResultActions =
        mockMvc.perform(
            post("/provider/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(response))
                .with(authentication(mockPrincipal)),
        )

    private fun seed(
        operation: OperationType,
        resource: String,
    ): RequestFintEvent {
        val request =
            RequestFintEvent().apply {
                corrId = UUID.randomUUID().toString()
                orgId = this@LiveReadFlowIT.orgId
                domainName = this@LiveReadFlowIT.domainName
                packageName = this@LiveReadFlowIT.packageName
                resourceName = resource
                operationType = operation
                created = System.currentTimeMillis()
                timeToLive = created + 900_000
                if (operation == OperationType.READ) {
                    filter = "systemId/identifikatorverdi eq '123'"
                } else {
                    value = """{"systemId":{"identifikatorverdi":"123"}}"""
                }
            }
        eventStore.save(request, Instant.ofEpochMilli(request.created).plusSeconds(1_800), eventCollection)
        return request
    }

    private fun readAnswerFor(
        request: RequestFintEvent,
        vararg ids: String,
    ): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = request.corrId
            orgId = request.orgId
            adapterId = this@LiveReadFlowIT.adapterId
            operationType = OperationType.READ
            handledAt = System.currentTimeMillis()
            values = ids.map { SyncPageEntry.of(it, mapOf("systemId" to mapOf("identifikatorverdi" to it))) }
        }

    private fun writeAnswerFor(
        request: RequestFintEvent,
        operation: OperationType,
    ): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = request.corrId
            orgId = request.orgId
            adapterId = this@LiveReadFlowIT.adapterId
            operationType = operation
            handledAt = System.currentTimeMillis()
            value = SyncPageEntry.of("123", mapOf("systemId" to mapOf("identifikatorverdi" to "123")))
        }
}
