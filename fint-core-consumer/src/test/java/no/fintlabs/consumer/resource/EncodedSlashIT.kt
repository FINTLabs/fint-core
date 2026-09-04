package no.fintlabs.consumer.resource

import no.fintlabs.consumer.admin.StatsService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.JacksonConfiguration
import no.fintlabs.consumer.config.TomcatConfiguration
import no.fintlabs.consumer.resource.event.RequestFintEventService
import no.fintlabs.consumer.resource.event.RequestStatusService
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.elev.Elev
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
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
    classes = [EncodedSlashIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class EncodedSlashIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(ResourceController::class, JacksonConfiguration::class, TomcatConfiguration::class)
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

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("x-org-id", "fintlabs.no")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `an encoded slash in the id value reaches the controller decoded`() {
        given(
            resourceService.getResourceById(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"),
                "systemid",
                "2023/145",
            ),
        ).willReturn(Elev(systemId = Identifikator(identifikatorverdi = "2023/145")))

        val response = get("/utdanning/elev/elev/systemId/2023%2F145")

        assertEquals(200, response.statusCode())
        val body = mapper.readTree(response.body())
        assertEquals("2023/145", body.get("systemId").get("identifikatorverdi").asString())
        assertEquals(
            "https://api.felleskomponent.no/utdanning/elev/elev/systemid/2023%2F145",
            body
                .get("_links")
                .get("self")
                .get(0)
                .get("href")
                .asString(),
        )
    }

    @Test
    fun `a raw slash is a path separator and does not match the id route`() {
        val response = get("/utdanning/elev/elev/systemId/2023/145")

        assertEquals(404, response.statusCode())
    }
}
