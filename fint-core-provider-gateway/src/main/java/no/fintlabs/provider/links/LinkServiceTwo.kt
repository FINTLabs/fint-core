package no.fintlabs.provider.links

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
class LinkServiceTwo {

    fun mapLinks(resource: FintResource) {
        // Remove in case there is already a self link
        resource.links.remove("self")
        // Fjern invalid links
        resource.removeInvalidLinks()

        // Generer ufullstendige links


        // Relative lenker, kun id-felt og verdi
        // Korrekt fullstendig lenke, må valideres
    }

    fun FintResource.removeInvalidLinks() = links.values.forEach { it.retainAll { link -> link.isValid()} }

    fun Link?.isValid() = this != null && !href.isNullOrBlank()

}