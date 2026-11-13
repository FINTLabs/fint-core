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
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
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
import java.io.IOException
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A full read through a real Tomcat. The service is mocked so the batches can be shaped and made to
 * fail at a chosen point. The controller, the writer and the Tomcat configuration are real. Each
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

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "*/*",
            "application/json",
            "application/hal+json",
            "application/hal+json, application/json;q=0.5",
            "application/*",
            "text/html, application/xhtml+xml, */*;q=0.8",
            "application/xml",
        ],
    )
    fun `a full read answers an Accept header with the status and content type a page does`(accept: String) {
        given(resourceService.streamResources(coordinate, 0, 0, null)).willAnswer { streamOf(batches(1)) }
        given(resourceService.getResources(coordinate, 1, 0, 0, null))
            .willReturn(
                createFintResourcesResponse(baseUrl, "utdanning/vurdering/elevfravar", batch(0).take(1), 0, 1, 1),
            )

        val fullRead = get(path, accept)
        val page = get("$path?size=1", accept)

        assertEquals(page.statusCode(), fullRead.statusCode())
        assertEquals(page.headers().firstValue("Content-Type"), fullRead.headers().firstValue("Content-Type"))
    }

    @Test
    fun `a client that asks for HAL gets the full read labelled as HAL`() {
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(batches(1)))

        val response = get(path, "application/hal+json")

        assertEquals(200, response.statusCode())
        assertEquals("application/hal+json", response.headers().firstValue("Content-Type").orElse(null))
        assertEquals(BATCH, mapper.readTree(response.body()).get("size").asInt())
    }

    @Test
    fun `an Accept header we cannot serve is a 406 and nothing is read`() {
        var read = false
        val untouched =
            sequence {
                read = true
                yield(batch(0))
            }
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(untouched))

        val response = get(path, "application/xml")

        assertEquals(406, response.statusCode())
        assertFalse(read, "the cursor is only opened when the writer asks for the first batch")
    }

    /**
     * Nothing has left Tomcat's buffer when the second batch fails, so the failure can still be
     * answered with a status.
     */
    @Test
    fun `a failure on a later batch before anything was sent is a 500`() {
        val batches =
            sequence {
                yield(batch(0).take(1))
                error("mongo gone")
            }
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(batches))

        val response = get(path)

        assertEquals(500, response.statusCode())
        assertFalse(response.body().contains("EF-0-0"), "what was buffered is thrown away")
    }

    /**
     * The status is already sent when a later batch fails, so it cannot be changed. The
     * connection is dropped instead, which the client's HTTP library reports as a failed read.
     */
    @Test
    fun `a failure after the first bytes were sent is a network error for the client`() {
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(failingAfterFirstBatch()))

        assertThrows<IOException> { get(path) }
    }

    /**
     * The same failure seen on the wire. Tomcat sends what was written and closes the connection
     * without the empty chunk that ends a chunked body, so the response is incomplete by the rules
     * of HTTP and not only because its JSON is cut off.
     */
    @Test
    fun `a failure after the first bytes were sent closes the connection without the last chunk`() {
        given(resourceService.streamResources(coordinate, 0, 0, null)).willReturn(streamOf(failingAfterFirstBatch()))

        val raw =
            Socket("localhost", port).use { socket ->
                socket.soTimeout = 10_000
                socket.getOutputStream().write(
                    "GET $path HTTP/1.1\r\nHost: localhost:$port\r\nx-org-id: fintlabs.no\r\n\r\n".toByteArray(),
                )
                // Reads until Tomcat closes. A kept-alive connection would run into the timeout
                socket.getInputStream().readAllBytes().decodeToString()
            }

        assertTrue(raw.startsWith("HTTP/1.1 200"), "the status was sent before the failure")
        assertTrue(raw.contains("Transfer-Encoding: chunked"))
        assertTrue(
            raw.contains("""{"offset":0,"_embedded":{"_entries":["""),
            "what was written before the failure went out",
        )
        assertFalse(raw.endsWith("0\r\n\r\n"), "the chunk that ends the body never comes")
        assertFalse(raw.contains("total_items"), "the counts are never written")
        assertFalse(raw.contains("mongo gone") || raw.contains("\"status\":500"), "no error document is appended")
    }

    private fun failingAfterFirstBatch(): Sequence<List<Elevfravar>> =
        sequence {
            yield(batch(0))
            error("mongo gone")
        }

    private fun get(
        path: String,
        accept: String = "",
    ): HttpResponse<String> {
        val request =
            HttpRequest
                .newBuilder(
                    URI.create("http://localhost:$port$path"),
                ).header("x-org-id", "fintlabs.no")
        if (accept.isNotEmpty()) request.header("Accept", accept)
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString())
    }

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
