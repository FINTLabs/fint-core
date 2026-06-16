package no.fintlabs.autorelation.model

/**
 * Uniquely identifies a FINT entity type (e.g. utdanning-vurdering-elevfravar).
 */
data class EntityDescriptor(
    val domainName: String,
    val packageName: String,
    val resourceName: String,
) {
    init {
        require(domainName == domainName.lowercase()) { "Domain name must be lowercase: $domainName" }
        require(packageName == packageName.lowercase()) { "Package name must be lowercase: $packageName" }
        require(resourceName == resourceName.lowercase()) { "Resource name must be lowercase: $resourceName" }
    }
}

fun createEntityDescriptor(
    domainName: String,
    packageName: String,
    resourceName: String,
) = EntityDescriptor(domainName.lowercase(), packageName.lowercase(), resourceName.lowercase())

/**
 * Parses a FINT topic string into a [EntityDescriptor].
 *
 * Expects the format `domain-package-resource` (e.g., `"utdanning-vurdering-elevfravar"`).
 * The input is automatically converted to lowercase.
 */
fun String.toEntityDescriptor() =
    split("-")
        .takeIf { it.size == 3 }
        ?.let { (domainName, packageName, resource) -> EntityDescriptor(domainName, packageName, resource) }
        ?: error("Invalid entity format: $this. Expected domain-package-resource")
