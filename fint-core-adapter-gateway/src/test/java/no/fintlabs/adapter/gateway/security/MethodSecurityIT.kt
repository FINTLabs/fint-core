package no.fintlabs.adapter.gateway.security

import no.fintlabs.adapter.gateway.TestcontainersConfiguration
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.event.EventController
import no.fintlabs.adapter.gateway.event.request.RequestEventService
import no.fintlabs.adapter.gateway.event.response.ResponseEventService
import no.fintlabs.adapter.gateway.exception.ExceptionController
import no.fintlabs.adapter.gateway.heartbeat.HeartbeatController
import no.fintlabs.adapter.gateway.heartbeat.HeartbeatService
import no.fintlabs.adapter.gateway.register.CapabilityKey
import no.fintlabs.adapter.gateway.register.ContractLookup
import no.fintlabs.adapter.gateway.register.ContractService
import no.fintlabs.adapter.gateway.register.RegistrationController
import no.fintlabs.adapter.gateway.register.RegistrationService
import no.fintlabs.adapter.gateway.sync.SyncController
import no.fintlabs.adapter.gateway.sync.SyncPageService
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.AdapterHeartbeat
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.adapter.models.sync.DeleteSyncPage
import no.fintlabs.adapter.models.sync.DeltaSyncPage
import no.fintlabs.adapter.models.sync.FullSyncPage
import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.novari.core.shared.event.EventScope
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.util.UUID

/**
 * Exercises the real `@PreAuthorize` expressions wired through [AdapterAuthorization], with the
 * downstream services mocked out. [SecurityConfigurationIT] covers the filter chain in
 * isolation; this covers the method-security layer sitting behind it.
 */
