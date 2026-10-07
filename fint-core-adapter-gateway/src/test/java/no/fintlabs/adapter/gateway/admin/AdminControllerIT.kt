package no.fintlabs.adapter.gateway.admin

import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.TestcontainersConfiguration
import no.fintlabs.adapter.gateway.config.ClockConfig
import no.fintlabs.adapter.gateway.config.OrgIdConverter
import no.fintlabs.adapter.gateway.config.ProviderProperties
import no.fintlabs.adapter.gateway.config.ResourceSelectionConverter
import no.fintlabs.adapter.gateway.register.ContractService
import no.fintlabs.adapter.gateway.relation.RelationEdgeDrift
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobRunner
import no.fintlabs.adapter.gateway.relation.RelationEdgeJobs
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuild
import no.fintlabs.adapter.gateway.relation.RelationEdgeRebuilder
import no.fintlabs.adapter.gateway.security.AdapterAuthorization
import no.fintlabs.adapter.gateway.security.SecurityConfiguration
import no.fintlabs.adapter.gateway.security.SecurityProblemDetailHandler
import no.novari.core.shared.model.OrgId
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.store.ResourceStore
import no.novari.fint.core.model.FintModel
import org.awaitility.kotlin.await
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Profile
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.context.ActiveProfiles
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The admin endpoints on a real server, so a request goes through the whole filter chain and any
 * forward to the error page, the way it does in the cluster. Jobs run on the real background
 * thread; only the rebuilder and the store are stand-ins. Two fixed tokens stand for a FINT client and a FINT
 * adapter from novari.no.
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

    private val json = JsonMapper.builder().build()

    @Autowired
    private lateinit var resourceStore: ResourceStore

    @Autowired
    private lateinit var topicCleanup: TopicCleanup

    @BeforeEach
    fun resetRebuilder() {
        clearMocks(rebuilder, resourceStore, topicCleanup)
        every { rebuilder.rebuild(any()) } returns REBUILT
        every { rebuilder.drift(any()) } returns RelationEdgeDrift.NONE
    }

    @Test
    fun `a rebuild answers accepted with where to follow the job`() {
        val response = post("/admin/relation-edges/rebuild?orgId=ude-oslo-kommune-no&scope=utdanning/elev/person", NOVARI_ADAPTER)

        assertEquals(202, response.statusCode(), response.body())
        val id = json.readTree(response.body())["id"].asString()
        assertEquals("/provider/admin/relation-edges/jobs/$id", response.headers().firstValue("Location").orElse(null))
    }

    @Test
    fun `a rebuild job names the org and resource it was asked for and holds what the rebuild did`() {
        val job =
            finishedJob(post("/admin/relation-edges/rebuild?orgId=ude-oslo-kommune-no&scope=utdanning/elev/person", NOVARI_ADAPTER))

        assertEquals("DONE", job["state"].asString())
        assertEquals("ude.oslo.kommune.no", job["orgId"].asString())
        assertEquals("utdanning/elev/person", job["resources"][0]["resource"].asString())
        assertEquals(REBUILT.edgesWritten, job["resources"][0]["result"]["edgesWritten"].asLong())
        verify { rebuilder.rebuild(ResourceCoordinate("ude.oslo.kommune.no", "utdanning", "elev", "person")) }
    }

    @Test
    fun `an iso resource is rebuilt under the identity the model gives it`() {
        finishedJob(post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=felles/kodeverk/iso/landkode", NOVARI_ADAPTER))

        verify { rebuilder.rebuild(ResourceCoordinate("fintlabs.no", "felles", "kodeverk", "landkode")) }
    }

    @Test
    fun `a rebuild of a component rebuilds every resource type in it`() {
        val inComponent = FintModel.refsIn("utdanning", "elev")

        val job = finishedJob(post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/elev", NOVARI_ADAPTER))

        val jobResources: Set<String> = job["resources"].values().map { it["resource"].asString() }.toSet()
        assertEquals(inComponent.map { "${it.domainName}/${it.packageName}/${it.resourceName}" }.toSet(), jobResources)
        inComponent.forEach { verify { rebuilder.rebuild(ResourceCoordinate.of(OrgId.from("fintlabs.no"), it)) } }
    }

    @Test
    fun `a rebuild of everything runs every resource type the org has stored`() {
        every { resourceStore.storedCoordinates(OrgId.from("fintlabs.no")) } returns
            listOf(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"),
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"),
            )

        val job = finishedJob(post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=all", NOVARI_ADAPTER))

        val jobResources: List<String> = job["resources"].values().map { it["resource"].asString() }
        assertEquals(listOf("utdanning/elev/elev", "utdanning/elev/person"), jobResources)
        verify { rebuilder.rebuild(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev")) }
        verify { rebuilder.rebuild(ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person")) }
    }

    @Test
    fun `a rebuild of everything for an org with nothing stored finishes with no resources`() {
        every { resourceStore.storedCoordinates(any()) } returns emptyList()

        val job = finishedJob(post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=all", NOVARI_ADAPTER))

        assertEquals("DONE", job["state"].asString())
        assertEquals(0, job["resources"].size())
    }

    @Test
    fun `a rebuild for a component the model does not serve is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/nothing", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
        assertEquals("Bad scope: Not a component the model serves: utdanning/nothing", detailOf(response))
    }

    @Test
    fun `a rebuild for a resource the model does not serve is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/elev/nothing", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
        assertEquals("Bad scope: Not a resource the model serves: utdanning/elev/nothing", detailOf(response))
    }

    @Test
    fun `a rebuild without a scope is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
        assertEquals("Missing scope", detailOf(response))
    }

    @Test
    fun `a rebuild with a blank org id is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?orgId=%20&scope=utdanning/elev/person", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a rebuild without an org id is a bad request`() {
        val response = post("/admin/relation-edges/rebuild?scope=utdanning/elev/person", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a drift check job holds the drift of the resource`() {
        every { rebuilder.drift(any()) } returns
            RelationEdgeDrift(resourcesRead = 4, edgesMissing = 1, edgesStale = 2, edgesOfSourcesGone = 3, examples = emptyList())

        val job = finishedJob(post("/admin/relation-edges/drift?orgId=fintlabs.no&scope=utdanning/elev/person", NOVARI_ADAPTER))

        assertEquals("DRIFT", job["kind"].asString())
        assertEquals(2, job["resources"][0]["result"]["edgesStale"].asLong())
    }

    @Test
    fun `a drift check for a resource the model does not serve is a bad request`() {
        val response = post("/admin/relation-edges/drift?orgId=fintlabs.no&scope=utdanning/elev/nothing", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a job started while another is running answers conflict and names the running job`() {
        val release = CountDownLatch(1)
        every { rebuilder.rebuild(any()) } answers {
            release.await(10, TimeUnit.SECONDS)
            REBUILT
        }
        val first = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/elev/person", NOVARI_ADAPTER)

        val second = post("/admin/relation-edges/drift?orgId=fintlabs.no&scope=utdanning/elev/person", NOVARI_ADAPTER)

        assertEquals(409, second.statusCode(), second.body())
        assertEquals(json.readTree(first.body())["id"].asString(), json.readTree(second.body())["runningJob"].asString())
        release.countDown()
        finishedJob(first)
    }

    @Test
    fun `a rebuild that fails shows as failed on the job`() {
        every { rebuilder.rebuild(any()) } throws IllegalStateException("the store is down")

        val job = finishedJob(post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/elev/person", NOVARI_ADAPTER))

        assertEquals("FAILED", job["state"].asString())
        assertEquals("the store is down", job["resources"][0]["error"].asString())
    }

    @Test
    fun `a job that does not exist is not found`() {
        val response = get("/admin/relation-edges/jobs/${UUID.randomUUID()}", NOVARI_ADAPTER)

        assertEquals(404, response.statusCode(), response.body())
    }

    @Test
    fun `a job id that is not an id is a bad request`() {
        val response = get("/admin/relation-edges/jobs/not-an-id", NOVARI_ADAPTER)

        assertEquals(400, response.statusCode(), response.body())
    }

    @Test
    fun `a Novari adapter can run a topic cleanup, and it is a dry run unless it says otherwise`() {
        every { topicCleanup.cleanup(any(), any(), any()) } returns EMPTY_REPORT

        val response = postJson("/admin/kafka/topics/delete", NOVARI_ADAPTER, """{"pattern": ".*entity.*"}""")

        assertEquals(200, response.statusCode(), response.body())
        verify { topicCleanup.cleanup(match { it.pattern == ".*entity.*" }, true, any()) }
    }

    @Test
    fun `a topic cleanup with dryRun false is passed on as a real run`() {
        every { topicCleanup.cleanup(any(), any(), any()) } returns EMPTY_REPORT

        postJson("/admin/kafka/topics/delete", NOVARI_ADAPTER, """{"pattern": ".*entity.*", "dryRun": false}""")

        verify { topicCleanup.cleanup(any(), false, any()) }
    }

    @Test
    fun `a topic cleanup with a pattern that is not a regex is a bad request`() {
        val response = postJson("/admin/kafka/topics/delete", NOVARI_ADAPTER, """{"pattern": "(["}""")

        assertEquals(400, response.statusCode(), response.body())
        verify(exactly = 0) { topicCleanup.cleanup(any(), any(), any()) }
    }

    @Test
    fun `a FINT client is refused the topic cleanup`() {
        val response = postJson("/admin/kafka/topics/delete", NOVARI_CLIENT, """{"pattern": ".*", "dryRun": false}""")

        assertEquals(403, response.statusCode(), response.body())
        verify(exactly = 0) { topicCleanup.cleanup(any(), any(), any()) }
    }

    @Test
    fun `a FINT client is refused the rebuild`() {
        val response = post("/admin/relation-edges/rebuild?orgId=fintlabs.no&scope=utdanning/elev/person", NOVARI_CLIENT)

        assertEquals(403, response.statusCode(), response.body())
        verify(exactly = 0) { rebuilder.rebuild(any()) }
    }

    private fun detailOf(response: HttpResponse<String>): String? = json.readTree(response.body())["detail"]?.asString()

    private fun finishedJob(started: HttpResponse<String>): JsonNode {
        assertEquals(202, started.statusCode(), started.body())
        val location =
            started
                .headers()
                .firstValue("Location")
                .orElseThrow()
                .removePrefix("/provider")
        var job: JsonNode? = null
        await.atMost(Duration.ofSeconds(5)).until {
            job = json.readTree(get(location, NOVARI_ADAPTER).body())
            job!!["state"].asString() != "RUNNING"
        }
        return job!!
    }

    private fun post(
        pathAndQuery: String,
        token: String,
    ): HttpResponse<String> = send("POST", pathAndQuery, token)

    private fun get(
        pathAndQuery: String,
        token: String,
    ): HttpResponse<String> = send("GET", pathAndQuery, token)

    private fun postJson(
        path: String,
        token: String,
        body: String,
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port/provider$path"))
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

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
    @EnableConfigurationProperties(ProviderProperties::class)
    @Import(
        SecurityConfiguration::class,
        SecurityProblemDetailHandler::class,
        AdapterAuthorization::class,
        AdminController::class,
        RelationEdgeJobs::class,
        RelationEdgeJobRunner::class,
        ClockConfig::class,
        OrgIdConverter::class,
        ResourceSelectionConverter::class,
    )
    class TestApp {
        @Bean
        fun relationEdgeRebuilder(): RelationEdgeRebuilder = mockk()

        @Bean
        fun topicCleanup(): TopicCleanup = mockk()

        @Bean
        fun contractService(): ContractService = mockk()

        @Bean
        fun resourceStore(): ResourceStore = mockk()

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
        private val REBUILT = RelationEdgeRebuild(resourcesRead = 1, edgesWritten = 1, edgesRemoved = 0)
        private val EMPTY_REPORT = TopicCleanupReport(".*", true, emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
        private const val NOVARI_CLIENT = "novari-client"
        private const val NOVARI_ADAPTER = "novari-adapter"
    }
}
