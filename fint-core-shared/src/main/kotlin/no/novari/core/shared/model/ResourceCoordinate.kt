package no.novari.core.shared.model

import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.FintResourceRef

/**
 * ResourceCoordinate is mostly used to keep information needed to generate the CollectionName when we
 * are retrieving from the database. It is usally created with the parameters that we receive in the controllers.
 *
 * For example, if someone from fintlabs want to retrieve a Fravar. The resulting CollectionName is
 * fintlabs_no_utdanning_vurdering_fravar
 */
data class ResourceCoordinate(
    val orgId: String,
    val domainName: String,
    val packageName: String,
    val resourceName: String,
) {
    init {
        require(orgId == orgId.lowercase()) { "Asset id must be lowercase: $orgId" }
        require(domainName == domainName.lowercase()) { "Domain name must be lowercase: $domainName" }
        require(packageName == packageName.lowercase()) { "Package name must be lowercase: $packageName" }
        require(resourceName == resourceName.lowercase()) { "Resource name must be lowercase: $resourceName" }
    }

    /**
     * Renders this coordinate as its physical collection name,
     * `<assetId>_<domainName>_<packageName>_<resourceName>`.
     * `fintlabs.no_utdanning_vurdering_elevfravar`
     */
    fun toCollectionName(): String = "${orgId.replace(".","_")}_${domainName}_${packageName}_$resourceName"

    fun toResourceUri(): String = "$domainName/$packageName/$resourceName"

    fun toRescourceRef(): FintResourceRef = FintResourceRef(domainName, packageName, resourceName)
}

fun ResourceCoordinate.toResourceClass(): Class<out FintResource> =
    FintModel
        .byPath(domainName, packageName, resourceName)
        ?.type
        ?.java
        ?: throw IllegalArgumentException("Unknown resource: $this")