@SpringBootTest(
    classes = [MethodSecurityIT.TestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
)
@ActiveProfiles(MethodSecurityIT.PROFILE)
@Import(TestcontainersConfiguration::class)
class MethodSecurityIT {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: JsonMapper

    @MockitoBean
    private lateinit var syncPageService: SyncPageService

    @MockitoBean
    private lateinit var heartbeatService: HeartbeatService

    @MockitoBean
    private lateinit var registrationService: RegistrationService

    @MockitoBean
    private lateinit var requestEventService: RequestEventService

    @MockitoBean
    private lateinit var responseEventService: ResponseEventService

    @MockitoBean
    private lateinit var contractService: ContractService

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()

        givenContract(CapabilityKey("utdanning", "elev", "elev"))
    }

    private fun givenContract(vararg capabilities: CapabilityKey) {
        whenever(contractService.lookup(any(), any()))
            .thenReturn(ContractLookup.Found(capabilities.toSet()))
    }

    @Test
    fun `sync denies page for org outside JWT assets with ProblemDetail body`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("other.org.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)
            .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
            .andExpect(jsonPath("$.detail").value(DenialReason.ORG_NOT_IN_ASSETS.detail))
            .andExpect(jsonPath("$.type").value("https://fintlabs.no/problem/org-not-in-assets"))

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync returns 400 for unparseable body before payload authorization runs`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{not-json")
                    .with(authentication(adapter())),
            ).andExpect(status().isBadRequest)

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync denies adapter without component role with role message`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("fintlabs.no")))
                    .with(authentication(adapter(roles = emptyList()))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.MISSING_COMPONENT_ROLE.detail))

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync denies a page with no metadata instead of throwing`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{}")
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `delta sync denies page for org outside JWT assets`() {
        val syncPage =
            DeltaSyncPage().apply {
                metadata = syncPageMetadata("other.org.no")
                resources = emptyList()
            }

        mockMvc
            .perform(
                patch("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `delete sync denies page for org outside JWT assets`() {
        val syncPage =
            DeleteSyncPage().apply {
                metadata = syncPageMetadata("other.org.no")
                resources = emptyList()
            }

        mockMvc
            .perform(
                delete("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(syncPage))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync allows page for org in JWT assets`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isCreated)

        verify(syncPageService).doSync(any<SyncPage>(), any<ResourceCoordinate>())
    }

    @Test
    fun `sync denies a page for an org this gateway does not serve`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("vtfk.no")))
                    .with(authentication(adapter(assets = "fintlabs.no,vtfk.no"))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.ORG_NOT_SERVED.detail))

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync denies a resource the contract does not cover`() {
        givenContract(CapabilityKey("utdanning", "elev", "skoleressurs"))

        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.RESOURCE_NOT_IN_CONTRACT.detail))

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync denies an adapter with no contract for the org`() {
        whenever(contractService.lookup(any(), any())).thenReturn(ContractLookup.Absent)

        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.NO_CONTRACT.detail))

        verifyNoInteractions(syncPageService)
    }

    @Test
    fun `sync builds the storage coordinate from the page org, not the JWT's first asset`() {
        mockMvc
            .perform(
                post("/provider/utdanning/elev/elev")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(fullSyncPage("test.fintlabs.no")))
                    .with(authentication(adapter(assets = "fintlabs.no,test.fintlabs.no"))),
            ).andExpect(status().isCreated)

        val coords = argumentCaptor<ResourceCoordinate>()
        verify(syncPageService).doSync(any<SyncPage>(), coords.capture())
        assertThat(coords.firstValue.orgId).isEqualTo("test.fintlabs.no")
    }

    @Test
    fun `heartbeat denies org outside JWT assets`() {
        mockMvc
            .perform(
                post("/provider/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat("other.org.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(heartbeatService)
    }

    @Test
    fun `heartbeat allows org in JWT assets`() {
        mockMvc
            .perform(
                post("/provider/heartbeat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(heartbeat("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isOk)

        verify(heartbeatService).beat(any<AdapterHeartbeat>())
    }

    @Test
    fun `register denies contract for org outside JWT assets`() {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract(orgId = "other.org.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(registrationService)
    }

    @Test
    fun `register denies contract with username not matching JWT`() {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract(username = "someone@else.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(registrationService)
    }

    @Test
    fun `register allows contract matching JWT org and username`() {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract()))
                    .with(authentication(adapter())),
            ).andExpect(status().isOk)

        verify(registrationService).register(any<AdapterContract>())
    }

    @Test
    fun `register allows a second org for the same adapter when both are JWT assets`() {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract(orgId = "test.fintlabs.no")))
                    .with(authentication(adapter(assets = "fintlabs.no,test.fintlabs.no"))),
            ).andExpect(status().isOk)

        verify(registrationService).register(any<AdapterContract>())
    }

    @Test
    fun `event response denies org outside JWT assets`() {
        mockMvc
            .perform(
                post("/provider/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(responseEvent("other.org.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)

        verifyNoInteractions(responseEventService)
    }

    @Test
    fun `event response allows org in JWT assets`() {
        mockMvc
            .perform(
                post("/provider/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(responseEvent("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isOk)

        verify(responseEventService).handleEvent(any<ResponseFintEvent>())
    }

    @Test
    fun `event response denies an adapter with no contract for the org`() {
        whenever(contractService.lookup(any(), any())).thenReturn(ContractLookup.Absent)

        mockMvc
            .perform(
                post("/provider/event")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(responseEvent("fintlabs.no")))
                    .with(authentication(adapter())),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.NO_CONTRACT.detail))

        verifyNoInteractions(responseEventService)
    }

    @Test
    fun `event fetch denies a package the adapter has no role for`() {
        mockMvc
            .perform(get("/provider/event/utdanning/vurdering").with(authentication(adapter())))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.MISSING_COMPONENT_ROLE.detail))

        verifyNoInteractions(requestEventService)
    }

    @Test
    fun `event fetch denies an adapter with no contract and says to register one`() {
        whenever(contractService.lookup(any(), any())).thenReturn(ContractLookup.Absent)

        mockMvc
            .perform(get("/provider/event/utdanning/elev").with(authentication(adapter())))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(DenialReason.NO_REGISTERED_CONTRACT.detail))

        verifyNoInteractions(requestEventService)
    }

    @Test
    fun `event fetch covers only the orgs the adapter has a contract for`() {
        whenever(contractService.lookup(USERNAME, "fintlabs.no")).thenReturn(ContractLookup.Found(emptySet()))
        whenever(contractService.lookup(USERNAME, "test.fintlabs.no")).thenReturn(ContractLookup.Absent)

        mockMvc
            .perform(get("/provider/event/utdanning/elev").with(authentication(adapter(assets = "fintlabs.no,test.fintlabs.no"))))
            .andExpect(status().isOk)

        verify(requestEventService).getEvents(listOf(OrgId.from("fintlabs.no")), listOf(EventScope("utdanning", "elev")), 0)
    }

    @Test
    fun `event fetch for a domain covers only the packages the adapter has a role for`() {
        val adapter =
            adapter(
                roles = listOf("FINT_Adapter_utdanning_elev", "FINT_Adapter_utdanning_vurdering", "FINT_Adapter_administrasjon_personal"),
            )

        mockMvc
            .perform(get("/provider/event/utdanning").param("size", "5").with(authentication(adapter)))
            .andExpect(status().isOk)

        verify(requestEventService).getEvents(
            listOf(OrgId.from("fintlabs.no")),
            listOf(EventScope("utdanning", "elev"), EventScope("utdanning", "vurdering")),
            5,
        )
    }

    private fun adapter(
        assets: String = "fintlabs.no",
        roles: List<String> = listOf("FINT_Adapter_utdanning_elev"),
    ): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", USERNAME)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", roles)
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    private fun fullSyncPage(orgId: String): FullSyncPage =
        FullSyncPage().apply {
            metadata = syncPageMetadata(orgId)
            resources = emptyList()
        }

    private fun syncPageMetadata(orgId: String): SyncPageMetadata =
        SyncPageMetadata
            .builder()
            .adapterId("adapter-id")
            .orgId(orgId)
            .corrId(UUID.randomUUID().toString())
            .totalSize(0)
            .page(0)
            .pageSize(0)
            .totalPages(1)
            .uriRef("/utdanning/elev/elev")
            .time(System.currentTimeMillis())
            .build()

    private fun heartbeat(orgId: String): AdapterHeartbeat =
        AdapterHeartbeat().apply {
            this.adapterId = "adapter-id"
            this.orgId = orgId
            this.username = USERNAME
            this.time = System.currentTimeMillis()
        }

    private fun contract(
        orgId: String = "fintlabs.no",
        username: String = USERNAME,
    ): AdapterContract =
        AdapterContract().apply {
            this.adapterId = "adapter-id"
            this.orgId = orgId
            this.username = username
            this.heartbeatIntervalInMinutes = 5
            this.capabilities = emptySet()
        }

    private fun responseEvent(orgId: String): ResponseFintEvent =
        ResponseFintEvent
            .builder()
            .corrId(UUID.randomUUID().toString())
            .orgId(orgId)
            .adapterId("adapter-id")
            .build()

    @Configuration
    @Profile(PROFILE)
    @EnableAutoConfiguration(exclude = [KafkaAutoConfiguration::class])
    @EnableConfigurationProperties(ProviderProperties::class)
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        AdapterAuthorization::class,
        EventAuthorization::class,
        SyncController::class,
        HeartbeatController::class,
        RegistrationController::class,
        EventController::class,
        ExceptionController::class,
    )
    class TestApp

    companion object {
        const val PROFILE = "method-security-test"
        private const val USERNAME = "test@adapter.fintlabs.no"
    }
}
