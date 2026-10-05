package no.novari.core.shared.event

import no.fintlabs.adapter.models.v2.event.EventOperation

/**
 * One resource and the operations an adapter answers for it, used to pick the pending events a
 * v2 adapter is served.
 */
data class OperationScope(
    val domainName: String,
    val packageName: String,
    val resourceName: String,
    val operations: Set<EventOperation>,
)
