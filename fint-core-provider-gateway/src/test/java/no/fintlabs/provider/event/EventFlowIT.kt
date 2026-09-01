package no.fintlabs.provider.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.fintlabs.provider.GatewayIntegrationTestBase
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.ResourceWrite
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.http.MediaType
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Instant
import java.util.UUID

class EventFlowIT : GatewayIntegrationTestBase() {
    @Autowired
    private lateinit var eventStore: EventStore

    @Autowired
    private lateinit var eventExpiryService: EventExpiryService

    @Autowired
    private lateinit var providerProperties: ProviderProperties

    @Autowired
    private lateinit var mongoTemplate: MongoTemplate

    @Autowired
    private lateinit var resourceStore: ResourceStore

    @Autowired
    private lateinit var orgStore: OrgStore

    private val adapterCollection get() = OrgId.from(orgId).toEventCollectionName()
    private val sweeperCollection get() = providerProperties.orgId.toEventCollectionName()
    private val resourceCollection get() = "test_org_no_${domainName}_${packageName}_$resourceName"

    @BeforeEach
    fun cleanCollections() {
        mongoTemplate.dropCollection(adapterCollection)
        mongoTemplate.dropCollection(sweeperCollection)
        mongoTemplate.dropCollection(resourceCollection)
        mongoTemplate.dropCollection(OrgStore.COLLECTION_NAME)
    }

