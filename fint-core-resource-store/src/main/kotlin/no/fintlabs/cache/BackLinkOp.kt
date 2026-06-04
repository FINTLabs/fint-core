package no.fintlabs.cache

import no.novari.fint.model.resource.Link

/**
 * A single back-link change to apply to a target document, used by [FintCache.applyBackLinkOps] to
 * reconcile a whole sync page's relations in one bulk write per target collection.
 */
sealed interface BackLinkOp {
    val resourceId: String

    data class Add(
        override val resourceId: String,
        val relation: String,
        val link: Link,
    ) : BackLinkOp

    data class Remove(
        override val resourceId: String,
        val relation: String,
        val ref: String,
    ) : BackLinkOp
}
