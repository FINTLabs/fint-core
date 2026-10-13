package no.fintlabs.client.resource

import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.servlet.ServletOutputStream
import jakarta.servlet.WriteListener
import jakarta.servlet.http.HttpServletResponseWrapper
import no.fintlabs.client.resource.dto.createFintResourcesResponse
import no.fintlabs.client.resource.dto.dumpSelfLink
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
import tools.jackson.core.JacksonException
import java.io.IOException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The writer must give the JSON a returned [no.fintlabs.client.resource.dto.FintResourcesResponse]
 * gives, so the expected bodies here are built by serializing that type with the same mapper.
 */
class ResourceDumpWriterTest {
    private val baseUrl = "https://api.felleskomponent.no"
    private val resourceUri = "utdanning/vurdering/elevfravar"
    private val mapper = FintJson.responseMapper(baseUrl)
    private val opaProperties = OpaProperties()
    private val registry = SimpleMeterRegistry()
    private val writer = ResourceDumpWriter(mapper, OpaFieldPruner(opaProperties), registry)

    private val request = MockHttpServletRequest("GET", "/utdanning/vurdering/elevfravar")

    @Test
    fun `writes the same JSON as the page response form, across batches`() {
        val resources = (1..5).map { elevfravar("EF-$it") }
        val response = MockHttpServletResponse()

        writer.write(dump(resources.chunked(2)), request, response)

        val expected = createFintResourcesResponse(baseUrl, resourceUri, resources, 0, 0, 5)
        assertEquals(200, response.status)
        assertEquals("application/json", response.contentType)
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), mapper.readTree(response.contentAsString))
        assertEquals(
            listOf("offset", "_embedded", "_links", "total_items", "size"),
            mapper.readTree(response.contentAsString).propertyNames().toList(),
            "the counts close the body, since they are only known once every entry is written",
        )
    }

    @Test
    fun `the self link and offset are the dump's`() {
        val response = MockHttpServletResponse()
        val dump =
            ResourceDump.of(
                offset = 3,
                selfLink = dumpSelfLink(baseUrl, resourceUri, sinceTimeStamp = 1000),
                resources = listOf(elevfravar("EF-1")),
            )

        writer.write(dump, request, response)

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
                DumpBatch(read = 500, resources = listOf(elevfravar("EF-1"), elevfravar("EF-2"))),
                DumpBatch(read = 500, resources = emptyList()),
                DumpBatch(read = 17, resources = listOf(elevfravar("EF-3"))),
            )

        writer.write(ResourceDump(0, dumpSelfLink(baseUrl, resourceUri), batches), request, response)

        val body = mapper.readTree(response.contentAsString)
        assertEquals(1017, body.get("total_items").asInt())
        assertEquals(3, body.get("size").asInt())
    }

    @Test
    fun `an empty dump is an empty list`() {
        val response = MockHttpServletResponse()

        writer.write(dump(emptyList()), request, response)

        val expected = createFintResourcesResponse(baseUrl, resourceUri, emptyList(), 0, 0, 0)
        assertEquals(mapper.readTree(mapper.writeValueAsString(expected)), mapper.readTree(response.contentAsString))
    }

    @Test
    fun `a failure reading the first batch leaves the response untouched`() {
        val response = MockHttpServletResponse()
        val dump = ResourceDump(0, dumpSelfLink(baseUrl, resourceUri), sequence { error("mongo gone") })

        assertThrows<IllegalStateException> { writer.write(dump, request, response) }

        assertFalse(response.isCommitted)
        assertEquals(0, response.contentAsByteArray.size)
        assertEquals(0.0, registry.counter(ResourceDumpWriter.ABORTED_METRIC).count())
    }

    @Test
    fun `a failure on a later batch stops the body where it is and is reported as aborted`() {
        val response = MockHttpServletResponse()
        val batches =
            sequence {
                yield(batch(elevfravar("EF-1"), elevfravar("EF-2")))
                error("mongo gone")
            }
        val dump = ResourceDump(0, dumpSelfLink(baseUrl, resourceUri), batches)

        val aborted = assertThrows<ResourceDumpAbortedException> { writer.write(dump, request, response) }

        assertEquals(2, aborted.entriesWritten)
        val body = response.contentAsString
        assertFalse(body.contains("total_items") || body.contains("\"size\""), "the counts are never written")
        assertThrows<JacksonException>("the body must not parse as a complete value") {
            mapper.readValue(body, Map::class.java)
        }
        assertEquals(1.0, registry.counter(ResourceDumpWriter.ABORTED_METRIC).count())
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

        writer.write(dump(listOf(listOf(elevfravar("EF-1")))), request, gone)

        assertEquals(0.0, registry.counter(ResourceDumpWriter.ABORTED_METRIC).count())
    }

    @Test
    fun `the in-flight gauge is back to zero after a dump, finished or aborted`() {
        writer.write(dump(listOf(listOf(elevfravar("EF-1")))), request, MockHttpServletResponse())
        runCatching {
            writer.write(
                ResourceDump(
                    0,
                    dumpSelfLink(baseUrl, resourceUri),
                    sequence {
                        yield(batch(elevfravar("EF-1")))
                        error("x")
                    },
                ),
                request,
                MockHttpServletResponse(),
            )
        }

        assertEquals(0.0, registry.get(ResourceDumpWriter.ACTIVE_METRIC).gauge().value())
    }

    @Test
    fun `with OPA enabled every entry is pruned to the request's decision`() {
        opaProperties.enabled = true
        request.setAttribute(
            SecurityConfiguration.OPA_DECISION_ATTRIBUTE,
            OpaDecision.Allowed(fields = setOf("systemid"), relations = emptySet()),
        )
        val response = MockHttpServletResponse()

        writer.write(dump(listOf(listOf(elevfravar("EF-1")), listOf(elevfravar("EF-2")))), request, response)

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

        writer.write(dump(listOf(listOf(elevfravar("EF-1")))), request, response)

        val entry = mapper.readTree(response.contentAsString).at("/_embedded/_entries/0")
        assertEquals(setOf("_links"), entry.propertyNames().toSet())
        assertEquals(0, entry.get("_links").size())
    }

    private fun dump(batches: List<List<FintResource>>) =
        ResourceDump(0, dumpSelfLink(baseUrl, resourceUri), batches.asSequence().map { DumpBatch(it.size, it) })

    private fun batch(vararg resources: FintResource) = DumpBatch(resources.size, resources.toList())

    private fun elevfravar(id: String) =
        Elevfravar(systemId = Identifikator(identifikatorverdi = id)).apply {
            addLink("fravarsregistrering", Link("systemid", "FR-$id"))
        }
}
