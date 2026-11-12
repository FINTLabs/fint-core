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
 * Writes a [ResourceStream] to the response as NDJSON while it is read, so the collection is
 * never held in memory. Every line is one resource. The last line is a summary with
 * `total_items`, `size` and the self link.
 */
@Component
class ResourceStreamWriter(
    private val jsonMapper: JsonMapper,
    private val pruner: OpaFieldPruner,
    meterRegistry: MeterRegistry,
) {
    // A newline between the values is what makes the body NDJSON
    private val lineWriter = jsonMapper.writer().withRootValueSeparator("\n")
    private val active = AtomicInteger()
    private val aborted: Counter = meterRegistry.counter(ABORTED_METRIC)

    init {
        Gauge.builder(ACTIVE_METRIC, active) { it.get().toDouble() }.register(meterRegistry)
    }

    /** Must run on the request thread, since the mapper reads the request to build self links. */
    fun write(
        stream: ResourceStream,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ) {
        // A streamed body never passes OpaFieldAdvice, so each resource is pruned here
        val allowed = if (pruner.enabled) pruner.allowedFor(request) else null
        val batches = stream.batches()
        // Read before the status is sent, so a store or filter failure still gets an error status
        val first = if (batches.hasNext()) batches.next() else ResourceBatch(0, emptyList())

        active.incrementAndGet()
        val tally = Tally()
        try {
            response.status = HttpStatus.OK.value()
            response.contentType = MediaType.APPLICATION_NDJSON_VALUE
            lineWriter
                .createGenerator(response.outputStream)
                .writeLines(stream, sequenceOf(first) + batches.asSequence(), allowed, tally)
        } catch (e: Exception) {
            when (e) {
                // The client went away, which is not a failure of the read
                is IOException, is JacksonIOException -> {
                    logger.debug(
                        "Client left during a streamed read of {} after {} entries: {}",
                        request.requestURI,
                        tally.written,
                        e.message,
                    )
                }

                // The status is already sent. The body ends without its summary line, which tells the client it is cut off
                else -> {
                    aborted.increment()
                    logger.error("Streamed read of {} aborted after {} entries", request.requestURI, tally.written, e)
                    throw ResourceStreamAbortedException(tally.written, e)
                }
            }
        } finally {
            active.decrementAndGet()
        }
    }

    private fun JsonGenerator.writeLines(
        stream: ResourceStream,
        batches: Sequence<ResourceBatch>,
        allowed: OpaDecision.Allowed?,
        tally: Tally,
    ) {
        for ((read, resources) in batches) {
            resources.forEach { resource ->
                writeEntry(resource, allowed)
                tally.written++
            }
            tally.read += read
        }
        writeStartObject()
        writeNumberProperty("total_items", tally.read)
        writeNumberProperty("size", tally.written)
        writePOJOProperty("_links", mapOf("self" to listOf(stream.selfLink)))
        writeEndObject()
        writeRaw('\n')
        // Closed only on success. Closing after a failure would finish a half written line into one that parses
        close()
    }

    private fun JsonGenerator.writeEntry(
        resource: FintResource,
        allowed: OpaDecision.Allowed?,
    ) = if (allowed == null) {
        writePOJO(resource)
    } else {
        writeTree(jsonMapper.valueToTree<ObjectNode>(resource).also { pruner.pruneResource(it, allowed) })
    }

    /** Entries read off the cursor before `$filter`, and entries written. */
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
