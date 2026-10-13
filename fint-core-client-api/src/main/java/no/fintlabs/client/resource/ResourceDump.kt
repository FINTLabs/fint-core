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
 * [totalItems] is the collection count before `$filter`, which is what the paged read reports
 * too. [offset] is echoed from the request, as it always was.
 */
class ResourceDump(
    val totalItems: Long,
    val offset: Long,
    val selfLink: LinkResponse,
    private val batches: Sequence<List<FintResource>>,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun batches(): Iterator<List<FintResource>> = batches.iterator()

    override fun close() = onClose()

    companion object {
        /** A dump of resources already in memory, for tests and callers that have the whole list. */
        fun of(
            totalItems: Long,
            offset: Long,
            selfLink: LinkResponse,
            resources: List<FintResource>,
        ): ResourceDump = ResourceDump(totalItems, offset, selfLink, sequenceOf(resources))
    }
}

/**
 * A dump failed after its first bytes were sent. The status is already on the wire, so nothing
 * can be done for the client; the body stops where it stopped and does not parse. The writer
 * has logged and counted the failure. [entriesWritten] is how far it got.
 */
class ResourceDumpAbortedException(
    val entriesWritten: Int,
    cause: Throwable,
) : RuntimeException("Dump aborted after $entriesWritten entries: ${cause.message}", cause)
