package no.fintlabs.consumer.resource

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.JacksonConfiguration
import no.fintlabs.consumer.config.TomcatConfiguration
import no.fintlabs.consumer.resource.dto.createFintResourcesResponse
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.Person
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [CommonResourceLinksIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class CommonResourceLinksIT {
    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EnableConfigurationProperties(ConsumerConfiguration::class)
    @Import(ResourceController::class, JacksonConfiguration::class, TomcatConfiguration::class)
    open class SliceApplication

    @MockitoBean
    private lateinit var resourceService: ResourceService

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newHttpClient()
    private val mapper = ObjectMapper()
    private val baseUrl = "https://api.felleskomponent.no"

    private val person =
        Person(fodselsnummer = Identifikator(identifikatorverdi = "01010112345")).apply {
            addLink("foreldreansvar", Link("fodselsnummer", "02020254321"))
        }

    private fun get(path: String): JsonNode {
        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$path"))
                    .header("x-org-id", "fintlabs.no")
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, response.statusCode(), "unexpected status for $path: ${response.body()}")
        return mapper.readTree(response.body())
    }

    private fun href(
        node: JsonNode,
        relation: String,
    ): String =
        node
            .get("_links")
            .get(relation)
            .get(0)
            .get("href")
            .asText()

    @Test
    fun `a common resource self-links under the component it was requested through`() {
        given(
            resourceService.getResourceById(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"),
                "fodselsnummer",
                "01010112345",
            ),
        ).willReturn(person)
        given(
            resourceService.getResourceById(
                ResourceCoordinate("fintlabs.no", "administrasjon", "personal", "person"),
                "fodselsnummer",
                "01010112345",
            ),
        ).willReturn(person)

        val viaElev = get("/utdanning/elev/person/fodselsnummer/01010112345")
        assertEquals("$baseUrl/utdanning/elev/person/fodselsnummer/01010112345", href(viaElev, "self"))
        assertEquals("$baseUrl/utdanning/elev/person/fodselsnummer/02020254321", href(viaElev, "foreldreansvar"))

        val viaPersonal = get("/administrasjon/personal/person/fodselsnummer/01010112345")
        assertEquals("$baseUrl/administrasjon/personal/person/fodselsnummer/01010112345", href(viaPersonal, "self"))
        assertEquals("$baseUrl/administrasjon/personal/person/fodselsnummer/02020254321", href(viaPersonal, "foreldreansvar"))
    }

    @Test
    fun `every entry of a common resource collection self-links under the requested component`() {
        given(
            resourceService.getResources(
                ResourceCoordinate("fintlabs.no", "utdanning", "elev", "person"),
                0,
                0,
                0,
                null,
            ),
        ).willReturn(createFintResourcesResponse(baseUrl, "utdanning/elev/person", listOf(person), 0, 0, 1))

        val entry =
            get("/utdanning/elev/person")
                .get("_embedded")
                .get("_entries")
                .get(0)

        assertEquals("$baseUrl/utdanning/elev/person/fodselsnummer/01010112345", href(entry, "self"))
    }
}
