package no.fintlabs.provider

import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.novari.resource.server.authentication.CorePrincipal
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.context.WebApplicationContext
import tools.jackson.databind.json.JsonMapper
import java.time.Instant

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
abstract class GatewayIntegrationTestBase {
    @Autowired
    protected lateinit var context: WebApplicationContext

    @Autowired
    protected lateinit var objectMapper: JsonMapper

    protected lateinit var mockMvc: MockMvc
    protected lateinit var mockPrincipal: CorePrincipal

    protected val domainName = "utdanning"
    protected val packageName = "elev"
    protected val resourceName = "elev"
    protected val orgId = "test.org.no"
    protected val username = "test@adapter.$orgId"
    protected val adapterId = "https://test.com/$orgId/$domainName/$packageName"

    @BeforeEach
    fun baseSetup() {
        val jwt =
            Jwt
                .withTokenValue("mock-token-value")
                .header("alg", "none")
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .claim("cn", username)
                .claim("fintAssetIDs", orgId)
                .claim("scope", listOf("fint-adapter"))
                .claim("Roles", listOf("FINT_Adapter_${domainName}_$packageName"))
                .build()

        mockPrincipal = CorePrincipal(jwt, listOf(SimpleGrantedAuthority("ROLE_ADAPTER")))

        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    protected fun adapterContract(): AdapterContract {
        val capability =
            AdapterCapability().apply {
                this.domainName = this@GatewayIntegrationTestBase.domainName
                this.packageName = this@GatewayIntegrationTestBase.packageName
                this.resourceName = this@GatewayIntegrationTestBase.resourceName
                this.fullSyncIntervalInDays = 1
                this.deltaSyncInterval = AdapterCapability.DeltaSyncInterval.IMMEDIATE
            }

        return AdapterContract().apply {
            this.adapterId = this@GatewayIntegrationTestBase.adapterId
            this.orgId = this@GatewayIntegrationTestBase.orgId
            this.username = this@GatewayIntegrationTestBase.username
            this.heartbeatIntervalInMinutes = 5
            this.capabilities = setOf(capability)
        }
    }

    protected fun registerAdapter() {
        mockMvc
            .perform(
                post("/register")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsBytes(adapterContract()))
                    .with(authentication(mockPrincipal)),
            ).andExpect(status().isOk)
    }
}
