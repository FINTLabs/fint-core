package no.fintlabs.client.resource

import no.fintlabs.client.resource.paging.PageCursor

/**
 * The query parameters that shape a list read. A live read answers the whole result at once,
 * so it only starts when none of them is given.
 */
data class ListOptions(
    val size: Int = 0,
    val offset: Long = 0,
    val sinceTimeStamp: Long = 0,
    val cursor: PageCursor? = null,
) {
    val given: Boolean get() = size > 0 || offset > 0 || sinceTimeStamp > 0 || cursor != null
}
