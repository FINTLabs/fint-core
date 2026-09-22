package no.fintlabs.client.resource

import no.fint.antlr.exception.FilterException
import no.fint.antlr.exception.InvalidSyntaxException
import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.JacksonConfiguration
import no.fintlabs.client.config.TomcatConfiguration
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.event.RequestFintEventService
import no.fintlabs.client.resource.event.RequestStatusService
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.vurdering.Elevfravar
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.ManagementWebSecurityAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterAutoConfiguration
import org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.web.OAuth2ResourceServerWebSecurityAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ResourceFilterIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class ResourceFilterIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration(
        exclude = [
            ManagementWebSecurityAutoConfiguration::class,
            SecurityFilterAutoConfiguration::class,
            ServletWebSecurityAutoConfiguration::class,
            OAuth2ResourceServerAutoConfiguration::class,
            OAuth2ResourceServerWebSecurityAutoConfiguration::class,
        ],
    )
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(
        ResourceController::class,
        ResourceExceptionHandler::class,
        JacksonConfiguration::class,
        TomcatConfiguration::class,
    )
    open class SliceApplication

    @MockitoBean
    private lateinit var resourceService: ResourceService

    @MockitoBean
    private lateinit var requestFintEventService: RequestFintEventService

    @MockitoBean
    private lateinit var requestStatusService: RequestStatusService

    @MockitoBean
    private lateinit var statsService: StatsService

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newHttpClient()
    private val mapper = JsonMapper.builder().build()
    private val baseUrl = "https://api.felleskomponent.no"
    private val resourceCoordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "elevfravar")
    private val elevfravarB = Elevfravar(systemId = Identifikator(identifikatorverdi = "B"))

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("x-org-id", "fintlabs.no")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun postQuery(
        path: String,
        body: String,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("x-org-id", "fintlabs.no")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `GET with dollar-filter forwards the decoded expression to the service`() {
        given(resourceService.getResources(resourceCoordinate, 0, 0, 0, "systemId/identifikatorverdi eq 'B'"))
            .willReturn(
                createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", listOf(elevfravarB), 0, 0, 1),
            )

        val response = get("/utdanning/vurdering/elevfravar?\$filter=systemId%2Fidentifikatorverdi%20eq%20%27B%27")

        assertEquals(200, response.statusCode())
        val entry =
            mapper
                .readTree(response.body())
                .get("_embedded")
                .get("_entries")
                .get(0)
        assertEquals("B", entry.get("systemId").get("identifikatorverdi").asString())
    }

    @Test
    fun `POST dollar-query forwards the raw body as the filter expression`() {
        given(resourceService.getResources(resourceCoordinate, 0, 0, 0, "systemId/identifikatorverdi eq 'B'"))
            .willReturn(
                createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", listOf(elevfravarB), 0, 0, 1),
            )

        val response = postQuery("/utdanning/vurdering/elevfravar/\$query", "systemId/identifikatorverdi eq 'B'")

        assertEquals(200, response.statusCode())
        val entry =
            mapper
                .readTree(response.body())
                .get("_embedded")
                .get("_entries")
                .get(0)
        assertEquals("B", entry.get("systemId").get("identifikatorverdi").asString())
    }

    @Test
    fun `invalid dollar-filter surfaces as a 400, via the shared ResourceExceptionHandler`() {
        given(resourceService.getResources(resourceCoordinate, 0, 0, 0, "systemId/identifikatorverdi = 'B'"))
            .willThrow(FilterException(InvalidSyntaxException("Invalid \$filter: systemId/identifikatorverdi = 'B'")))

        val response = get("/utdanning/vurdering/elevfravar?\$filter=systemId%2Fidentifikatorverdi%20%3D%20%27B%27")

        assertEquals(400, response.statusCode())
        assertEquals(
            "no.fint.antlr.exception.InvalidSyntaxException: Invalid \$filter: systemId/identifikatorverdi = 'B'",
            response.body(),
        )
    }
}
