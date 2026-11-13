package no.novari.core.shared.store

/**
 * What the store did with one write.
 *
 * [NEW] and [CHANGED] stored the content and moved both timestamps. [UNCHANGED] found the same
 * content already stored and only moved `lastDelivered`. [STALE] was skipped because the store
 * held a newer delivery. [DELETED] removed the document, or found nothing to remove.
 */
enum class WriteResult {
    NEW,
    CHANGED,
    UNCHANGED,
    STALE,
    DELETED,
}

/**
 * One write and its [result]. [changedData] is true when the stored content changed, which is
 * when the relation edges the resource owns have to be written again. [identifiersChanged] is
 * true for a changed resource that now answers to other identifiers than the stored one did.
 */
data class WriteOutcome(
    val write: ResourceWrite,
    val result: WriteResult,
    val identifiersChanged: Boolean = false,
) {
    val changedData: Boolean
        get() = result == WriteResult.NEW || result == WriteResult.CHANGED || result == WriteResult.DELETED

    /**
     * True when edges that already point at the resource may not show on it yet. That is a
     * resource stored for the first time, and one that answers to other identifiers than before.
     */
    val reachedByOtherEdges: Boolean
        get() = result == WriteResult.NEW || (result == WriteResult.CHANGED && identifiersChanged)
}
