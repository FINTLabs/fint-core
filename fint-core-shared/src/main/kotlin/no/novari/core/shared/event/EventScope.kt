package no.novari.core.shared.event

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
