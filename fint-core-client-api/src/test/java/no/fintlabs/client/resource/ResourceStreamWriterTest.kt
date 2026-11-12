package no.fintlabs.client.resource

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponseWrapper
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import tools.jackson.databind.JsonNode
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ResourceStreamWriterTest {
    private val baseUrl = "https://api.felleskomponent.no"
    private val resourceUri = "utdanning/vurdering/elevfravar"
    private val mapper = FintJson.responseMapper(baseUrl)
    private val opaProperties = OpaProperties()
    private val registry = SimpleMeterRegistry()
    private val writer = ResourceStreamWriter(mapper, OpaFieldPruner(opaProperties), registry)

    private val request = MockHttpServletRequest("GET", "/utdanning/vurdering/elevfravar")

    @Test
    fun `writes one resource per line across batches, then the summary line`() {
        val resources = (1..5).map { elevfravar("EF-$it") }
        val response = MockHttpServletResponse()

        writer.write(streamOf(resources.chunked(2)), request, response)

        assertEquals(200, response.status)
        assertEquals("application/x-ndjson", response.contentType)
        val body = response.contentAsString
        assertTrue(body.endsWith("\n"), "the last line is terminated too")
        val lines = body.trimEnd('\n').split("\n")
        assertEquals(6, lines.size)
        assertEquals(
            resources.map { mapper.readTree(mapper.writeValueAsString(it)) },
            lines.dropLast(1).map(mapper::readTree),
            "a line is the same JSON an entry of the JSON response is",
        )
        assertEquals(
            """{"total_items":5,"size":5,"_links":{"self":[{"href":"$baseUrl/$resourceUri"}]}}""",
            lines.last(),
        )
    }

    @Test
    fun `the self link is the stream's`() {
        val response = MockHttpServletResponse()
        val stream =
            ResourceStream(
                fullReadSelfLink(baseUrl, resourceUri, sinceTimeStamp = 1000),
                sequenceOf(batch(elevfravar("EF-1"))),
            )

        writer.write(stream, request, response)

        assertEquals(
            "$baseUrl/$resourceUri?sinceTimeStamp=1000",
            linesOf(response).last().at("/_links/self/0/href").asString(),
        )
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

        writer.write(ResourceStream(fullReadSelfLink(baseUrl, resourceUri), batches), request, response)

        val summary = linesOf(response).last()
        assertEquals(1017, summary.get("total_items").asInt())
        assertEquals(3, summary.get("size").asInt())
    }

    @Test
    fun `an empty read is the summary line alone`() {
        val response = MockHttpServletResponse()

        writer.write(streamOf(emptyList()), request, response)

        val summary = linesOf(response).single()
        assertEquals(0, summary.get("total_items").asInt())
        assertEquals(0, summary.get("size").asInt())
    }

    @Test
    fun `a failure reading the first batch leaves the response untouched`() {
        val response = MockHttpServletResponse()
        val stream = ResourceStream(fullReadSelfLink(baseUrl, resourceUri), sequence { error("mongo gone") })

        assertThrows<IllegalStateException> { writer.write(stream, request, response) }

        assertFalse(response.isCommitted)
        assertEquals(0, response.contentAsByteArray.size)
        assertEquals(0.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
    }

    @Test
    fun `a failure on a later batch ends the body without a summary line and is reported as aborted`() {
        val response = MockHttpServletResponse()
        val batches =
            sequence {
                yield(batch(elevfravar("EF-1"), elevfravar("EF-2")))
                error("mongo gone")
            }
        val stream = ResourceStream(fullReadSelfLink(baseUrl, resourceUri), batches)

        val aborted = assertThrows<ResourceStreamAbortedException> { writer.write(stream, request, response) }

        assertEquals(2, aborted.entriesWritten)
        assertFalse(response.contentAsString.contains("total_items"), "the summary line is never written")
        assertEquals(1.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
    }

    @Test
    fun `a client that goes away is not an abort`() {
        val response = MockHttpServletResponse()
        val gone =
            object : HttpServletResponseWrapper(response) {
                override fun getOutputStream(): ServletOutputStream =
                    object : ServletOutputStream() {
                        override fun write(b: Int) = throw IOException("Broken pipe")

                        override fun isReady() = true

                        override fun setWriteListener(writeListener: WriteListener?) = Unit
                    }
            }

        writer.write(streamOf(listOf(listOf(elevfravar("EF-1")))), request, gone)

        assertEquals(0.0, registry.counter(ResourceStreamWriter.ABORTED_METRIC).count())
    }

    @Test
    fun `the in-flight gauge is back to zero after a read, finished or aborted`() {
        writer.write(streamOf(listOf(listOf(elevfravar("EF-1")))), request, MockHttpServletResponse())
        runCatching {
            writer.write(
                ResourceStream(
                    fullReadSelfLink(baseUrl, resourceUri),
                    sequence {
                        yield(batch(elevfravar("EF-1")))
                        error("x")
                    },
                ),
                request,
                MockHttpServletResponse(),
            )
        }

        assertEquals(0.0, registry.get(ResourceStreamWriter.ACTIVE_METRIC).gauge().value())
    }

    @Test
    fun `with OPA enabled every line is pruned to the request's decision`() {
        opaProperties.enabled = true
        request.setAttribute(
            SecurityConfiguration.OPA_DECISION_ATTRIBUTE,
            OpaDecision.Allowed(fields = setOf("systemid"), relations = emptySet()),
        )
        val response = MockHttpServletResponse()

        writer.write(streamOf(listOf(listOf(elevfravar("EF-1")), listOf(elevfravar("EF-2")))), request, response)

        val entries = linesOf(response).dropLast(1)
        assertEquals(2, entries.size)
        entries.forEach { entry ->
            assertEquals(setOf("systemId", "_links"), entry.propertyNames().toSet())
            assertTrue(entry.at("/_links/self/0/href").asString().contains("/systemid/"))
            assertNull(entry.at("/_links").get("fravarsregistrering"))
        }
    }

    @Test
    fun `with OPA enabled but no decision on the request, lines are pruned to nothing`() {
        opaProperties.enabled = true
        val response = MockHttpServletResponse()

        writer.write(streamOf(listOf(listOf(elevfravar("EF-1")))), request, response)

        val entry = linesOf(response).first()
        assertEquals(setOf("_links"), entry.propertyNames().toSet())
        assertEquals(0, entry.get("_links").size())
    }

    private fun linesOf(response: MockHttpServletResponse): List<JsonNode> =
        response.contentAsString
            .trimEnd('\n')
            .split("\n")
            .map(mapper::readTree)

    private fun streamOf(batches: List<List<FintResource>>) =
        ResourceStream(
            fullReadSelfLink(baseUrl, resourceUri),
            batches.asSequence().map { ResourceBatch(it.size, it) },
        )

    private fun batch(vararg resources: FintResource) = ResourceBatch(resources.size, resources.toList())

    private fun elevfravar(id: String) =
        Elevfravar(systemId = Identifikator(identifikatorverdi = id)).apply {
            addLink("fravarsregistrering", Link("systemid", "FR-$id"))
        }
}
