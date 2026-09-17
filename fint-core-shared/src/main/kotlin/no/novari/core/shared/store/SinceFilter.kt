package no.novari.core.shared.store

import java.time.Instant

/**
 * A request's `sinceTimeStamp`, ready for a page read. [since] limits the page to entries
 * modified at or after it. [matches] is how many entries that is, the number the caller already
 * got from [ResourceStore.count] for the same instant. The store only uses it to choose the index
 * the page is read through, so the caller never has to know about indexes.
 */
data class SinceFilter(
    val since: Instant,
    val matches: Long,
)
