package no.novari.core.shared.event

/**
 * What part of the component tree a pending-event query asks for, mirroring the /event route
 * shapes: a domain, a domain and package, or all three. The hierarchy is enforced here, so a
 * scope with a resource but no package cannot be built and the query layer never has to
 * consider it.
 */
data class EventScope(
    val domainName: String,
    val packageName: String? = null,
    val resourceName: String? = null,
) {
    init {
        require(domainName.isNotBlank()) { "domainName must not be blank" }
        require(resourceName == null || packageName != null) { "resourceName requires packageName" }
    }

    companion object {
        fun of(
            domainName: String,
            packageName: String?,
            resourceName: String?,
        ): EventScope =
            EventScope(
                domainName.normalized().orEmpty(),
                packageName?.normalized(),
                resourceName?.normalized(),
            )

        private fun String.normalized(): String? = trim().lowercase().takeUnless { it.isEmpty() }
    }
}
