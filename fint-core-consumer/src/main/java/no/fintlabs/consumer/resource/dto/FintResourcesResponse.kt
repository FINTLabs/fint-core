package no.fintlabs.consumer.resource.dto

import com.fasterxml.jackson.annotation.JsonProperty
import no.novari.core.shared.json.LinkResponse
import org.springframework.web.util.UriComponentsBuilder
import kotlin.math.max

/**
 * Response type for the get-all-resources endpoint.
 *
 * Deliberately holds no information-model types: the entries are already-serialized resources and
 * the pagination links are plain hrefs, so the response shape is free to differ from the model.
 */
class FintResourcesResponse(
    entries: List<Any>,
    @get:JsonProperty("offset") val offset: Long,
    totalItems: Int,
) {
    data class Embedded(
        @get:JsonProperty("_entries") val entries: List<Any>,
    )

    @get:JsonProperty("_embedded")
    val embedded: Embedded = Embedded(entries)

    @get:JsonProperty("_links")
    val links: MutableMap<String, MutableList<LinkResponse>> = LinkedHashMap()

    @get:JsonProperty("total_items")
    val totalItems: Int = max(entries.size, totalItems)

    @get:JsonProperty("size")
    val size: Int get() = embedded.entries.size

    fun addLink(
        relation: String,
        link: LinkResponse,
    ) {
        links.getOrPut(relation) { mutableListOf() }.add(link)
    }
}

/**
 * Builds the response together with the self, prev and next pagination links.
 *
 * Every link has `offset` and `size`. When the request had a `sinceTimeStamp`, the links keep it,
 * so a client can follow `next` without adding it again. `prev` is only added when `offset > 0`.
 * `next` is only added when `offset + size < totalItems`. When [size] is zero or lower there is no
 * paging, and `self` is the plain resource URL with `sinceTimeStamp` added if the request had one.
 *
 * Example:
 * ```
 * createFintResourcesResponse(
 *     baseUrl = "https://api.felleskomponent.no",
 *     resourceUri = "utdanning/elev/elev",
 *     entries = listOf(elev1, elev2),
 *     offset = 0,
 *     size = 2,
 *     totalItems = 10,
 *     sinceTimeStamp = 1000,
 * )
 * ```
 *
 * produces:
 * - self: `https://api.felleskomponent.no/utdanning/elev/elev?sinceTimeStamp=1000&offset=0&size=2`
 * - next: `https://api.felleskomponent.no/utdanning/elev/elev?sinceTimeStamp=1000&offset=2&size=2`
 */
fun createFintResourcesResponse(
    baseUrl: String,
    resourceUri: String,
    entries: List<Any>,
    offset: Long,
    size: Int,
    totalItems: Int,
    sinceTimeStamp: Long = 0,
): FintResourcesResponse {
    val builder = UriComponentsBuilder.fromUriString("$baseUrl/$resourceUri")
    if (sinceTimeStamp > 0) builder.queryParam("sinceTimeStamp", sinceTimeStamp)

    return FintResourcesResponse(entries, offset, totalItems).apply {
        if (size > 0) {
            addLink("self", pageLink(builder, size, offset))
            if (offset > 0) addLink("prev", pageLink(builder, size, max(0, offset - size)))
            if (offset + size < this.totalItems) addLink("next", pageLink(builder, size, offset + size))
        } else {
            addLink("self", LinkResponse(builder.toUriString()))
        }
    }
}

private fun pageLink(
    builder: UriComponentsBuilder,
    size: Int,
    offset: Long,
) = LinkResponse(
    builder
        .replaceQueryParam("offset", offset)
        .replaceQueryParam("size", size)
        .toUriString(),
)