    @Test
    fun `serves pending requests to the adapter and filters by component`() {
        val request = seedRequest(adapterCollection, orgId)

        mockMvc
            .perform(get("/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].corrId").value(request.corrId))

        mockMvc
            .perform(get("/event/administrasjon").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test
    fun `does not serve a request past its deadline`() {
        seedRequest(adapterCollection, orgId, ttlMillis = -1_000)

        mockMvc
            .perform(get("/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test
    fun `an adapter response attaches once and the request is never re-served`() {
        val request = seedRequest(adapterCollection, orgId)
        val response = responseFor(request)

        mockMvc
            .perform(
                post("/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(response))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored?.response).isNotNull

        val entry = resourceStore.findByResourceId("123", resourceCollection)
        assertThat(entry).isNotNull
        assertThat(entry!!.lastModified.toEpochMilli()).isEqualTo(stored!!.response!!.handledAt)

        mockMvc
            .perform(get("/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))

        mockMvc
            .perform(
                post("/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(response))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `an answer after the deadline is rejected and writes nothing`() {
        val request = seedRequest(adapterCollection, orgId, ttlMillis = -1_000)
        val response = responseFor(request)

        mockMvc
            .perform(
                post("/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(response))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isNotFound)

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.response).isNull()
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()

        val outcome = eventStore.markAnswered(response, adapterCollection)
        assertThat(outcome).isEqualTo(ClaimOutcome.Expired)
    }

    @Test
    fun `a failing entity write rolls the answer claim back`() {
        val request = seedRequest(adapterCollection, orgId)
        val response =
            responseFor(request).apply {
                value = SyncPageEntry.of("123", mapOf("systemId" to "will-crash-not-identifikator"))
            }

        mockMvc
            .perform(
                post("/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(response))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().is4xxClientError)

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.PENDING)
        assertThat(stored?.response).isNull()
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()

        mockMvc
            .perform(get("/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].corrId").value(request.corrId))
    }

    @Test
    fun `an expired claim beats a concurrent answer and the answer loses cleanly`() {
        val request = seedRequest(adapterCollection, orgId)
        assertThat(eventStore.markExpired(request.corrId, adapterCollection, Instant.now())).isFalse

        val flipped =
            eventStore.markExpired(
                request.corrId,
                adapterCollection,
                Instant.ofEpochMilli(request.timeToLive + 1),
            )
        assertThat(flipped).isTrue

        val outcome =
            eventStore.markAnswered(
                responseFor(request).apply { handledAt = request.timeToLive - 1 },
                adapterCollection,
            )
        assertThat(outcome).isEqualTo(ClaimOutcome.Expired)

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.EXPIRED)
        assertThat(stored?.response).isNull()
    }

    @Test
    fun `a response to an unknown corrId is not found`() {
        val request = seedRequest(adapterCollection, orgId)
        val response = responseFor(request).apply { corrId = "unknown-corr-id" }

        mockMvc
            .perform(
                post("/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(response))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isNotFound)
    }

    @Test
    fun `the sweeper answers overdue events with an expired response`() {
        orgStore.upsert(providerProperties.orgId.value)
        val overdue = seedRequest(sweeperCollection, providerProperties.orgId.value, ttlMillis = -1_000)
        val pending = seedRequest(sweeperCollection, providerProperties.orgId.value)

        eventExpiryService.expireOverdueEvents()

        val expired = eventStore.findByCorrId(overdue.corrId, sweeperCollection)
        assertThat(expired?.status).isEqualTo(EventState.EXPIRED)
        assertThat(expired?.response).isNull()

        val untouched = eventStore.findByCorrId(pending.corrId, sweeperCollection)
        assertThat(untouched?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `a stale buffered write cannot revert a fresher event write`() {
        val fresh = Elev(systemId = Identifikator(identifikatorverdi = "123"))
        val stale = Elev(systemId = Identifikator(identifikatorverdi = "123"), elevnummer = Identifikator(identifikatorverdi = "E-1"))

        val freshTime = Instant.ofEpochMilli(System.currentTimeMillis())
        resourceStore.saveAll(listOf(ResourceWrite("123", resourceCollection, fresh, freshTime)))
        resourceStore.saveAll(listOf(ResourceWrite("123", resourceCollection, stale, freshTime.minusSeconds(60))))

        val afterStale = resourceStore.findByResourceId("123", resourceCollection)
        assertThat(afterStale!!.lastModified).isEqualTo(freshTime)
        assertThat(afterStale.identifiers).hasSize(1)

        resourceStore.saveAll(listOf(ResourceWrite("123", resourceCollection, stale, freshTime.plusSeconds(60))))

        val afterNewer = resourceStore.findByResourceId("123", resourceCollection)
        assertThat(afterNewer!!.lastModified).isEqualTo(freshTime.plusSeconds(60))
        assertThat(afterNewer.identifiers).hasSize(2)
        assertThat(afterNewer.createdAt).isEqualTo(freshTime)
    }

    @Test
    fun `the sweeper covers sub-org collections but never foreign orgs`() {
        val primary = providerProperties.orgId.value
        orgStore.upsert("test.$primary")
        orgStore.upsert(orgId)
        val subOrgCollection = OrgId.from("test.$primary").toEventCollectionName()
        val subOrg = seedRequest(subOrgCollection, "test.$primary", ttlMillis = -1_000)
        val foreign = seedRequest(adapterCollection, orgId, ttlMillis = -1_000)

        eventExpiryService.expireOverdueEvents()

        assertThat(eventStore.findByCorrId(subOrg.corrId, subOrgCollection)?.status)
            .isEqualTo(EventState.EXPIRED)
        assertThat(eventStore.findByCorrId(foreign.corrId, adapterCollection)?.status)
            .isEqualTo(EventState.PENDING)

        mongoTemplate.dropCollection(subOrgCollection)
    }

    @Test
    fun `the sweeper skips an org that never registered`() {
        val overdue = seedRequest(sweeperCollection, providerProperties.orgId.value, ttlMillis = -1_000)

        eventExpiryService.expireOverdueEvents()

        assertThat(eventStore.findByCorrId(overdue.corrId, sweeperCollection)?.status)
            .isEqualTo(EventState.PENDING)
    }

    @Test
    fun `the sweeper never overwrites an answer that raced it`() {
        orgStore.upsert(providerProperties.orgId.value)
        val request = seedRequest(sweeperCollection, providerProperties.orgId.value, ttlMillis = -1_000)
        val adapterResponse =
            responseFor(request).apply {
                orgId = providerProperties.orgId.value
                handledAt = request.timeToLive - 1
            }
        eventStore.markAnswered(adapterResponse, sweeperCollection)

        eventExpiryService.expireOverdueEvents()

        val stored = eventStore.findByCorrId(request.corrId, sweeperCollection)
        assertThat(stored?.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored?.response?.isFailed).isFalse
        assertThat(stored?.response?.handledAt).isEqualTo(adapterResponse.handledAt)
    }

    private fun seedRequest(
        collectionName: String,
        requestOrgId: String,
        ttlMillis: Long = 900_000,
    ): RequestFintEvent {
        val request =
            RequestFintEvent().apply {
                corrId = UUID.randomUUID().toString()
                orgId = requestOrgId
                domainName = this@EventFlowIT.domainName
                packageName = this@EventFlowIT.packageName
                resourceName = this@EventFlowIT.resourceName
                operationType = OperationType.CREATE
                created = System.currentTimeMillis()
                timeToLive = created + ttlMillis
                value = """{"systemId":{"identifikatorverdi":"123"}}"""
            }

        eventStore.save(request, Instant.ofEpochMilli(request.created).plusSeconds(1_800), collectionName)
        return request
    }

    private fun responseFor(request: RequestFintEvent): ResponseFintEvent =
        ResponseFintEvent().apply {
            corrId = request.corrId
            orgId = request.orgId
            adapterId = this@EventFlowIT.adapterId
            operationType = OperationType.CREATE
            handledAt = System.currentTimeMillis()
            value = SyncPageEntry.of("123", mapOf("systemId" to mapOf("identifikatorverdi" to "123")))
        }
}
