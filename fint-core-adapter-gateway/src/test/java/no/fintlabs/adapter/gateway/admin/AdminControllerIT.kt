package no.fintlabs.adapter.gateway.admin

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.TestcontainersConfiguration
import no.fintlabs.adapter.gateway.config.FintResourceRefConverter
import no.fintlabs.adapter.gateway.config.OrgIdConverter
import no.fintlabs.adapter.gateway.relation.RelationEdgeDrift
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuild
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuildRunningException
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuilder
import no.fintlabs.adapter.gateway.security.SecurityConfiguration
import no.fintlabs.adapter.gateway.security.SecurityProblemDetailHandler
import no.novari.core.shared.model.ResourceCoordinate
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The admin endpoints on a real server, so a request goes through the whole filter chain and any
 * forward to the error page, the way it does in the cluster. Two fixed tokens stand for a FINT
 * client and a FINT adapter from novari.no.
 */
@SpringBootTest(
    classes = [AdminControllerIT.TestApp::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
)
@ActiveProfiles(AdminControllerIT.PROFILE)
@Import(TestcontainersConfiguration::class)
class AdminControllerIT {
    @Autowired
    private lateinit var rebuilder: RelationEdgeRebuilder

    @Value("\${local.server.port}")
    private var port: Int = 0

    @BeforeEach
    fun resetRebuilder() {
        clearMocks(rebuilder)
        every { rebuilder.rebuild(any()) } returns RelationEdgeRebuild(resourcesRead = 1, edgesWritten = 1, edgesRemoved = 0)
        every { rebuilder.drift(any()) } returns RelationEdgeDrift.NONE
    }

    @Test
    fun `a rebuild names the org and resource it was asked for`() {
        val response = post("/admin/relation-edges/rebuild?orgId=ude-oslo-kommune-no&resource=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(200, response.statusCode())
        verify { rebuilder.rebuild(ResourceCoordinate("ude.oslo.kommune.no", "utdanning", "elev", "person")) }
    }

    @Test
    fun `an iso resource is rebuilt under the identity the model gives it`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&resource=felles/kodeverk/iso/landkode", NOVARI_CLIENT)

        assertEquals(200, response.statusCode())
        verify { rebuilder.rebuild(ResourceCoordinate("fintlabs.no", "felles", "kodeverk", "landkode")) }
    }

    @Test
    fun `a rebuild for a resource the model does not serve is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&resource=utdanning/elev/nothing", NOVARI_CLIENT)

        assertEquals(400, response.statusCode(), response.body())
        assertTrue("utdanning/elev/nothing" in response.body(), response.body())
    }

    @Test
    fun `a rebuild with a blank org id is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=%20&resource=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a rebuild without an org id is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?resource=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a rebuild without a resource is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no", NOVARI_CLIENT)

        assertEquals(400, response.statusCode(), response.body())
        assertTrue("resource" in response.body(), response.body())
    }

    @Test
    fun `a drift check for a resource the model does not serve is a bad request`() {
        val response = get("/admin/relation-edges/drift?orgId=fintlabs.no&resource=utdanning/elev/nothing", NOVARI_CLIENT)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a rebuild that is already running answers conflict`() {
        every { rebuilder.rebuild(any()) } throws
            RelationEdgeRebuildRunningException(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"))

        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&resource=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(409, response.statusCode(), response.body())
    }

    @Test
    fun `a rebuild that fails answers server error`() {
        every { rebuilder.rebuild(any()) } throws IllegalStateException("the store is down")

        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&resource=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(500, response.statusCode(), response.body())
    }

    @Test
    fun `an adapter is refused the rebuild`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&resource=utdanning/elev/person", NOVARI_ADAPTER)

        assertEquals(403, response.statusCode(), response.body())
        verify(exactly = 0) { rebuilder.rebuild(any()) }
    }

    private fun post(
        pathAndQuery: String,
        token: String,
    ): HttpResponse<String> = send("POST", pathAndQuery, token)

    private fun get(
        pathAndQuery: String,
        token: String,
    ): HttpResponse<String> = send("GET", pathAndQuery, token)

    private fun send(
        method: String,
        pathAndQuery: String,
        token: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/provider$pathAndQuery"))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("Authorization", "Bearer $token")
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    @Configuration
    @Profile(PROFILE)
    @EnableAutoConfiguration(exclude = [KafkaAutoConfiguration::class])
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        AdminController::class,
        OrgIdConverter::class,
        FintResourceRefConverter::class,
    )
    class TestApp {
        @Bean
        fun relationEdgeRebuilder(): RelationEdgeRebuilder = mockk()

        @Bean
        fun jwtDecoder(): JwtDecoder =
            JwtDecoder { token ->
                when (token) {
                    NOVARI_CLIENT -> jwt(token, cn = "admin@client.novari.no", scope = "fint-client")
                    NOVARI_ADAPTER -> jwt(token, cn = "test@adapter.novari.no", scope = "fint-adapter")
                    else -> error("unknown test token $token")
                }
            }

        private fun jwt(
            token: String,
            cn: String,
            scope: String,
        ): Jwt =
            Jwt
                .withTokenValue(token)
                .header("alg", "none")
                .claim("cn", cn)
                .claim("fintAssetIDs", "novari.no")
                .claim("scope", listOf(scope))
                .claim("Roles", emptyList<String>())
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(3600))
                .build()
    }

    companion object {
        const val PROFILE = "admin-controller-it"
        private const val NOVARI_CLIENT = "novari-client"
        private const val NOVARI_ADAPTER = "novari-adapter"
    }
}
