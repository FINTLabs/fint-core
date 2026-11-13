package no.fintlabs.client.resource

import io.micrometer.core.instrument.Counter
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import jakarta.servlet.http.HttpServletRequest
import no.fintlabs.client.config.AbortedResponseValve
import no.fintlabs.client.security.OpaFieldPruner
import no.fintlabs.client.security.opa.OpaDecision
import no.novari.fint.core.model.FintResource
import org.slf4j.LoggerFactory
import org.springframework.http.HttpInputMessage
import org.springframework.http.HttpOutputMessage
import org.springframework.http.MediaType
import org.springframework.http.converter.AbstractHttpMessageConverter
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.util.StreamUtils
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import tools.jackson.core.JsonGenerator
import tools.jackson.core.exc.JacksonIOException
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Writes a [ResourceStream] to the response while it is read, so the collection is never held in
 * memory. It is a message converter, so Spring picks the content type from the `Accept` header
 * the same way it does for a page.
 *
 * The body has the fields of a [no.fintlabs.client.resource.dto.FintResourcesResponse].
 * `total_items` and `size` are only known when the cursor is empty, so they come after the entries.
 */
@Component
class ResourceStreamWriter(
    private val jsonMapper: JsonMapper,
    private val pruner: OpaFieldPruner,
    meterRegistry: MeterRegistry,
    // The types the JSON converter writes, so a full read and a page negotiate the same content type
) : AbstractHttpMessageConverter<ResourceStream>(MediaType.APPLICATION_JSON, MediaType("application", "*+json")) {
    private val active = AtomicInteger()
    private val aborted: Counter = meterRegistry.counter(ABORTED_METRIC)

    init {
        Gauge.builder(ACTIVE_METRIC, active) { it.get().toDouble() }.register(meterRegistry)
    }

    override fun supports(clazz: Class<*>): Boolean = ResourceStream::class.java.isAssignableFrom(clazz)

    override fun canRead(
        clazz: Class<*>,
        mediaType: MediaType?,
    ): Boolean = false

    override fun readInternal(
        clazz: Class<out ResourceStream>,
        inputMessage: HttpInputMessage,
    ): ResourceStream = throw UnsupportedOperationException("A resource stream is only written")

    /** Runs on the request thread, since the mapper reads the request to build self links. */
    override fun writeInternal(
        stream: ResourceStream,
        outputMessage: HttpOutputMessage,
    ) {
        val request = (RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes).request
        active.incrementAndGet()
        val tally = Tally()
        try {
            // OpaFieldAdvice passes a stream on untouched, so each resource is pruned here
            val allowed = if (pruner.enabled) pruner.allowedFor(request) else null
            val batches = stream.batches()
            // Read before anything is written, so a store or filter failure still gets an error status
            val first = if (batches.hasNext()) batches.next() else ResourceBatch(0, emptyList())
            try {
                jsonMapper
                    // The generator is closed to flush it. The response itself is closed by Spring
                    .createGenerator(StreamUtils.nonClosing(outputMessage.body))
                    .writeBody(stream, sequenceOf(first) + batches.asSequence(), allowed, tally)
            } catch (e: Exception) {
                when {
                    // The client went away, which is not a failure of the read
                    e is IOException || e is JacksonIOException -> {
                        log.debug(
                            "Client left during a full read of {} after {} entries: {}",
                            request.requestURI,
                            tally.written,
                            e.message,
                        )
                    }

                    // Nothing has left the pod yet, so the failure can still get an error status
                    !outputMessage.isCommitted() -> {
                        throw e
                    }

                    else -> {
                        abort(request, tally, e)
                    }
                }
            }
        } finally {
            stream.close()
            active.decrementAndGet()
        }
    }

    /**
     * The status is already sent, so the failure cannot be reported with one. The request is marked
     * for [AbortedResponseValve], which drops the connection so the client gets a network error.
     */
    private fun abort(
        request: HttpServletRequest,
        tally: Tally,
        cause: Exception,
    ) {
        aborted.increment()
        log.error("Full read of {} aborted after {} entries", request.requestURI, tally.written, cause)
        // Not thrown. Tomcat drops the connection for a thrown failure too, but Spring Boot first
        // appends its error document to the body
        request.setAttribute(AbortedResponseValve.ABORTED_ATTRIBUTE, cause)
    }

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
        for ((read, resources) in batches) {
            resources.forEach { resource ->
                writeEntry(resource, allowed)
                tally.written++
            }
            tally.read += read
        }
        writeEndArray()
        writeEndObject()
        writePOJOProperty("_links", mapOf("self" to listOf(stream.selfLink)))
        writeNumberProperty("total_items", tally.read)
        writeNumberProperty("size", tally.written)
        writeEndObject()
        // Closed only on success. Closing after a failure would end the open array and object and leave a body that parses
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

    private fun HttpOutputMessage.isCommitted(): Boolean =
        (this as? ServletServerHttpResponse)?.servletResponse?.isCommitted ?: true

    /** Entries read off the cursor before `$filter`, and entries written. */
    private class Tally {
        var read = 0L
        var written = 0
    }

    companion object {
        const val ACTIVE_METRIC = "fint.client.list.stream.active"
        const val ABORTED_METRIC = "fint.client.list.stream.aborted"
        private val log = LoggerFactory.getLogger(ResourceStreamWriter::class.java)
    }
}
