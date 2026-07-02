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
    fun mapLinks(
        resourceRef: ResourceRef,
        resource: FintResource,
    ) {
        resource.links.remove("self") // Delete self links, we will generate correct ones later
        resource.removeInvalidLinks()
        generateCompleteLinks(resourceRef, resource)
        resetSelfLinks(resourceRef, resource)
        resource.nestedResources.forEach {
            mapLinks(it.javaClass.name.toResourceRef(), it)
        }
    }

    /**
     * Generates completed links for each link in the [resource].
     * If the link refers to a common resource, it inherits the [resourceRef] from the resource.
     */
    fun generateCompleteLinks(
        resourceRef: ResourceRef,
        resource: FintResource,
    ) = resourceRef.getMetaResource()?.let { metaResource ->
        resource.links.forEach { (relationName, links) ->
            val className = metaResource.relations.first { it.name == relationName }
            val relationResourceRef = className.packageName.toResourceRef()

            links.forEach { link ->
                val (idField, idValue) = link.href.split("/").takeLast(2)
                if (relationResourceRef.isCommonResource()) {
                    link.setVerdi(resourceRef.toHref(idField, idValue))
                } else {
                    link.setVerdi(relationResourceRef.toHref(idField, idValue))
                }
            }
        }
    } ?: throw RuntimeException("Could not find meta resource for ${resourceRef.toURI()}")

    /**
     * If the resource is a common resource the package name is "felles".
     */
    private fun ResourceRef.isCommonResource() = packageName == "felles"

    /**
     * The String is expected to be a java class name.
     * The [ResourceRef.packageName] could be "felles" if it's a common resource.
     */
    private fun String.toResourceRef() =
        split(".")
            .takeLast(3)
            .let { (domainName, packageName, resourceName) -> ResourceRef(domainName, packageName, resourceName) }

    private fun ResourceRef.getMetaResource() = metamodelService.getResource(domainName, packageName, resourceName)

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
            selfLinks.add(Link.with(resourceRef.toHref(idField, identifikator.identifikatorverdi)))
        }

        resource.links["self"] = selfLinks
    }

    fun deleteSelfLinks() {
    }

    /**
     * Generates an absolute Fint link in lowercase except the idValue.
     * idValue is case-sensetive on look-up, so we don't mutate it.
     */
    fun ResourceRef.toHref(
        idField: String,
        idValue: String,
    ): String = "${properties.baseUrl}/${toURI()}/${idField.lowercase()}/$idValue"

    fun FintResource.removeInvalidLinks() = links.values.forEach { it.retainAll { link -> link.isValid() } }

    fun Link?.isValid() = this != null && !href.isNullOrBlank()
}
