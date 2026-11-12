package no.fintlabs.client.resource

import no.novari.core.shared.json.LinkResponse
import no.novari.fint.core.model.FintResource

/**
 * A list read without `size` that is read while it is consumed. Each batch is fetched from the
 * store, given its back-links and filtered when it is asked for, not before. The store cursor
 * stays open until [close] is called.
 */
class ResourceStream(
    val selfLink: LinkResponse,
    private val batches: Sequence<ResourceBatch>,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun batches(): Iterator<ResourceBatch> = batches.iterator()

    override fun close() = onClose()
}

/** [read] is how many entries came off the cursor, [resources] are the ones left after `$filter`. */
data class ResourceBatch(
    val read: Int,
    val resources: List<FintResource>,
)

/** A streamed read failed after its first bytes were sent. [entriesWritten] is how far it got. */
class ResourceStreamAbortedException(
    val entriesWritten: Int,
    cause: Throwable,
) : RuntimeException("Stream aborted after $entriesWritten entries: ${cause.message}", cause)
