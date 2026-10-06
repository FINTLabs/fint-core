package no.novari.core.shared.store

import no.novari.fint.core.model.FintResource
import org.bson.Document
import org.bson.types.Binary
import org.springframework.data.annotation.Id
import java.time.Instant

/**
 * A reference to a single identifier on a FINT resource.
 *
 * A resource can hold several identifiers, each distinguished by its [field] name.
 * [field] is the identifier's name and [value] is the concrete identifier value.
 *
 * @property field the name of the identifier, e.g. `"systemId"`
 * @property value the identifier value, e.g. a UUID
 */
data class IdentifierRef(
    val field: String,
    val value: String,
)

/**
 * A stored resource. [lastModified] is when its content last changed, which is what
 * `sinceTimeStamp` reads. [lastDelivered] is when an adapter last sent it, changed or not, which
 * is what eviction reads. A document written before [lastDelivered] existed has none, and its
 * [lastModified] was the delivery time back then, so the store reads that instead.
 */
data class ResourceEntry(
    @Id val id: String,
    val data: Document,
    val identifiers: List<IdentifierRef>,
    val createdAt: Instant,
    val lastModified: Instant,
    val lastDelivered: Instant? = null,
)

data class ResourceId(
    @Id val id: String,
)

/**
 * What we use to decide if we should write to [ResourceStore] or not. If [contentHash] is equal, we only update the
 * [lastDelivered] field on the related document. Though if [lastDelivered] is older than what already exists, nothing is written.
 *
 * We use [lastModified] as a placeholder for [lastDelivered] in case it does not exist.
 * This is because [lastDelivered] is relatively new and [lastModified] was previously used for filtering and what [lastDelivered]
 * does today.
 */
data class ResourceFingerprint(
    @Id val id: String,
    val lastModified: Instant, // TODO: Can be removed after 21 days (TTL has removed old resources without lastDelivered field)
    val lastDelivered: Instant? = null,
    val contentHash: Binary? = null,
) {
    val delivered: Instant get() = lastDelivered ?: lastModified
}

fun FintResource.toIdentifierRefs(): List<IdentifierRef> =
    buildList {
        visitIdentifikators { field, value -> add(IdentifierRef(field.lowercase(), value)) }
    }
