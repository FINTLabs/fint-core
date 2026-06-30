package no.fintlabs.provider.links

import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.model.ResourceRef
import no.novari.core.shared.nonNullIdentifikators
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

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
class LinkService(
    private val properties: ProviderProperties,
) {
    /**
     * Used only once per resource on resource arrival.
     * We also reset self links because we do not trust they are set correctly on arrival.
     */
    fun mapLinks(
        resourceRef: ResourceRef,
        resource: FintResource,
    ) {
        // Reset self links
        resetSelfLinks(resourceRef, resource)

        // Fjern invalid links
        resource.removeInvalidLinks()

        // Generer ufullstendige links

        // Relative lenker, kun id-felt og verdi
        // Korrekt fullstendig lenke, må valideres
    }

    /**
     * Iterate over non-null identifiers of a resource and populate those in self links.
     *
     * For example, we recieve this Elev resource:
     * {
     *      brukernavn: "Test",
     *      elevnummer: "456",
     *      ...
     *      _links: {
     *          self: [
     *              { href: "http:///Invalid-link.com/123/abc" }
     *          ]
     *      }
     * }
     *
     * Then we iterate over the identifikators that are not null, brukernavn and elevnummer in this case and generate this:
     *
     * {
     *      brukernavn: "Test",
     *      elevnummer: "456",
     *      ...
     *      _links: {
     *          self: [
     *              { href: "https://api.felleskomponent.no/utdanning/elev/elev/brukernavn/Test" },
     *              { href: "https://api.felleskomponent.no/utdanning/elev/elev/elevnummer/456" },
     *          ]
     *      }
     * }
     *
     */
    fun resetSelfLinks(
        resourceRef: ResourceRef,
        resource: FintResource,
    ) {
        val selfLinks = mutableListOf<Link>()

        // https://api.felleskomponent.no/<domain>/<package>/<resource>/idField/idValue
        resource.nonNullIdentifikators().entries.forEach { (idField, identifikator) ->
            selfLinks.add(resourceRef.toLink(idField, identifikator.identifikatorverdi))
        }

        resource.links["self"] = selfLinks
    }

    /**
     * Generates an absolute Fint link in lowercase except the idValue.
     * idValue is case-sensetive on look-up, so we don't mutate it.
     */
    fun ResourceRef.toLink(
        idField: String,
        idValue: String,
    ): Link = Link.with("${properties.baseUrl}/$domainName/$packageName/$resourceName/${idField.lowercase()}/$idValue")

    fun FintResource.removeInvalidLinks() = links.values.forEach { it.retainAll { link -> link.isValid() } }

    fun Link?.isValid() = this != null && !href.isNullOrBlank()
}
