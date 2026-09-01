package no.fintlabs.consumer.resource

import no.fintlabs.consumer.admin.StatsService
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.JacksonConfiguration
import no.fintlabs.consumer.config.TomcatConfiguration
import no.fintlabs.consumer.resource.event.RequestFintEventService
import no.fintlabs.consumer.resource.event.RequestStatusService
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.uri.LinkCodec
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
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [EncodedIdCharactersIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package-name=elev",
        "fint.consumer.pod-url=http://localhost",
    ],
)
class EncodedIdCharactersIT {
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
    fun `spaces, plus, percent and non-ascii id values are fetchable through their own hrefs`() {
        val idValues = listOf("a b", "a+b", "100%", "æøå")

        val outcomes =
            idValues.map { idValue ->
                given(
                    resourceService.getResourceById(
                        ResourceCoordinate("fintlabs.no", "utdanning", "elev", "elev"),
                        "systemid",
                        idValue,
                    ),
                ).willReturn(Elev(systemId = Identifikator(identifikatorverdi = idValue)))

                val encoded = LinkCodec.encodeIdValue(idValue)
                val status = get("/utdanning/elev/elev/systemid/$encoded").statusCode()
                "'$idValue' as '$encoded' -> $status"
            }

        assertEquals(
            idValues.map { "'$it' as '${LinkCodec.encodeIdValue(it)}' -> 200" },
            outcomes,
        )
    }

    @Test
    fun `an encoded backslash in the id value is rejected by Tomcat before Spring sees it`() {
        assertEquals(400, get("/utdanning/elev/elev/systemid/AD%5Cuser").statusCode())
    }
}
