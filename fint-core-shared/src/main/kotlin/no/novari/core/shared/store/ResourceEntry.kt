package no.novari.core.shared.store

import no.novari.fint.core.model.FintResource
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

/**
 * The two things an eviction needs about a stored resource: which document to delete, and which
 * identifiers other resources may have pointed at. Read as a projection rather than a whole
 * [ResourceEntry], so sweeping a large collection never pulls the resource data into memory.
 */
data class ResourceIdentity(
    @Id val id: String,
    val identifiers: List<IdentifierRef>,
)

fun FintResource.toIdentifierRefs(): List<IdentifierRef> =
    buildList {
        visitIdentifikators { field, value -> add(IdentifierRef(field.lowercase(), value)) }
    }
