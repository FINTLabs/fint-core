package no.fintlabs.consumer.integration

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

data class FintResourcesPage(
    @get:JsonProperty("_embedded")
    val embedded: EmbeddedResources,
    @get:JsonProperty("_links")
    val links: MutableMap<String, MutableList<Link>>,
    val totalItems: Int,
    val offset: Int,
    val size: Int,
) {
    data class EmbeddedResources(
        @get:JsonProperty("_entries")
        val entries: List<JsonNode>,
    )

    fun <T> getResources(
        objectMapper: ObjectMapper,
        resourceType: Class<T>,
    ): List<T> =
        embedded.entries.map {
            objectMapper.treeToValue(it, resourceType)
        }
}
