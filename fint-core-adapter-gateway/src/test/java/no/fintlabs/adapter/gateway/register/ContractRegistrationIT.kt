package no.fintlabs.adapter.gateway.register

import no.fintlabs.adapter.gateway.TestcontainersConfiguration
import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.adapter.models.EventCapability
import no.fintlabs.adapter.operation.OperationType
import no.novari.core.shared.event.EventCapabilityStore
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.resourceRefOf
import no.novari.resource.server.authentication.CorePrincipal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class ContractRegistrationIT {
    @Autowired
    private lateinit var context: WebApplicationContext

    @Autowired
    private lateinit var objectMapper: JsonMapper

    @Autowired
    private lateinit var contractJpaRepository: ContractJpaRepository

    @Autowired
    private lateinit var eventCapabilityStore: EventCapabilityStore

    private lateinit var mockMvc: MockMvc
    private lateinit var principal: CorePrincipal

    private val orgId = "test.fintlabs.no"
    private val domainName = "utdanning"
    private val packageName = "elev"
    private val username = "test@adapter.$orgId"
    private val adapterId = "https://test.com/$orgId/$domainName/$packageName"

    @BeforeEach
    fun setup() {
        contractJpaRepository.deleteAll()

        val jwt =
            Jwt
                .withTokenValue("mock-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("cn", username)
                .claim("fintAssetIDs", orgId)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", listOf("FINT_Adapter_${domainName}_$packageName"))
                .build()
        principal = CorePrincipal(jwt, listOf(SimpleGrantedAuthority("ROLE_ADAPTER")))

        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @Test
    fun `register persists contract and capabilities`() {
        postRegister(contract(heartbeat = 5, capabilities = setOf(capability(resource = "elev"))))

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!

        assertThat(stored.userName).isEqualTo(username)
        assertThat(stored.adapterId).isEqualTo(adapterId)
        assertThat(stored.orgId).isEqualTo(orgId)
        assertThat(stored.heartbeatIntervalInMinutes).isEqualTo(5)
        assertThat(stored.capabilityEntityset)
            .singleElement()
            .satisfies({
                assertThat(it.domainName).isEqualTo(domainName)
                assertThat(it.pkgName).isEqualTo(packageName)
                assertThat(it.resourceName).isEqualTo("elev")
                assertThat(it.fullSyncIntervalInDays).isEqualTo(1)
                assertThat(it.deltaSyncInterval).isEqualTo("IMMEDIATE")
            })
    }

    @Test
    fun `re-registering with a different capability set replaces the old capabilities`() {
        postRegister(contract(capabilities = setOf(capability(resource = "elev"))))
        postRegister(
            contract(
                capabilities =
                    setOf(
                        capability(pkg = "vurdering", resource = "elevfravar"),
                    ),
            ),
        )

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!

        assertThat(stored.capabilityEntityset.map { it.resourceName })
            .containsExactly("elevfravar")
    }

    @Test
    fun `re-registering updates scalar contract fields`() {
        postRegister(contract(heartbeat = 5))
        postRegister(contract(heartbeat = 2))

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!

        assertThat(stored.heartbeatIntervalInMinutes).isEqualTo(2)
        assertThat(contractJpaRepository.count()).isEqualTo(1)
    }

    @Test
    fun `re-registering with no capabilities clears the capability set`() {
        postRegister(contract(capabilities = setOf(capability(resource = "elev"))))
        postRegister(contract(capabilities = emptySet()))

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!

        assertThat(stored.capabilityEntityset).isEmpty()
    }

    @Test
    fun `registering a second org for the same adapter keeps both contracts`() {
        val subOrg = "sub.$orgId"
        val multiOrgPrincipal = principal(assets = "$orgId,$subOrg")

        postRegister(contract(capabilities = setOf(capability(resource = "elev"))), multiOrgPrincipal)
        postRegister(
            contract(orgId = subOrg, capabilities = setOf(capability(pkg = "vurdering", resource = "elevfravar"))),
            multiOrgPrincipal,
        )

        assertThat(contractJpaRepository.count()).isEqualTo(2)
        assertThat(
            contractJpaRepository
                .findByUserNameAndOrgId(username, orgId)!!
                .capabilityEntityset
                .map { it.resourceName },
        ).containsExactly("elev")
        assertThat(
            contractJpaRepository
                .findByUserNameAndOrgId(username, subOrg)!!
                .capabilityEntityset
                .map { it.resourceName },
        ).containsExactly("elevfravar")
    }

    @Test
    fun `register persists the event capabilities as one row per resource and operation`() {
        postRegister(
            contract(
                eventCapabilities =
                    setOf(
                        eventCapability("vurdering", "elevfravar", OperationType.READ),
                        eventCapability("vurdering", "aktivitetsfravar", OperationType.CREATE, OperationType.UPDATE),
                    ),
            ),
        )

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!
        assertThat(stored.eventCapabilityEntityset.map { "${it.resourceName}:${it.operation}" })
            .containsExactlyInAnyOrder("elevfravar:READ", "aktivitetsfravar:CREATE", "aktivitetsfravar:UPDATE")
    }

    @Test
    fun `re-registering replaces the event capabilities`() {
        postRegister(contract(eventCapabilities = setOf(eventCapability("vurdering", "elevfravar", OperationType.READ))))
        postRegister(contract(eventCapabilities = setOf(eventCapability("vurdering", "aktivitetsfravar", OperationType.READ))))

        val stored = contractJpaRepository.findByUserNameAndOrgId(username, orgId)!!
        assertThat(stored.eventCapabilityEntityset.map { it.resourceName }).containsExactly("aktivitetsfravar")
    }

    @Test
    fun `register publishes what the org's adapters can read live`() {
        postRegister(contract(eventCapabilities = setOf(eventCapability("vurdering", "elevfravar", OperationType.READ))))

        val published = eventCapabilityStore.find(OrgId.from(orgId))!!
        assertThat(published.canRead(resourceRefOf("utdanning", "vurdering", "elevfravar"))).isTrue()
        assertThat(published.canRead(resourceRefOf("utdanning", "elev", "elev"))).isFalse()
    }

    @Test
    fun `a contract that breaks a rule is refused before anything is stored, naming the field`() {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsBytes(
                            contract(
                                heartbeat = 10,
                                eventCapabilities = setOf(eventCapability("vurdering", "finnesikke", OperationType.READ)),
                            ),
                        ),
                    ).with(authentication(principal)),
            ).andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.detail").value("The request body is not valid"))
            .andExpect(jsonPath("$.errors[?(@.field == 'heartbeatIntervalInMinutes')]").exists())
            .andExpect(
                jsonPath(
                    "$.errors[?(@.field == 'eventCapabilities[]')].message",
                ).value("/utdanning/vurdering/finnesikke is not a resource in the FINT model"),
            )

        assertThat(contractJpaRepository.count()).isEqualTo(0)
    }

    private fun postRegister(
        contract: AdapterContract,
        authenticatedAs: CorePrincipal = principal,
    ) {
        mockMvc
            .perform(
                post("/provider/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(contract))
                    .with(authentication(authenticatedAs)),
            ).andExpect(status().isOk)
    }

    private fun principal(assets: String): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("mock-token")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("cn", username)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", listOf("FINT_Adapter_${domainName}_$packageName"))
                .build()
        return CorePrincipal(jwt, listOf(SimpleGrantedAuthority("ROLE_ADAPTER")))
    }

    private fun contract(
        orgId: String = this.orgId,
        heartbeat: Int = 5,
        capabilities: Set<AdapterCapability> = setOf(capability(resource = "elev")),
        eventCapabilities: Set<EventCapability> = emptySet(),
    ): AdapterContract =
        AdapterContract().apply {
            this.adapterId = this@ContractRegistrationIT.adapterId
            this.orgId = orgId
            this.username = this@ContractRegistrationIT.username
            this.heartbeatIntervalInMinutes = heartbeat
            this.capabilities = capabilities
            this.eventCapabilities = eventCapabilities
        }

    private fun eventCapability(
        pkg: String,
        resource: String,
        vararg operations: OperationType,
    ): EventCapability =
        EventCapability().apply {
            this.domainName = this@ContractRegistrationIT.domainName
            this.packageName = pkg
            this.resourceName = resource
            this.operations = operations.toSet()
        }

    private fun capability(
        domain: String = domainName,
        pkg: String = packageName,
        resource: String,
    ): AdapterCapability =
        AdapterCapability().apply {
            this.domainName = domain
            this.packageName = pkg
            this.resourceName = resource
            this.fullSyncIntervalInDays = 1
            this.deltaSyncInterval = AdapterCapability.DeltaSyncInterval.IMMEDIATE
        }
}
