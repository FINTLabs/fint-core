package no.novari.core.shared.store

import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.resource.FintResource
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ResourceStore(
    private val template: MongoTemplate,
) {
    fun save(
        resourceId: String,
        resourceCoordinate: ResourceCoordinate,
        resource: FintResource,
        timestamp: Instant,
    ) {
        val identifiers =
            resource.nonNullIdentifikators().map { (field, identifier) ->
                IdentifierRef(field, identifier.identifikatorverdi)
            }

        val resourceEntry =
            ResourceEntry(
                resourceId,
                resource,
                identifiers,
                timestamp,
                timestamp,
            )

        template.save(resourceEntry, resourceCoordinate.toCollectionName().value)
    }
}
