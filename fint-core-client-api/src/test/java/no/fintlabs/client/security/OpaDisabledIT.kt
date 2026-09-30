package no.fintlabs.client.security

import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.JacksonConfiguration
import no.fintlabs.client.security.opa.OpaClient
import no.fintlabs.client.security.opa.OpaProperties
import no.fintlabs.client.security.opa.OpaService
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.felles.kompleksedatatyper.Personnavn
import no.novari.resource.server.authentication.CorePrincipal
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
 * Regression test for a real bug found while documenting this class: while OPA is disabled,
 * [no.fintlabs.client.security.opa.OpaService] returns an allow decision with empty field and
 * relation sets, since there is nothing else for it to say. Without the `enabled` check in
 * [OpaFieldAdvice.supports], those empty sets would prune every response down to almost nothing
 * any time OPA is off, which is the opposite of what "off" is supposed to mean.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.MOCK,
    classes = [OpaDisabledIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class OpaDisabledIT {
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
    fun `response keeps every field and relation when OPA is disabled`() {
        mockMvc
            .perform(
                get("/utdanning/elev/person")
                    .header("x-org-id", "fintlabs.no")
                    .with(authentication(client())),
            ).andExpect(status().isOk)
            .andExpect(jsonPath("$.fodselsnummer").exists())
            .andExpect(jsonPath("$.navn").exists())
            .andExpect(jsonPath("$._links.self").exists())
            .andExpect(jsonPath("$._links.elev").exists())
    }

    private fun client(): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("token")
                .header("alg", "none")
                .claim("cn", "test@client.fintlabs.no")
                .claim("fintAssetIDs", "fintlabs.no")
                .claim("scope", listOf("fint-client"))
                .claim("Roles", listOf("FINT_Client_utdanning_elev"))
                .build()
        return CorePrincipal(jwt, emptyList())
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(OpaProperties::class, ConsumerConfiguration::class)
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        OpaFieldAdvice::class,
        OpaClient::class,
        OpaService::class,
        JacksonConfiguration::class,
        Endpoints::class,
    )
    class SliceApplication

    @RestController
    class Endpoints {
        @GetMapping("/{domainName}/{packageName}/person")
        fun person(
            @PathVariable domainName: String,
            @PathVariable packageName: String,
            @RequestHeader("x-org-id") orgId: String,
        ): Person =
            Person(
                fodselsnummer = Identifikator(identifikatorverdi = "01010112345"),
                navn = Personnavn(fornavn = "Kari", etternavn = "Nordmann"),
            ).apply {
                addLink("elev", Link(idField = "systemid", idValue = "1"))
            }
    }
}
