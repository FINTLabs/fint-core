package no.fintlabs.client.resource

import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.JacksonConfiguration
import no.fintlabs.client.config.TomcatConfiguration
import no.fintlabs.client.resource.dto.fullReadSelfLink
import no.fintlabs.client.resource.event.LiveReadService
import no.fintlabs.client.resource.event.RequestFintEventService
import no.fintlabs.client.resource.event.RequestStatusService
import no.fintlabs.client.security.OpaFieldPruner
import no.fintlabs.client.security.opa.OpaProperties
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.vurdering.Elevfravar
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
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
import tools.jackson.core.JacksonException
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full read through a real Tomcat. The service is mocked so the batches can be shaped and made to
 * fail at a chosen point; the controller, the writer and the exception handler are real. Each
 * batch is bigger than Tomcat's 8 KB output buffer, so the status is on the wire before the
 * second batch is read.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    classes = [ResourceStreamIT.SliceApplication::class],
    properties = [
        "fint.consumer.base-url=https://api.felleskomponent.no",
        "fint.consumer.org-id=fintlabs.no",
    ],
)
class ResourceStreamIT {
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
    @EnableConfigurationProperties(ConsumerConfiguration::class, OpaProperties::class)
    @Import(
        ResourceController::class,
        ResourceStreamWriter::class,
        OpaFieldPruner::class,
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

    @MockitoBean
    private lateinit var liveReadService: LiveReadService

    @LocalServerPort
    private var port = 0

    private val client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
    private val mapper = JsonMapper.builder().build()
    private val baseUrl = "https://api.felleskomponent.no"
    private val coordinate = ResourceCoordinate("fintlabs.no", "utdanning", "vurdering", "elevfravar")
    private val path = "/utdanning/vurdering/elevfravar"

    @Test
    fun `a full read of several batches arrives complete and chunked`() {
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(batches(3)))

        val response = get(path)

        assertEquals(200, response.statusCode())
        assertTrue(response.headers().allValues("Transfer-Encoding").any { it.contains("chunked") })
        assertTrue(response.headers().firstValue("Content-Length").isEmpty)
        val body = mapper.readTree(response.body())
        assertEquals(3 * BATCH, body.at("/_embedded/_entries").size())
        assertEquals(3 * BATCH, body.get("size").asInt())
        assertEquals(3 * BATCH, body.get("total_items").asInt())
        assertEquals("$baseUrl$path", body.at("/_links/self/0/href").asString())
        assertEquals("EF-2-7", body.at("/_embedded/_entries/${2 * BATCH + 7}/systemId/identifikatorverdi").asString())
    }

    @Test
    fun `a full read through dollar-query streams the same way`() {
        given(resourceService.streamResources(coordinate, 0, 0, "systemId/identifikatorverdi ne 'x'"))
            .willReturn(streamOf(batches(2)))

        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$path/\$query"))
                    .header("x-org-id", "fintlabs.no")
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("systemId/identifikatorverdi ne 'x'"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(200, response.statusCode())
        assertEquals(2 * BATCH, mapper.readTree(response.body()).get("size").asInt())
    }

    @Test
    fun `a failure on the first batch is a 500`() {
        given(resourceService.streamResources(coordinate, 0, 0, null))
            .willReturn(streamOf(sequence { error("mongo gone") }))

        val response = get(path)

        assertEquals(500, response.statusCode())
    }

    /**
     * The status is already sent when a later batch fails, so the client sees 200. What it must
     * never see is a body that parses as a complete answer: the entries array is never closed,
     * `total_items` and `size` never come, and no error document is appended. On the wire Tomcat finishes the
     * chunked encoding after the exception, so the client's read succeeds and the body is what
     * gives the failure away. This test pins that down; if Tomcat ever drops the connection
     * instead, the read throws and this test will say so.
     */
    @Test
    fun `a failure on a later batch leaves a body that does not parse, with nothing appended`() {
        val batches =
            sequence {
                yield(batch(0))
                error("mongo gone")
            }
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(batches))

        val response = get(path)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(
            body.startsWith("""{"offset":0,"_embedded":{"_entries":["""),
            "the body starts as a full read",
        )
        assertFalse(body.contains("total_items") || body.contains("\"size\""), "the counts are never written")
        assertFalse(body.contains("detail") || body.contains("Stream aborted"), "no error document is appended")
        assertThrows<JacksonException>("the body must not parse as a complete value") {
            mapper.readValue(body, Map::class.java)
        }
    }

    private fun get(path: String): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("x-org-id", "fintlabs.no")
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun streamOf(batches: Sequence<List<Elevfravar>>) =
        ResourceStream(
            offset = 0,
            selfLink = fullReadSelfLink(baseUrl, "utdanning/vurdering/elevfravar"),
            batches = batches.map { ResourceBatch(it.size, it) },
        )

    private fun batches(count: Int): Sequence<List<Elevfravar>> = (0 until count).asSequence().map(::batch)

    /** [BATCH] resources of roughly 100 bytes each, so one batch is well past Tomcat's 8 KB buffer. */
    private fun batch(index: Int): List<Elevfravar> =
        (0 until BATCH).map { Elevfravar(systemId = Identifikator(identifikatorverdi = "EF-$index-$it")) }

    private companion object {
        const val BATCH = 200
    }
}
