package no.fintlabs.consumer.security

import no.novari.resource.server.authentication.CorePrincipal
import no.novari.resource.server.converter.CorePrincipalConverter
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka
@ActiveProfiles("utdanning-elev")
@TestPropertySource(
    properties = [
        "fint.security.enabled=true",
        "fint.security.opa.enabled=false",
        // dummy jwk-set-uri: gives a lazy JwtDecoder (no startup JWKS fetch) that is never invoked
        // because the tests inject the authentication directly.
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=https://localhost/jwks",
    ],
)
class SecurityIT {
    @Autowired
    private lateinit var mockMvc: MockMvc

    private val path = "/utdanning/elev/elev"

    @Test
    fun `unauthenticated request returns 401`() {
        mockMvc.get(path).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `client principal with component access returns 200`() {
        mockMvc
            .get(path) { with(authentication(principal(roles = listOf("FINT_Client_utdanning_elev")))) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `client principal without component access returns 403`() {
        mockMvc
            .get(path) { with(authentication(principal(roles = listOf("FINT_Client_utdanning_basisgruppe")))) }
            .andExpect { status { isForbidden() } }
    }

    private fun principal(roles: List<String>): CorePrincipal {
        val jwt =
            Jwt
                .withTokenValue("test-token")
                .header("alg", "none")
                .claim("cn", "test@client.fintlabs.no")
                .claim("scope", listOf("fint-client"))
                .claim("Roles", roles)
                .claim("fintAssetIDs", "fintlabs-no")
                .build()
        return CorePrincipalConverter().convert(jwt) as CorePrincipal
    }
}
