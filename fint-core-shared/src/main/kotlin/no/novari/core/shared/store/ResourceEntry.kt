package no.novari.core.shared.store

import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.resource.FintResource
import org.springframework.data.annotation.Id
import java.time.Instant

data class IdentifierRef(
    val field: String,
    val value: String,
)

data class ResourceEntry(
    @Id val id: String,
    val data: FintResource,
    val identifiers: List<IdentifierRef>,
    val createdAt: Instant,
    val lastModified: Instant,
)

fun FintResource.toIdentifierRefs(): List<IdentifierRef> =
    nonNullIdentifikators().map { (field, identifier) -> IdentifierRef(field, identifier.identifikatorverdi) }
