package no.fintlabs.client.security

import no.fintlabs.client.security.opa.OpaClient
import no.fintlabs.client.security.opa.OpaProperties
import no.fintlabs.client.security.opa.OpaService
import no.novari.resource.server.authentication.CorePrincipal
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.DefaultMockMvcBuilder
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.context.WebApplicationContext

/**
 * With OPA enabled but unreachable, a request that would otherwise pass every check must still
 * be denied. [OpaClient] never throws past itself, so this proves the denial end to end rather
 * than just at the client.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [OpaUnreachableIT.SliceApplication::class],
    properties = [
        "fint.security.opa.enabled=true",
        "fint.security.opa.url=http://localhost:1",
    ],
)
class OpaUnreachableIT {
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
    fun `an otherwise-valid request is denied when OPA cannot be reached, and the body says so`() {
        mockMvc
            .perform(
                get("/utdanning/vurdering/elevfravar")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client())),
            ).andExpect(status().isForbidden)
            .andExpect(jsonPath("$.detail").value(containsString("unavailable")))
    }

    private fun client(): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", "test@client.fintlabs.no")
                .claim("fintAssetIDs", "fintlabs.no")
                .claim("scope", listOf("fint-client"))
                .claim("Roles", listOf("FINT_Client_utdanning_vurdering"))
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(OpaProperties::class)
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        OpaClient::class,
        OpaService::class,
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
    }
}
