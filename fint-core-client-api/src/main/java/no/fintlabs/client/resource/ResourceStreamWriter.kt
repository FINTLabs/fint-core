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

/**
 * Writes a [ResourceStream] straight to the response while it is read, so the body leaves the pod
 * batch by batch and the collection is never held in memory. The JSON holds the same fields as a
 * [no.fintlabs.client.resource.dto.FintResourcesResponse]: `offset`, then the entries, the self
 * link, `total_items` and `size`. `total_items` is how many entries the cursor yielded before
 * `$filter` and `size` how many were written, so both are only known at the end and close the
 * body. That puts `total_items` later in the JSON than the page response has it, which no
 * parser cares about, and makes it exact, which clients do care about.
 *
 * The first batch is read, enriched and filtered before anything is written. So a filter that
 * fails to evaluate, a store that cannot be reached or a cursor that cannot open still answers
 * with an error status. Once the first bytes are out the status is fixed. A failure after that
 * stops the body where it is: the entries array is never closed and `total_items` and `size`
 * never come, so the body does not parse and no client can take it for a complete answer. Such a
 * failure is logged, counted in `fint.client.list.stream.aborted` and rethrown as
 * [ResourceStreamAbortedException], which the exception handler lets pass without writing.
 *
 * A client that goes away mid-read is not a failure. The write fails with an I/O error, which
 * is logged at debug and swallowed.
 *
 * Each resource renders through the response mapper, exactly as a returned body does. When OPA
 * is enabled, each resource is pruned to what the request's decision allows before it is
 * written, since a streamed body never passes the response advice. The write runs on the
 * request thread so the mapper can read the request's component.
 */
@Component
class ResourceStreamWriter(
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
        stream: ResourceStream,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        val allowed = if (pruner.enabled) pruner.allowedFor(request) else null
        val batches = stream.batches()
        // Pulled before the status is committed, so a store or filter failure still gets an error status.
        val first = if (batches.hasNext()) batches.next() else ResourceBatch(0, emptyList())

        active.incrementAndGet()
        val tally = Tally()
        try {
            response.status = HttpStatus.OK.value()
            response.contentType = MediaType.APPLICATION_JSON_VALUE
            jsonMapper
                .createGenerator(response.outputStream)
                .writeBody(stream, sequenceOf(first) + batches.asSequence(), allowed, tally)
        } catch (e: IOException) {
            clientLeft(request, tally, e)
        } catch (e: JacksonIOException) {
            clientLeft(request, tally, e)
        } catch (e: Exception) {
            aborted.increment()
            logger.error("Full read of {} aborted after {} entries", request.requestURI, tally.written, e)
            throw ResourceStreamAbortedException(tally.written, e)
        } finally {
            active.decrementAndGet()
        }
    }

    /**
     * The whole body, start to end. The generator is closed only once everything is written: closing
     * it after a failure would close the open array and object too, and leave a body that parses.
     */
    private fun JsonGenerator.writeBody(
        stream: ResourceStream,
        batches: Sequence<ResourceBatch>,
        allowed: OpaDecision.Allowed?,
        tally: Tally,
    ) {
        writeStartObject()
        writeNumberProperty("offset", stream.offset)
        writeObjectPropertyStart("_embedded")
        writeArrayPropertyStart("_entries")
        for (batch in batches) {
            batch.resources.forEach { resource ->
                writeEntry(resource, allowed)
                tally.written++
            }
            tally.read += batch.read
        }
        writeEndArray()
        writeEndObject()
        writePOJOProperty("_links", mapOf("self" to listOf(stream.selfLink)))
        writeNumberProperty("total_items", tally.read)
        writeNumberProperty("size", tally.written)
        writeEndObject()
        close()
    }

    private fun JsonGenerator.writeEntry(
        resource: FintResource,
        allowed: OpaDecision.Allowed?,
    ) {
        if (allowed == null) {
            writePOJO(resource)
        } else {
            writeTree(jsonMapper.valueToTree<ObjectNode>(resource).also { pruner.pruneResource(it, allowed) })
        }
    }

    private fun clientLeft(
        request: HttpServletRequest,
        tally: Tally,
        e: Exception,
    ) {
        logger.debug(
            "Client left during a full read of {} after {} entries: {}",
            request.requestURI,
            tally.written,
            e.message,
        )
    }

    /** How far the body got: entries read off the cursor before `$filter`, and entries written. */
    private class Tally {
        var read = 0L
        var written = 0
    }

    companion object {
        const val ACTIVE_METRIC = "fint.client.list.stream.active"
        const val ABORTED_METRIC = "fint.client.list.stream.aborted"
        private val logger = LoggerFactory.getLogger(ResourceStreamWriter::class.java)
    }
}
