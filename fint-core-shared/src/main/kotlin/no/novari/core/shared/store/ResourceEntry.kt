package no.novari.core.shared.store

import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.resource.FintResource
import org.bson.Document
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

data class ResourceEntry(
    @Id val id: String,
    val data: Document,
    val identifiers: List<IdentifierRef>,
    val createdAt: Instant,
    val lastModified: Instant,
)

fun FintResource.toIdentifierRefs(): List<IdentifierRef> =
    nonNullIdentifikators().map { (field, identifier) -> IdentifierRef(field, identifier.identifikatorverdi) }
