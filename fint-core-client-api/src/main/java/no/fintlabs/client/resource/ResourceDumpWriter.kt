package no.fintlabs.client.resource

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import no.fintlabs.client.security.OpaFieldPruner
import no.fintlabs.client.security.opa.OpaDecision
import no.novari.fint.core.model.FintResource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import tools.jackson.core.JsonGenerator
import tools.jackson.core.exc.JacksonIOException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max

/**
 * Writes a [ResourceDump] straight to the response while it is read, so the body leaves the pod
 * batch by batch and the collection is never held in memory. The JSON is the same as a
 * [no.fintlabs.client.resource.dto.FintResourcesResponse] would give, fields in the same order:
 * `offset` and `total_items`, then the entries, the self link and `size`. `total_items` comes
 * from the count the dump was opened with, so it can be written before the entries; `size`
 * is how many were written, so it comes after.
 *
 * The first batch is read, enriched and filtered before anything is written. So a filter that
 * fails to evaluate, a store that cannot be reached or a cursor that cannot open still answers
 * with an error status. Once the first bytes are out the status is fixed. A failure after that
 * stops the body where it is: the entries array is never closed and `size` never comes, so
 * the body does not parse and no client can take it for a complete answer. Such a
 * failure is logged, counted in `fint.client.list.stream.aborted` and rethrown as
 * [ResourceDumpAbortedException], which the exception handler lets pass without writing.
 *
 * A client that goes away mid-dump is not a failure. The write fails with an I/O error, which
 * is logged at debug and swallowed.
 *
 * Each resource renders through the response mapper, exactly as a returned body does. When OPA
 * is enabled, each resource is pruned to what the request's decision allows before it is
 * written, since a streamed body never passes the response advice. The write runs on the
 * request thread so the mapper can read the request's component.
 */
@Component
class ResourceDumpWriter(
    private val jsonMapper: JsonMapper,
    private val pruner: OpaFieldPruner,
    meterRegistry: MeterRegistry,
) {
    private val active = AtomicInteger()
    private val aborted: Counter = meterRegistry.counter(ABORTED_METRIC)

    init {
        Gauge.builder(ACTIVE_METRIC, active) { it.get().toDouble() }.register(meterRegistry)
    }

    fun write(
        dump: ResourceDump,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val allowed = if (pruner.enabled) pruner.allowedFor(request) else null
        val batches = dump.batches()
        val first = if (batches.hasNext()) batches.next() else emptyList()

        active.incrementAndGet()
        var written = 0
        try {
            response.status = HttpStatus.OK.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            val generator = jsonMapper.createGenerator(response.outputStream)

            generator.writeStartObject()
            generator.writeNumberProperty("offset", dump.offset)
            generator.writeNumberProperty("total_items", max(first.size.toLong(), dump.totalItems))
            generator.writeName("_embedded")
            generator.writeStartObject()
            generator.writeName("_entries")
            generator.writeStartArray()
            written += generator.writeEntries(first, allowed)
            while (batches.hasNext()) written += generator.writeEntries(batches.next(), allowed)
            generator.writeEndArray()
            generator.writeEndObject()
            generator.writeName("_links")
            generator.writePOJO(mapOf("self" to listOf(dump.selfLink)))
            generator.writeNumberProperty("size", written)
            generator.writeEndObject()
            generator.close()
        } catch (e: IOException) {
            logger.debug("Client left during a dump of {} after {} entries: {}", request.requestURI, written, e.message)
        } catch (e: JacksonIOException) {
            logger.debug("Client left during a dump of {} after {} entries: {}", request.requestURI, written, e.message)
        } catch (e: Exception) {
            aborted.increment()
            logger.error("Dump of {} aborted after {} entries", request.requestURI, written, e)
            throw ResourceDumpAbortedException(written, e)
        } finally {
            active.decrementAndGet()
        }
    }

    private fun JsonGenerator.writeEntries(
        resources: List<FintResource>,
        allowed: OpaDecision.Allowed?,
    ): Int {
        resources.forEach { resource ->
            if (allowed == null) {
                writePOJO(resource)
            } else {
                writeTree(jsonMapper.valueToTree<ObjectNode>(resource).also { pruner.pruneResource(it, allowed) })
            }
        }
        return resources.size
    }

    companion object {
        const val ACTIVE_METRIC = "fint.client.list.stream.active"
        const val ABORTED_METRIC = "fint.client.list.stream.aborted"
        private val logger = LoggerFactory.getLogger(ResourceDumpWriter::class.java)
    }
}
