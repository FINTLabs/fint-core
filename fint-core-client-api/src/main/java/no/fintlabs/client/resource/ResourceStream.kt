package no.fintlabs.client.resource

import no.novari.core.shared.json.LinkResponse
import no.novari.fint.core.model.FintResource

/**
 * A list read without `size` that is read while it is written. Each batch is fetched from the
 * store, given its back-links and filtered when it is asked for, not before. The store cursor
 * opens with the first batch and stays open until [close] is called.
 */
class ResourceStream(
    val offset: Long,
    val selfLink: LinkResponse,
    private val batches: Sequence<ResourceBatch>,
    private val onClose: () -> Unit = {},
) : AutoCloseable {
    fun batches(): Iterator<ResourceBatch> = batches.iterator()

    override fun close() = onClose()

    companion object {
        /** A stream of resources already in memory, for tests and callers that have the whole list. */
        fun of(
            offset: Long,
            selfLink: LinkResponse,
            resources: List<FintResource>,
        ): ResourceStream = ResourceStream(offset, selfLink, sequenceOf(ResourceBatch(resources.size, resources)))
    }
}

/** [read] is how many entries came off the cursor, [resources] are the ones left after `$filter`. */
data class ResourceBatch(
    val read: Int,
    val resources: List<FintResource>,
)
