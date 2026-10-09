package no.novari.core.shared.model

import no.novari.fint.core.model.FintResourceRef

/**
 * Builds a [FintResourceRef] from names as they arrive in a path, a contract or an event, so
 * two spellings of the same resource always compare equal.
 */
fun resourceRefOf(
    domainName: String,
    packageName: String,
    resourceName: String,
): FintResourceRef =
    FintResourceRef(
        domainName.trim().lowercase(),
        packageName.trim().lowercase(),
        resourceName.trim().lowercase(),
    )
