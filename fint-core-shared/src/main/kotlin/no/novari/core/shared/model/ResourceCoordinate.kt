package no.novari.core.shared.model

/**
 * A Mongo collection name, wrapped so it can't be confused with the other plain-[String]
 * identifiers (asset id, resource name, org id) flowing through the storage layer.
 */
@JvmInline
value class CollectionName(
    val value: String,
)

/**
 * Fully-qualified, asset-scoped identity of a stored resource type: one asset's slice of a
 * `domain-package-resource`. Maps 1:1 to a physical collection via [toCollectionName].
 */
data class ResourceCoordinate(
    private val assetId: String,
    private val domainName: String,
    private val packageName: String,
    private val resourceName: String,
) {
    init {
        require(assetId == assetId.lowercase()) { "Asset id must be lowercase: $assetId" }
        require(domainName == domainName.lowercase()) { "Domain name must be lowercase: $domainName" }
        require(packageName == packageName.lowercase()) { "Package name must be lowercase: $packageName" }
        require(resourceName == resourceName.lowercase()) { "Resource name must be lowercase: $resourceName" }
    }

    /**
     * Renders this coordinate as its physical collection name,
     * `<assetId>_<domainName>_<packageName>_<resourceName>`.
     * `fintlabs.no_utdanning_vurdering_elevfravar`
     */
    fun toCollectionName(): CollectionName = CollectionName("${assetId}_${domainName}_${packageName}_$resourceName")
}
