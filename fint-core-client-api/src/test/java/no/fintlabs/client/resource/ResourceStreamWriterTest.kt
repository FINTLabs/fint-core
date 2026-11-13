package no.fintlabs.client.resource

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponse
import jakarta.servlet.http.HttpServletResponseWrapper
import no.fintlabs.client.config.AbortedResponseValve
import no.fintlabs.client.resource.dto.FintResourcesResponse
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.dto.fullReadSelfLink
import no.fintlabs.client.security.OpaFieldPruner
import no.fintlabs.client.security.SecurityConfiguration
import no.fintlabs.client.security.opa.OpaDecision
import no.fintlabs.client.security.opa.OpaProperties
import no.novari.core.shared.json.FintJson
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.core.model.utdanning.vurdering.Elevfravar
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.MediaType
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.core.JacksonException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The writer must give the JSON a returned [no.fintlabs.client.resource.dto.FintResourcesResponse]
 * gives, so the expected bodies here are built by serializing that type with the same mapper.
 */
class ResourceStreamWriterTest {
    private val baseUrl = "https://api.felleskomponent.no"
    private val resourceUri = "utdanning/vurdering/elevfravar"
    private val mapper = FintJson.responseMapper(baseUrl)
    private val opaProperties = OpaProperties()
    private val registry = SimpleMeterRegistry()
    private val writer = ResourceStreamWriter(mapper, OpaFieldPruner(opaProperties), registry)

    private val request = MockHttpServletRequest("GET", "/utdanning/vurdering/elevfravar")

