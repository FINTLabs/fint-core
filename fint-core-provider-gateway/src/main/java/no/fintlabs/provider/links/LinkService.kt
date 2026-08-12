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
 * LinkServices takes a FintResource and then builds all HATEOAS _links, including self links.
 * For example, we recieve this:
 * {
 *      brukernavn: "Test",
 *      elevnummer: "456",
 *      ...
 *      _links: {
 *          person: [
 *              { href: "fodselsnummer/123" }
 *          ]
 *      }
 * }
 *
 * LinkService will then build a correct FINT url based on the objects in _links
 * Return in this example will be:
 * {
 *      brukernavn: "Test",
 *      elevnummer: "456",
 *      ...
 *      _links: {
 *          person: [
 *              { href: "https://api.felleskomponent.no/utdanning/elev/person/fodselsnummer/123" }
 *          ],
 *          self: [
 *              { href: "https://api.felleskomponent.no/utdanning/elev/elev/elevnummer/456" },
 *              { href: "https://api.felleskomponent.no/utdanning/elev/elev/brukernavn/Test" }
 *          ]
 *      }
 * }
 *
 */
@Service
class LinkService(
    private val properties: ProviderProperties,
    private val metamodelService: MetamodelService,
) {
    /**
     * Used once when retrieved from Buffer, to ensure correct links before inserting into database.
     * We also reset self links because we do not trust they are set correctly on arrival.
     */
    fun mapLinks(resource: FintResource) {
        resource.links.remove("self") // Delete self links, we will generate correct ones later
        resource.removeInvalidLinks()
        formatLinksToRelativeURI(resource)
        resource.nestedResources.forEach {
            mapLinks(it)
        }
    }

    /**
     * Formats the links within the provided FintResource to relative URIs, based on their current href values.
     *
     * The method processes each link in the FintResource's `_links` map, extracting the two last segments
     * from the `href` (assumed to represent an identifier field and value) and updates the link to use
     * a relative format with the structure `idField/idValue`.
     *
     * For example, a link with `href` value `https://example.com/fodselsnummer/123` will be updated to `fodselsnummer/123`.
     *
     * @param resource The FintResource containing the links to be reformatted to relative URIs.
     */
    fun formatLinksToRelativeURI(resource: FintResource) {
        resource.links.values.flatten().forEach {
            val (idField, idValue) = it.href.split("/").takeLast(2)
            it.setVerdi("$idField/$idValue")
        }
    }

    fun FintResource.removeInvalidLinks() =
        links.entries.removeIf { (_, value) ->
            value.retainAll { link -> link.isValid() }
            value.isEmpty()
        }

    fun Link?.isValid() = this != null && !href.isNullOrBlank() && href.contains("/")
}
