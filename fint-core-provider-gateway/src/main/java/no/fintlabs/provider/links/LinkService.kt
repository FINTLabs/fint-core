package no.fintlabs.provider.links

import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.model.ResourceRef
import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.FintRelation
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import no.novari.metamodel.MetamodelService
import org.springframework.stereotype.Service

/**
 * Service for performing operations on links within a FintResource.
 * Provides functionality for mapping and validating links, as well as formatting them to relative URIs.
 *
 * The service ensures that links within a FintResource are correctly formatted and valid before they are used or stored.
 */
@Service
class LinkService {
    /**
     * Used once when retrieved from Buffer, to ensure correct links before inserting into database.
     * We also delete self links, as they will be generated upon consumer response.
     */
    fun mapLinks(resource: FintResource) {
        resource.links.remove("self")
        resource.removeInvalidLinks()
        resource.formatLinksToRelativeURI()
        resource.nestedResources.forEach { mapLinks(it) }
    }

    /**
     * Formats the links within the provided FintResource to relative URIs, based on their current href values.
     *
     * The method processes each link in the FintResource's `_links` map, extracting the two last segments
     * from the `href` (assumed to represent an identifier field and value) and updates the link to use
     * a relative format with the structure `idField/idValue`.
     *
     * For example, a link with `href` value `https://example.com/fodselsnummer/123` will be updated to `fodselsnummer/123`.
     */
    fun FintResource.formatLinksToRelativeURI() =
        links.values.flatten().forEach { link ->
            val (idField, idValue) = link.href.split("/").takeLast(2)
            link.setVerdi("$idField/$idValue")
        }

    fun FintResource.removeInvalidLinks() =
        links.entries.removeIf { (_, value) ->
            value.retainAll { link -> link.isValid() }
            value.isEmpty()
        }

    fun Link?.isValid() = this != null && !href.isNullOrBlank() && href.contains("/")
}
