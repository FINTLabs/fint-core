package no.fintlabs.client.resource

import no.fintlabs.client.admin.StatsService
import no.fintlabs.client.config.ConsumerConfiguration
import no.fintlabs.client.config.JacksonConfiguration
import no.fintlabs.client.config.TomcatConfiguration
import no.fintlabs.client.resource.dto.createFintResourcesResponse
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
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.then
import org.mockito.Mockito.never
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
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A streamed read through a real Tomcat. The service is mocked so the batches can be shaped and
 * made to fail at a chosen point. The controller, the writer and the exception handler are real.
 * Each batch is bigger than Tomcat's 8 KB output buffer, so the status is on the wire before the
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
    fun `a read without size that asks for NDJSON arrives as chunked lines with a summary last`() {
        given(resourceService.streamResources(coordinate, 0, null)).willReturn(streamOf(batches(3)))

        val response = get(path, NDJSON)

        assertEquals(200, response.statusCode())
        assertEquals(NDJSON, response.headers().firstValue("Content-Type").get())
        assertTrue(response.headers().allValues("Transfer-Encoding").any { it.contains("chunked") })
        assertTrue(response.headers().firstValue("Content-Length").isEmpty)
        val lines = linesOf(response)
        assertEquals(3 * BATCH + 1, lines.size)
        assertEquals("EF-2-7", lines[2 * BATCH + 7].at("/systemId/identifikatorverdi").asString())
        assertEquals(3 * BATCH, lines.last().get("total_items").asInt())
        assertEquals(3 * BATCH, lines.last().get("size").asInt())
        assertEquals("$baseUrl$path", lines.last().at("/_links/self/0/href").asString())
    }

    @Test
    fun `a dollar-query that asks for NDJSON streams the same way`() {
        given(resourceService.streamResources(coordinate, 0, "systemId/identifikatorverdi ne 'x'"))
            .willReturn(streamOf(batches(2)))

        val response =
            client.send(
                HttpRequest
                    .newBuilder(URI.create("http://localhost:$port$path/\$query"))
                    .header("x-org-id", "fintlabs.no")
                    .header("Content-Type", "text/plain")
                    .header("Accept", NDJSON)
                    .POST(HttpRequest.BodyPublishers.ofString("systemId/identifikatorverdi ne 'x'"))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )

        assertEquals(200, response.statusCode())
        assertEquals(2 * BATCH, linesOf(response).last().get("size").asInt())
    }

    @ParameterizedTest
    @ValueSource(
        strings = ["", "*/*", "application/json", "application/*", "application/json, application/x-ndjson;q=0"],
    )
    fun `a read that does not name NDJSON gets the JSON body and opens no stream`(accept: String) {
        given(resourceService.getResources(coordinate, 0, 0, 0, null, null))
            .willReturn(createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", batch(0), 0, 0, BATCH))

        val response = get(path, accept.ifEmpty { null })

        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").get())
        assertEquals(BATCH, mapper.readTree(response.body()).at("/_embedded/_entries").size())
        then(resourceService).should(never()).streamResources(coordinate, 0, null)
    }

    @Test
    fun `a page is not streamed, it stays JSON for a client that takes both`() {
        given(resourceService.getResources(coordinate, 2, 0, 0, null, null))
            .willReturn(
                createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", batch(0).take(2), 0, 2, 2),
            )

        val response = get("$path?size=2", "$NDJSON, application/json")

        assertEquals(200, response.statusCode())
        assertEquals("application/json", response.headers().firstValue("Content-Type").get())
        then(resourceService).should(never()).streamResources(coordinate, 0, null)
    }

    @Test
    fun `a page for a client that takes NDJSON only is a 406`() {
        given(resourceService.getResources(coordinate, 2, 0, 0, null, null))
            .willReturn(
                createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", batch(0).take(2), 0, 2, 2),
            )

        val response = get("$path?size=2", NDJSON)

        assertEquals(406, response.statusCode())
    }

    @Test
    fun `an invalid filter is a 400 for a client that asks for NDJSON too`() {
        val response = get("$path?\$filter=systemId%2Fidentifikatorverdi%20%3D%20%27B%27", NDJSON)

        assertEquals(400, response.statusCode())
        then(resourceService).shouldHaveNoInteractions()
    }

    @Test
    fun `a failure on the first batch is a 500`() {
        given(resourceService.streamResources(coordinate, 0, null))
            .willReturn(streamOf(sequence { error("mongo gone") }))

        val response = get(path, NDJSON)

        assertEquals(500, response.statusCode())
    }

    /**
     * The status is already sent when a later batch fails, so the client sees 200. Tomcat finishes
     * the chunked encoding after the exception, so the client's read succeeds too. What tells the
     * client the read is cut off is that the summary line never comes. Nothing may be appended
     * that could be taken for one.
     */
    @Test
    fun `a failure on a later batch ends the body without a summary line, with nothing appended`() {
        val batches =
            sequence {
                yield(batch(0))
                error("mongo gone")
            }
        given(resourceService.streamResources(coordinate, 0, null)).willReturn(streamOf(batches))

        val response = get(path, NDJSON)

        assertEquals(200, response.statusCode())
        val body = response.body()
        assertTrue(
            body.startsWith("""{"systemId":{"identifikatorverdi":"EF-0-0"}"""),
            "the body starts with the first entry",
        )
        assertFalse(body.contains("total_items"), "the summary line is never written")
        assertFalse(body.contains("detail") || body.contains("Stream aborted"), "no error document is appended")
    }

    private fun get(
        path: String,
        accept: String?,
    ): HttpResponse<String> =
        client.send(
            HttpRequest
                .newBuilder(URI.create("http://localhost:$port$path"))
                .header("x-org-id", "fintlabs.no")
                .apply { accept?.let { header("Accept", it) } }
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    private fun linesOf(response: HttpResponse<String>): List<JsonNode> =
        response
            .body()
            .trimEnd('\n')
            .split("\n")
            .map(mapper::readTree)

    private fun streamOf(batches: Sequence<List<Elevfravar>>) =
        ResourceStream(
            selfLink = fullReadSelfLink(baseUrl, "utdanning/vurdering/elevfravar"),
            batches = batches.map { ResourceBatch(it.size, it) },
        )

    private fun batches(count: Int): Sequence<List<Elevfravar>> = (0 until count).asSequence().map(::batch)

    /** [BATCH] resources of roughly 100 bytes each, so one batch is well past Tomcat's 8 KB buffer. */
    private fun batch(index: Int): List<Elevfravar> =
        (0 until BATCH).map { Elevfravar(systemId = Identifikator(identifikatorverdi = "EF-$index-$it")) }

    private companion object {
        const val BATCH = 200
        const val NDJSON = "application/x-ndjson"
    }
}
