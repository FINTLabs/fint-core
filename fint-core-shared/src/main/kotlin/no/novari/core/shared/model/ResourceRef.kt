package no.novari.core.shared.model

import no.novari.fint.model.resource.Link

// TODO: replace this with ResourceCoordinate
class ResourceRef(
    val domainName: String, // For example "utdanning"
    val packageName: String, // For example "vurdering"
    val resourceName: String, // For example "fravarsregistrering"
) {
    // For example "utdanning/vurdering/fravarsregistrering"
    fun toURI() = "$domainName/$packageName/$resourceName"
}
