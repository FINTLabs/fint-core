package no.fintlabs.client.resource.event

/**
 * What a live read asks the adapter for. Every resource that matches a filter, or the one
 * resource with a given identifier.
 */
sealed interface ReadRequest {
    data class ByFilter(
        val filter: String,
    ) : ReadRequest

    data class ById(
        val idField: String,
        val idValue: String,
    ) : ReadRequest
}
