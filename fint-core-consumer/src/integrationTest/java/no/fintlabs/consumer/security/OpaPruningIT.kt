package no.fintlabs.consumer.security

import no.fintlabs.cache.CacheService
import no.fintlabs.consumer.security.opa.OpaClient
import no.fintlabs.consumer.security.opa.OpaResponse
import no.fintlabs.consumer.security.opa.OpaResult
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.Link
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.converter.CorePrincipalConverter
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * End-to-end OPA pruning: with OPA enabled and a stubbed decision allowing only `systemid` /
 * `elevforhold`, a cached resource fetched over HTTP must come back with only those — the rest
 * removed. Proves the full wiring (CoreAccessService stashes the decision -> OpaFieldAdvice prunes).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka
@ActiveProfiles("utdanning-elev")
@TestPropertySource(
    properties = [
        "fint.security.enabled=true",
        "fint.security.opa.enabled=true",
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://localhost/jwks",
    ],
)
class OpaPruningIT {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var cacheService: CacheService

    @MockitoBean
    private lateinit var opaClient: OpaClient

    @BeforeEach
    fun stubOpaDecision() {
        given(opaClient.getDecision(anyNonNull()))
            .willReturn(
                OpaResponse(OpaResult(allow = true, fields = setOf("systemid"), relations = setOf("elevforhold"))),
            )
    }

    @Test
    fun `response is pruned to the fields and relations OPA allowed`() {
        cacheService.getCache("utdanning_elev_elev").put("123", elev(), System.currentTimeMillis())

        mockMvc
            .get("/utdanning/elev/elev/systemid/123") { with(authentication(clientPrincipal())) }
            .andExpect {
                status { isOk() }
                jsonPath("$.systemId") { exists() }
                jsonPath("$.brukernavn") { doesNotExist() }
                jsonPath("$.feidenavn") { doesNotExist() }
                jsonPath("$._links.elevforhold") { exists() }
                jsonPath("$._links.basisgruppemedlemskap") { doesNotExist() }
            }
    }

    private fun elev(): ElevResource =
        ElevResource().apply {
            systemId = identifikator("123")
            brukernavn = identifikator("user")
            feidenavn = identifikator("feide")
            addLink("elevforhold", Link.with("link/elevforhold/1"))
            addLink("basisgruppemedlemskap", Link.with("link/basisgruppemedlemskap/1"))
        }

    private fun identifikator(value: String): Identifikator =
        object : Identifikator() {
            init {
                identifikatorverdi = value
            }
        }

    private fun clientPrincipal(): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .claim("cn", "test@client.fintlabs.no")
                .claim("scope", listOf("fint-client"))
                .claim("Roles", listOf("FINT_Client_utdanning_elev"))
                .claim("fintAssetIDs", "fintlabs-no")
                .build()
        return CorePrincipalConverter().convert(jwt) as CorePrincipal
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> anyNonNull(): T = Mockito.any<T>() as T
}
