package no.fintlabs.client.security

import no.fintlabs.client.resource.ResourceExceptionHandler
import no.novari.resource.server.authentication.CorePrincipal
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [SecurityConfigurationIT.SliceApplication::class],
)
class SecurityConfigurationIT {
    @Autowired
    private lateinit var context: WebApplicationContext

    private lateinit var mockMvc: MockMvc

    @BeforeEach
    fun setup() {
        mockMvc =
            MockMvcBuilders
                .webAppContextSetup(context)
                .apply<DefaultMockMvcBuilder>(springSecurity())
                .build()
    }

    @Test
    fun `health probe is answered by the actuator without authentication`() {
        mockMvc
            .perform(get("/actuator/health"))
            .andExpect(jsonPath("$.status").exists())
    }

    @Test
    fun `prometheus scrape is answered with metrics without authentication`() {
        mockMvc
            .perform(get("/actuator/prometheus"))
            .andExpect(status().isOk)
            .andExpect(content().string(containsString("jvm_")))
    }

    @Test
    fun `other actuator endpoints still require a client token`() {
        mockMvc
            .perform(get("/actuator/env"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `request without a token returns 401 with a problem detail body`() {
        mockMvc
            .perform(resourceRequest("fintlabs.no"))
            .andExpect(status().isUnauthorized)
            .andExpect(header().string("Content-Type", containsString(MediaType.APPLICATION_PROBLEM_JSON_VALUE)))
    }

    @Test
    fun `adapter-typed token is denied on a client endpoint`() {
        mockMvc
            .perform(resourceRequest("fintlabs.no").with(authentication(client(cn = "test@adapter.fintlabs.no"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `token without the fint-client scope is denied`() {
        mockMvc
            .perform(resourceRequest("fintlabs.no").with(authentication(client(scope = "some-other-scope"))))
            .andExpect(status().isForbidden)
    }

    @Test
    fun `token whose assets do not include the requested org is denied`() {
        mockMvc
            .perform(resourceRequest("othercounty.no").with(authentication(client(assets = "fintlabs.no"))))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("organisation")))
    }

    @Test
    fun `request with a valid token but no org-id header is a 400 explaining what's missing`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering/elevfravar")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_vurdering")))),
            ).andExpect(status().isBadRequest)
            .andExpect(content().string(containsString("x-org-id")))
    }

    @Test
    fun `token without the matching component role is denied`() {
        mockMvc
            .perform(
                resourceRequest("fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_administrasjon_personal")))),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("component")))
    }

    @Test
    fun `valid token with matching org and component is allowed`() {
        mockMvc
            .perform(
                resourceRequest("fintlabs.no")
                    .with(authentication(client(roles = listOf("FINT_Client_utdanning_vurdering")))),
            ).andExpect(status().isOk)
    }

    @Test
    fun `endpoints overview enforces the same component check`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering")
                    .with(authentication(client(roles = listOf("FINT_Client_administrasjon_personal")))),
            ).andExpect(status().isForbidden)
    }

    private fun resourceRequest(orgId: String) = get("/utdanning/vurdering/elevfravar").header("x-org-id", orgId)

    private fun client(
        cn: String = "test@client.fintlabs.no",
        scope: String = "fint-client",
        assets: String = "fintlabs.no",
        roles: List<String> = emptyList(),
    ): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", cn)
                .claim("fintAssetIDs", assets)
                .claim("scope", listOf(scope))
                .claim("Roles", roles)
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        ResourceExceptionHandler::class,
        Endpoints::class,
    )
    class SliceApplication

    @RestController
    class Endpoints {
        @GetMapping("/{domainName}/{packageName}/{resourceName}")
        fun resource(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @PathVariable resourceName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): String = "$domainName/$packageName/$resourceName"

        @GetMapping("/{domainName}/{packageName}")
        fun endpoints(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
        ): String = "$domainName/$packageName"
    }
}
