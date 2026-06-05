package no.novari.fint.core.shared.resource

/**
 * Identifies a resource by its full component coordinates: `(domain, package, resource)`.
 *
 * Under the per-org model a single service hosts every component, and resource names are not
 * globally unique (`person` exists in several components). Anything that keys a resource — the
 * reflection/context map, the Mongo cache collection, the per-resource write lock — must use this
 * qualified identity via [key], not the bare resource name. [name] is kept for the few places that
 * need the unqualified name (HATEOAS links, metric tags, relation URIs).
 */
data class ResourceRef(
    val domain: String,
    val packageName: String,
    val name: String,
) {
    /** Stable, collection-safe qualified key, e.g. `utdanning_vurdering_sluttvurdering`. */
    val key: String = keyOf(domain, packageName, name)

    /** Component path segment used in HATEOAS hrefs, e.g. `utdanning/vurdering`. */
    val componentPath: String = "${domain.lowercase()}/${packageName.lowercase()}"

    companion object {
        const val DELIMITER = "_"

        /** Java-friendly key builder shared with the reflection/context layer. */
        @JvmStatic
        fun keyOf(
            domain: String,
            packageName: String,
            name: String,
        ): String = "${domain.lowercase()}$DELIMITER${packageName.lowercase()}$DELIMITER${name.lowercase()}"

        fun of(
            domain: String,
            packageName: String,
            resource: String,
        ): ResourceRef = ResourceRef(domain.lowercase(), packageName.lowercase(), resource.lowercase())

        /**
         * Rebuild a [ResourceRef] from its [key]. FINT domain/package/resource names are single
         * lowercase tokens without underscores, so a 3-way split is unambiguous.
         */
        @JvmStatic
        fun fromKey(key: String): ResourceRef {
            val parts = key.split(DELIMITER, limit = 3)
            require(parts.size == 3) { "Invalid resource key: $key" }
            return ResourceRef(parts[0], parts[1], parts[2])
        }
    }
}
