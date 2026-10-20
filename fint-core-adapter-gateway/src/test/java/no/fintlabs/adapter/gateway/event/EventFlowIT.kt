package no.fintlabs.adapter.gateway.event

import no.fintlabs.adapter.gateway.GatewayIntegrationTestBase
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.event.request.EventArrivals
import no.fintlabs.adapter.gateway.security.DenialReason
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.ClaimOutcome
import no.novari.core.shared.event.EventState
import no.novari.core.shared.event.EventStore
import no.novari.core.shared.event.toEventCollectionName
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.org.OrgStore
import no.novari.core.shared.store.ResourceStore
import no.novari.core.shared.store.Save
import no.novari.core.shared.web.PreferHeader
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.awaitility.kotlin.await
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.concurrent.thread

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

    @Autowired
    private lateinit var eventArrivals: EventArrivals

    private val adapterCollection get() = OrgId.from(orgId).toEventCollectionName()
    private val sweeperCollection get() = providerProperties.orgId.toEventCollectionName()
    private val resourceCollection get() = "test_fintlabs_no_${domainName}_${packageName}_$resourceName"

    @BeforeEach
    fun cleanCollections() {
        mongoTemplate.dropCollection(adapterCollection)
        mongoTemplate.dropCollection(sweeperCollection)
        mongoTemplate.dropCollection(resourceCollection)
        mongoTemplate.dropCollection(OrgStore.COLLECTION_NAME)
    }

    @BeforeEach
    fun registerContract() {
        registerAdapter()
    }

    @Test
    fun `serves pending requests under the domain`() {
        val request = seedRequest(adapterCollection, orgId)

        mockMvc
            .perform(get("/provider/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].corrId").value(request.corrId))
    }

    @Test
    fun `serves a domain the adapter has no role in`() {
        val request = seedRequest(adapterCollection, orgId, domain = "administrasjon", pkg = "personal")

        mockMvc
            .perform(get("/provider/event/administrasjon").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].corrId").value(request.corrId))
    }

    @Test
    fun `an answer for a component the adapter has no role for is accepted`() {
        val request = seedRequest(adapterCollection, orgId, domain = "administrasjon", pkg = "personal")
        val response =
            responseFor(request).apply {
                isFailed = true
                errorMessage = "The source system is down"
                value = null
            }

        answer(response).andExpect(status().isOk)

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored?.response?.isFailed).isTrue
    }

    @Test
    fun `an adapter without a contract is served its events and answers them, except READ`() {
        val unregistered = principal(username = "other@adapter.$orgId")
        val request = seedRequest(adapterCollection, orgId)
        seedRequest(adapterCollection, orgId, operation = OperationType.READ)

        mockMvc
            .perform(get("/provider/event/$domainName").with(authentication(unregistered)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].corrId").value(request.corrId))

        answer(responseFor(request), unregistered).andExpect(status().isOk)

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.status).isEqualTo(EventState.ANSWERED)
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNotNull
    }

    @Test
    fun `a failed answer may carry an empty value, which is dropped`() {
        val request = seedRequest(adapterCollection, orgId, operation = OperationType.UPDATE)
        val response =
            responseFor(request).apply {
                operationType = OperationType.UPDATE
                isFailed = true
                errorMessage = "The source system is down"
                value = SyncPageEntry()
            }

        answer(response).andExpect(status().isOk)

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.ANSWERED)
        assertThat(stored?.response?.isFailed).isTrue
        assertThat(stored?.response?.value).isNull()
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()
    }

    @Test
    fun `a conflicted answer needs the resource the source system holds`() {
        val request = seedRequest(adapterCollection, orgId, operation = OperationType.UPDATE)
        val response =
            responseFor(request).apply {
                operationType = OperationType.UPDATE
                isConflicted = true
                conflictReason = "Changed since it was read"
                value = null
            }

        answer(response)
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("has no value")))

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `an answer that names another operation is accepted and the request's operation decides`() {
        val request = seedRequest(adapterCollection, orgId)
        val response = responseFor(request).apply { operationType = OperationType.UPDATE }

        answer(response).andExpect(status().isOk)

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.response?.operationType)
            .isEqualTo(OperationType.CREATE)
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNotNull
    }

    @Test
    fun `a VALIDATE that passed needs no value`() {
        val request = seedRequest(adapterCollection, orgId, operation = OperationType.VALIDATE)
        val response =
            responseFor(request).apply {
                operationType = OperationType.VALIDATE
                value = null
            }

        answer(response).andExpect(status().isOk)

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.status).isEqualTo(EventState.ANSWERED)
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()
    }

    @Test
    fun `an answer without a corrId is a bad request`() {
        val request = seedRequest(adapterCollection, orgId)

        answer(responseFor(request).apply { corrId = null })
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].field").value("corrId"))

        assertThat(eventStore.findByCorrId(request.corrId, adapterCollection)?.status).isEqualTo(EventState.PENDING)
    }

    @Test
    fun `an answer that names another of the adapter's orgs than the event's is refused`() {
        val subOrg = "sub.$orgId"
        val subOrgCollection = OrgId.from(subOrg).toEventCollectionName()
        val request = seedRequest(subOrgCollection, subOrg)

        answer(responseFor(request).apply { orgId = this@EventFlowIT.orgId }, principal(assets = "$orgId,$subOrg"))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.EVENT_ORG_MISMATCH.detail))

        assertThat(eventStore.findByCorrId(request.corrId, subOrgCollection)?.status).isEqualTo(EventState.PENDING)
        mongoTemplate.dropCollection(subOrgCollection)
    }

    @Test
    fun `an answer cannot claim another sub-org's event`() {
        val otherOrg = "other.fintlabs.no"
        val otherCollection = OrgId.from(otherOrg).toEventCollectionName()
        val request = seedRequest(otherCollection, otherOrg)

        mockMvc
            .perform(
                post("/provider/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(responseFor(request)))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.ORG_NOT_IN_ASSETS.detail))

        mockMvc
            .perform(
                post("/provider/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(responseFor(request).apply { orgId = this@EventFlowIT.orgId }))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isNotFound)

        assertThat(eventStore.findByCorrId(request.corrId, otherCollection)?.status).isEqualTo(EventState.PENDING)
        mongoTemplate.dropCollection(otherCollection)
    }

    @Test
    fun `does not serve a request past its deadline`() {
        seedRequest(adapterCollection, orgId, ttlMillis = -1_000)

        mockMvc
            .perform(get("/provider/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))
    }

    @Test
    fun `an adapter response attaches once and the request is never re-served`() {
        val request = seedRequest(adapterCollection, orgId)
        val response = responseFor(request)

        mockMvc
            .perform(
                post("/provider/event")
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
            .perform(get("/provider/event/$domainName").with(authentication(mockPrincipal)))
            .andExpect(status().isOk)
            .andExpect(content().json("[]"))

        mockMvc
            .perform(
                post("/provider/event")
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
                post("/provider/event")
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
    fun `an answer whose resource does not fit the model is refused and the event stays pending`() {
        val request = seedRequest(adapterCollection, orgId)
        val response =
            responseFor(request).apply {
                value = SyncPageEntry.of("123", mapOf("systemId" to "will-crash-not-identifikator"))
            }

        answer(response)
            .andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("not a valid elev")))

        val stored = eventStore.findByCorrId(request.corrId, adapterCollection)
        assertThat(stored?.status).isEqualTo(EventState.PENDING)
        assertThat(stored?.response).isNull()
        assertThat(resourceStore.findByResourceId("123", resourceCollection)).isNull()

        mockMvc
            .perform(get("/provider/event/$domainName").with(authentication(mockPrincipal)))
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
                post("/provider/event")
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
        resourceStore.saveAll(listOf(Save("123", resourceCollection, fresh, freshTime)))
        resourceStore.saveAll(listOf(Save("123", resourceCollection, stale, freshTime.minusSeconds(60))))

        val afterStale = resourceStore.findByResourceId("123", resourceCollection)
        assertThat(afterStale!!.lastModified).isEqualTo(freshTime)
        assertThat(afterStale.identifiers).hasSize(1)

        resourceStore.saveAll(listOf(Save("123", resourceCollection, stale, freshTime.plusSeconds(60))))

        val afterNewer = resourceStore.findByResourceId("123", resourceCollection)
        assertThat(afterNewer!!.lastModified).isEqualTo(freshTime.plusSeconds(60))
        assertThat(afterNewer.identifiers).hasSize(2)
        assertThat(afterNewer.createdAt).isEqualTo(freshTime)
    }

    @Test
    fun `the sweeper covers sub-org collections but never foreign orgs`() {
        val primary = providerProperties.orgId.value
        val foreignOrgId = "vtfk.no"
        val foreignCollection = OrgId.from(foreignOrgId).toEventCollectionName()
        orgStore.upsert("test.$primary")
        orgStore.upsert(foreignOrgId)
        val subOrgCollection = OrgId.from("test.$primary").toEventCollectionName()
        val subOrg = seedRequest(subOrgCollection, "test.$primary", ttlMillis = -1_000)
        val foreign = seedRequest(foreignCollection, foreignOrgId, ttlMillis = -1_000)

        eventExpiryService.expireOverdueEvents()

        assertThat(eventStore.findByCorrId(subOrg.corrId, subOrgCollection)?.status)
            .isEqualTo(EventState.EXPIRED)
        assertThat(eventStore.findByCorrId(foreign.corrId, foreignCollection)?.status)
            .isEqualTo(EventState.PENDING)

        mongoTemplate.dropCollection(subOrgCollection)
        mongoTemplate.dropCollection(foreignCollection)
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

    @Test
    fun `without a wait preference an empty poll answers at once`() {
        listOf(null, "wait=0", "wait=soon").forEach { prefer ->
            val elapsed =
                timed {
                    mockMvc
                        .perform(pollEvents(prefer))
                        .andExpect(status().isOk)
                        .andExpect(content().json("[]"))
                        .andExpect(header().doesNotExist(PreferHeader.APPLIED))
                }

            assertThat(elapsed).`as`("Prefer: %s", prefer).isLessThan(Duration.ofSeconds(1))
        }
    }

    @Test
    fun `an empty long poll is held for the wait it applied, capped at the maximum`() {
        val elapsed =
            timed {
                mockMvc
                    .perform(pollEvents("wait=30"))
                    .andExpect(status().isOk)
                    .andExpect(content().json("[]"))
                    .andExpect(header().string(PreferHeader.APPLIED, "wait=2"))
            }

        assertThat(elapsed).isBetween(Duration.ofMillis(1_900), Duration.ofSeconds(5))
    }

    @Test
    fun `a pending event is served at once even when the adapter asks to wait`() {
        val request = seedRequest(adapterCollection, orgId)

        val elapsed =
            timed {
                mockMvc
                    .perform(pollEvents("wait=2"))
                    .andExpect(status().isOk)
                    .andExpect(jsonPath("$[0].corrId").value(request.corrId))
            }

        assertThat(elapsed).isLessThan(Duration.ofSeconds(1))
    }

    @Test
    fun `a held long poll is woken by the change stream as soon as the event is stored`() {
        await.atMost(Duration.ofSeconds(10)).until { eventArrivals.watched }

        lateinit var request: RequestFintEvent
        val seeder =
            thread {
                Thread.sleep(300)
                request = seedRequest(adapterCollection, orgId)
            }

        val result =
            timedResult {
                mockMvc
                    .perform(pollEvents("wait=2"))
                    .andExpect(status().isOk)
                    .andExpect(header().string(PreferHeader.APPLIED, "wait=2"))
                    .andReturn()
            }
        seeder.join()

        assertThat(result.second).isLessThan(Duration.ofMillis(1_500))
        assertThat(result.first.response.contentAsString).contains(request.corrId)
    }

    @Test
    fun `an event for another org wakes a held long poll but does not end it`() {
        await.atMost(Duration.ofSeconds(10)).until { eventArrivals.watched }

        val otherOrg = "other.fintlabs.no"
        val otherCollection = OrgId.from(otherOrg).toEventCollectionName()
        val versionBefore = eventArrivals.version
        val seeder =
            thread {
                Thread.sleep(300)
                seedRequest(otherCollection, otherOrg)
            }

        val elapsed =
            timed {
                mockMvc
                    .perform(pollEvents("wait=2"))
                    .andExpect(status().isOk)
                    .andExpect(content().json("[]"))
            }
        seeder.join()
        mongoTemplate.dropCollection(otherCollection)

        assertThat(eventArrivals.version).isGreaterThan(versionBefore)
        assertThat(elapsed).isBetween(Duration.ofMillis(1_900), Duration.ofSeconds(5))
    }

    private fun answer(
        response: ResponseFintEvent,
        principal: CorePrincipal = mockPrincipal,
    ): ResultActions =
        mockMvc.perform(
            post("/provider/event")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(response))
                .with(authentication(principal)),
        )

    private fun pollEvents(prefer: String?) =
        get("/provider/event/$domainName")
            .with(authentication(mockPrincipal))
            .let { builder -> if (prefer == null) builder else builder.header(PreferHeader.NAME, prefer) }

    private fun timed(block: () -> Unit): Duration = timedResult(block).second

    private fun <T> timedResult(block: () -> T): Pair<T, Duration> {
        val start = System.nanoTime()
        val result = block()
        return result to Duration.ofNanos(System.nanoTime() - start)
    }

    private fun seedRequest(
        collectionName: String,
        requestOrgId: String,
        ttlMillis: Long = 900_000,
        domain: String = domainName,
        pkg: String = packageName,
        operation: OperationType = OperationType.CREATE,
    ): RequestFintEvent {
        val request =
            RequestFintEvent().apply {
                corrId = UUID.randomUUID().toString()
                orgId = requestOrgId
                domainName = domain
                packageName = pkg
                resourceName = this@EventFlowIT.resourceName
                operationType = operation
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
