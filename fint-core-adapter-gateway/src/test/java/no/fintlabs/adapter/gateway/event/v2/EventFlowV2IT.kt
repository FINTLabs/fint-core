package no.fintlabs.adapter.gateway.event.v2

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import no.fintlabs.adapter.gateway.security.DenialReason
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.EventCapability
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.v2.event.EventOperation
import no.fintlabs.adapter.models.v2.event.EventRequest
import no.fintlabs.adapter.models.v2.event.EventResponse
import no.fintlabs.adapter.models.v2.event.EventStatus
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
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

class EventFlowV2IT : GatewayIntegrationTestBase() {
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
        mongoTemplate.dropCollection(OrgStore.COLLECTION_NAME)
    }

    @Test
    fun `an adapter without event capabilities is refused and told why`() {
        register()

        mockMvc
            .perform(get("/adapter/v2/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.NO_EVENT_CAPABILITIES.detail))
    }

    @Test
    fun `only the operations in the event capabilities are served, in the v2 shape`() {
        register(EventOperation.READ)
        val read = seed(EventOperation.READ) { filter = "navn/fornavn eq 'Ola'" }
        seed(EventOperation.CREATE)

        mockMvc
            .perform(get("/adapter/v2/event/$domainName/$packageName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].corrId").value(read.corrId))
            .andExpect(jsonPath("$[0].operation").value("READ"))
            .andExpect(jsonPath("$[0].filter").value("navn/fornavn eq 'Ola'"))
            .andExpect(jsonPath("$[0].maxResults").value(10))
    }

    @Test
    fun `a v1 adapter is never served a read event`() {
        register()
        seed(EventOperation.READ)
        val create = seed(EventOperation.CREATE)

        mockMvc
            .perform(get("/provider/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].corrId").value(create.corrId))
            .andExpect(jsonPath("$[0].operationType").value("CREATE"))
    }

    @Test
    fun `read results are stored on the event and never written to the cache`() {
        register(EventOperation.READ)
        val read = seed(EventOperation.READ)

        answer(answerTo(read, EventStatus.SUCCEEDED, "1", "2")).andExpect(status().isOk)

        val stored = eventStore.findByCorrId(read.corrId, eventCollection)
        assertThat(stored?.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored?.response?.resources?.map { it.identifier }).containsExactly("1", "2")
        assertThat(resourceStore.findByResourceId("1", resourceCollection)).isNull()
        assertThat(resourceStore.findByResourceId("2", resourceCollection)).isNull()
    }

    @Test
    fun `a read that found too many resources is refused and the event stays pending`() {
        register(EventOperation.READ)
        val read = seed(EventOperation.READ) { maxResults = 1 }

        answer(answerTo(read, EventStatus.SUCCEEDED, "1", "2"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value(containsString("Answer REJECTED")))

        assertThat(eventStore.findByCorrId(read.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `an answer without a status is refused before the event is looked up`() {
        register(EventOperation.READ)
        val unknown =
            EventResponse
                .builder()
                .corrId("unknown")
                .orgId(orgId)
                .build()

        answer(unknown)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("status"))
    }

    @Test
    fun `a resource without an identifier is refused with the field that is wrong`() {
        register(EventOperation.READ)
        val read = seed(EventOperation.READ)
        val response = answerTo(read, EventStatus.SUCCEEDED, "1").also { it.resources[0].identifier = "" }

        answer(response)
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("resources[0].identifier"))

        assertThat(eventStore.findByCorrId(read.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `a successful create through v2 saves the resource to the cache`() {
        register(EventOperation.CREATE)
        val create = seed(EventOperation.CREATE)

        answer(answerTo(create, EventStatus.SUCCEEDED, "123")).andExpect(status().isOk)

        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNotNull
        assertThat(eventStore.findByCorrId(create.corrId, eventCollection)?.status).isEqualTo(EventState.ANSWERED)
    }

    @Test
    fun `an answer to an operation the adapter has not listed is refused`() {
        register(EventOperation.READ)
        val create = seed(EventOperation.CREATE)

        answer(answerTo(create, EventStatus.SUCCEEDED, "123"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.EVENT_NOT_IN_CONTRACT.detail))

        assertThat(eventStore.findByCorrId(create.corrId, eventCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `a second answer to the same event gets 404, as in v1`() {
        register(EventOperation.READ)
        val read = seed(EventOperation.READ)

        answer(answerTo(read, EventStatus.SUCCEEDED)).andExpect(status().isOk)
        answer(answerTo(read, EventStatus.SUCCEEDED)).andExpect(status().isNotFound)
    }

    @Test
    fun `a create on a resource without full sync is accepted and saved to the cache`() {
        register(syncs = false, EventOperation.CREATE)
        val create = seed(EventOperation.CREATE)

        answer(answerTo(create, EventStatus.SUCCEEDED, "123")).andExpect(status().isOk)

        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNotNull
    }

    private fun register(vararg operations: EventOperation) = register(syncs = true, *operations)

    private fun register(
        syncs: Boolean,
        vararg operations: EventOperation,
    ) {
        mockMvc
            .perform(
                post("/adapter/v2/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract(syncs, *operations)))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }

    private fun contract(
        syncs: Boolean,
        vararg operations: EventOperation,
    ): AdapterContract =
        adapterContract().apply {
            if (!syncs) capabilities = emptySet<AdapterCapability>()
            eventCapabilities =
                if (operations.isEmpty()) {
                    emptySet()
                } else {
                    setOf(EventCapability(domainName, packageName, resourceName, operations.toSet()))
                }
        }

    private fun seed(
        operation: EventOperation,
        block: EventRequest.() -> Unit = {},
    ): EventRequest {
        val now = System.currentTimeMillis()
        val request =
            EventRequest
                .builder()
                .corrId(UUID.randomUUID().toString())
                .orgId(orgId)
                .domainName(domainName)
                .packageName(packageName)
                .resourceName(resourceName)
                .operation(operation)
                .created(now)
                .deadline(now + 900_000)
                .value(if (operation == EventOperation.READ) null else """{"systemId":{"identifikatorverdi":"123"}}""")
                .maxResults(if (operation == EventOperation.READ) 10 else null)
                .build()
                .apply(block)

        eventStore.save(request, Instant.ofEpochMilli(now).plusSeconds(1_800), eventCollection)
        return request
    }

    private fun answerTo(
        request: EventRequest,
        status: EventStatus,
        vararg identifiers: String,
    ): EventResponse =
        EventResponse
            .builder()
            .corrId(request.corrId)
            .orgId(request.orgId)
            .status(status)
            .resources(
                identifiers
                    .map { SyncPageEntry.of(it, mapOf("systemId" to mapOf("identifikatorverdi" to it))) }
                    .toMutableList(),
            ).build()

    private fun answer(response: EventResponse): ResultActions =
        mockMvc.perform(
            post("/adapter/v2/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(response))
                .with(authentication(mockPrincipal)),
        )
}