    @BeforeEach
    fun bindRequest() = RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))

    @AfterEach
    fun releaseRequest() = RequestContextHolder.resetRequestAttributes()

    @Test
    fun `writes a resource stream as JSON and nothing else`() {
        assertTrue(writer.canWrite(ResourceStream::class.java, MediaType.APPLICATION_JSON))
        assertTrue(writer.canWrite(ResourceStream::class.java, MediaType.parseMediaType("application/hal+json")))
        assertFalse(writer.canWrite(ResourceStream::class.java, MediaType.APPLICATION_XML))
        assertFalse(writer.canWrite(FintResourcesResponse::class.java, MediaType.APPLICATION_JSON))
        assertFalse(writer.canRead(ResourceStream::class.java, MediaType.APPLICATION_JSON))
    }

    @Test
    fun `writes the same JSON as the page response form, across batches`() {
        val resources = (1..5).map { elevfravar("EF-$it") }
        val response = MockHttpServletResponse()

        write(streamOf(resources.chunked(2)), response)

        val expected = createFintResourcesResponse(baseUrl, resourceUri, resources, 0, 0, 5)
        assertEquals(200, response.status)
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), mapper.readTree(response.contentAsString))
        assertEquals(
            listOf("offset", "_embedded", "_links", "total_items", "size"),
            mapper.readTree(response.contentAsString).propertyNames().toList(),
            "the counts close the body, since they are only known once every entry is written",
        )
    }

    @Test
    fun `the content type is the one Spring negotiated`() {
        val response = MockHttpServletResponse()

        write(streamOf(listOf(listOf(elevfravar("EF-1")))), response, MediaType.parseMediaType("application/hal+json"))

        assertEquals("application/hal+json", response.contentType)
    }

    @Test
    fun `the self link and offset are the stream's`() {
        val response = MockHttpServletResponse()
        val stream =
            ResourceStream.of(
                offset = 3,
                selfLink = fullReadSelfLink(baseUrl, resourceUri, sinceTimeStamp = 1000),
                resources = listOf(elevfravar("EF-1")),
            )

        write(stream, response)

        val body = mapper.readTree(response.contentAsString)
        assertEquals("$baseUrl/$resourceUri?sinceTimeStamp=1000", body.at("/_links/self/0/href").asString())
        assertEquals(3, body.get("offset").asInt())
        assertEquals(1, body.get("size").asInt())
    }

    @Test
    fun `total_items counts what the cursor yielded before the filter, size what was written`() {
        val response = MockHttpServletResponse()
        val batches =
            sequenceOf(
                ResourceBatch(read = 500, resources = listOf(elevfravar("EF-1"), elevfravar("EF-2"))),
                ResourceBatch(read = 500, resources = emptyList()),
                ResourceBatch(read = 17, resources = listOf(elevfravar("EF-3"))),
            )

        write(ResourceStream(0, fullReadSelfLink(baseUrl, resourceUri), batches), response)

        val body = mapper.readTree(response.contentAsString)
        assertEquals(1017, body.get("total_items").asInt())
        assertEquals(3, body.get("size").asInt())
    }

    @Test
    fun `an empty full read is an empty list`() {
        val response = MockHttpServletResponse()

        write(streamOf(emptyList()), response)

        val expected = createFintResourcesResponse(baseUrl, resourceUri, emptyList(), 0, 0, 0)
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), mapper.readTree(response.contentAsString))
    }

    @Test
    fun `the stream is closed after the write`() {
        var closed = false
        val stream =
            ResourceStream(0, fullReadSelfLink(baseUrl, resourceUri), sequenceOf(batch(elevfravar("EF-1")))) {
                closed =
                    true
            }

        write(stream)

        assertTrue(closed)
    }

    @Test
    fun `a failure reading the first batch leaves the response untouched`() {
        var closed = false
        val response = MockHttpServletResponse()
        val stream =
            ResourceStream(0, fullReadSelfLink(baseUrl, resourceUri), sequence { error("mongo gone") }) {
                closed =
                    true
            }

        assertThrows<IllegalStateException> { write(stream, response) }

        assertFalse(response.isCommitted)
        assertEquals(0, response.contentAsByteArray.size)
        assertEquals(0.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
        assertNull(request.getAttribute(AbortedResponseValve.ABORTED_ATTRIBUTE))
        assertTrue(closed, "the cursor is released on a failure too")
    }

    @Test
    fun `a later failure before anything was sent is thrown on, so it still gets an error status`() {
        val response = MockHttpServletResponse()
        val batches =
            sequence {
                // Far below the response buffer, so nothing has been sent when the next batch fails
                yield(batch(elevfravar("EF-1")))
                error("mongo gone")
            }

        assertThrows<IllegalStateException> {
            write(ResourceStream(0, fullReadSelfLink(baseUrl, resourceUri), batches), response)
        }

        assertFalse(response.isCommitted)
        assertEquals(0.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
        assertNull(request.getAttribute(AbortedResponseValve.ABORTED_ATTRIBUTE))
    }

    @Test
    fun `a failure after the first bytes were sent cuts the body and asks for the connection to be dropped`() {
        val response = MockHttpServletResponse()
        val failure = IllegalStateException("mongo gone")
        val batches =
            sequence {
                yield(bigBatch())
                throw failure
            }

        write(ResourceStream(0, fullReadSelfLink(baseUrl, resourceUri), batches), response)

        assertTrue(response.isCommitted, "the first batch is past the buffers, so the status is out")
        assertSame(failure, request.getAttribute(AbortedResponseValve.ABORTED_ATTRIBUTE))
        assertEquals(1.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
        val body = response.contentAsString
        assertFalse(body.contains("total_items") || body.contains("\"size\""), "the counts are never written")
        assertThrows<JacksonException>("the body must not parse as a complete value") {
            mapper.readValue(body, Map::class.java)
        }
    }

    @Test
    fun `a client that goes away is not an abort`() {
        val gone =
            object : HttpServletResponseWrapper(MockHttpServletResponse()) {
                override fun getOutputStream(): ServletOutputStream =
                    object : ServletOutputStream() {
                        override fun write(b: Int) = throw IOException("Broken pipe")

                        override fun isReady() = true

                        override fun setWriteListener(writeListener: WriteListener?) = Unit
                    }

                // The status is out by the time a client can leave
                override fun isCommitted() = true
            }

        write(streamOf(listOf(listOf(elevfravar("EF-1")))), gone)

        assertEquals(0.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
        assertNull(request.getAttribute(AbortedResponseValve.ABORTED_ATTRIBUTE))
    }

    @Test
    fun `the in-flight gauge is back to zero after a full read, finished or aborted`() {
        write(streamOf(listOf(listOf(elevfravar("EF-1")))))
        write(
            ResourceStream(
                0,
                fullReadSelfLink(baseUrl, resourceUri),
                sequence {
                    yield(bigBatch())
                    error("x")
                },
            ),
        )

        assertEquals(1.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
        assertEquals(0.0, registry.get(ResourceStreamWriter.ACTIVE_METRIC).gauge().value())
    }

    @Test
    fun `with OPA enabled every entry is pruned to the request's decision`() {
        opaProperties.enabled = true
        request.setAttribute(
            SecurityConfiguration.OPA_DECISION_ATTRIBUTE,
            OpaDecision.Allowed(fields = setOf("systemid"), relations = emptySet()),
        )
        val response = MockHttpServletResponse()

        write(streamOf(listOf(listOf(elevfravar("EF-1")), listOf(elevfravar("EF-2")))), response)

        val entries = mapper.readTree(response.contentAsString).at("/_embedded/_entries")
        assertEquals(2, entries.size())
        entries.forEach { entry ->
            assertEquals(setOf("systemId", "_links"), entry.propertyNames().toSet())
            assertTrue(entry.at("/_links/self/0/href").asString().contains("/systemid/"))
            assertNull(entry.at("/_links").get("fravarsregistrering"))
        }
    }

    @Test
    fun `with OPA enabled but no decision on the request, entries are pruned to nothing`() {
        opaProperties.enabled = true
        val response = MockHttpServletResponse()

        write(streamOf(listOf(listOf(elevfravar("EF-1")))), response)

        val entry = mapper.readTree(response.contentAsString).at("/_embedded/_entries/0")
        assertEquals(setOf("_links"), entry.propertyNames().toSet())
        assertEquals(0, entry.get("_links").size())
    }

    /** Writes the way Spring does once it has picked this converter and the content type. */
    private fun write(
        stream: ResourceStream,
        response: HttpServletResponse = MockHttpServletResponse(),
        contentType: MediaType = MediaType.APPLICATION_JSON,
    ) = writer.write(stream, contentType, ServletServerHttpResponse(response))

    private fun streamOf(batches: List<List<FintResource>>) =
        ResourceStream(
            0,
            fullReadSelfLink(baseUrl, resourceUri),
            batches.asSequence().map { ResourceBatch(it.size, it) },
        )

    private fun batch(vararg resources: FintResource) = ResourceBatch(resources.size, resources.toList())

    /** Bigger than the generator's and the response's buffers together, so writing it sends the status. */
    private fun bigBatch() = (1..300).map { elevfravar("EF-$it") }.let { ResourceBatch(it.size, it) }

    private fun elevfravar(id: String) =
        Elevfravar(systemId = Identifikator(identifikatorverdi = id)).apply {
            addLink("fravarsregistrering", Link("systemid", "FR-$id"))
        }
}
