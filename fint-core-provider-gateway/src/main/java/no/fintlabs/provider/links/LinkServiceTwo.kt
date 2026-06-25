package no.fintlabs.provider.links

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

}