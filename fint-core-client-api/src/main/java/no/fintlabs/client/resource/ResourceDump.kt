package no.fintlabs.client.resource

import no.novari.core.shared.json.LinkResponse
import no.novari.fint.core.model.FintResource

/**
 * A list read without `size`: every resource of the collection, or every one modified since a
 * timestamp. The resources are not loaded up front. [batches] yields them a batch at a time,
 * each one read from the store, enriched with its back-links and filtered only when the writer
 * asks for it, so a dump of any size holds one batch in memory. The store cursor behind it stays
 * open until [close] is called.
 *
 * There is no count up front. Each [DumpBatch] says how many entries it read before `$filter`,
 * and the writer adds them up into `total_items` as it goes, so the number is exactly what the
 * cursor yielded. [offset] is echoed from the request, as it always was.
 */
class ResourceDump(
    val offset: Long,
    val selfLink: LinkResponse,
    private val batches: Sequence<DumpBatch>,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun batches(): Iterator<DumpBatch> = batches.iterator()

    override fun close() = onClose()

    companion object {
        /** A dump of resources already in memory, for tests and callers that have the whole list. */
        fun of(
            offset: Long,
            selfLink: LinkResponse,
            resources: List<FintResource>,
        ): ResourceDump = ResourceDump(offset, selfLink, sequenceOf(DumpBatch(resources.size, resources)))
    }
}

/**
 * One batch of a dump. [read] is how many entries came off the cursor, [resources] are the ones
 * left after `$filter`, converted and with their back-links. The two differ only when there is
 * a filter.
 */
data class DumpBatch(
    val read: Int,
    val resources: List<FintResource>,
)

/**
 * A dump failed after its first bytes were sent. The status is already on the wire, so nothing
 * can be done for the client; the body stops where it stopped and does not parse. The writer
 * has logged and counted the failure. [entriesWritten] is how far it got.
 */
class ResourceDumpAbortedException(
    val entriesWritten: Int,
    cause: Throwable,
) : RuntimeException("Dump aborted after $entriesWritten entries: ${cause.message}", cause)
