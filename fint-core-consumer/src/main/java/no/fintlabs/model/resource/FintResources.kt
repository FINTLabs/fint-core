package no.fintlabs.model.resource

import com.fasterxml.jackson.core.type.TypeReference
import no.novari.fint.model.resource.AbstractCollectionResources
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link
import org.springframework.web.util.UriComponentsBuilder
import kotlin.math.max

/**
 * Response type for get all resources endpoint
 */
class FintResources : AbstractCollectionResources<FintResource> {
    constructor(input: List<FintResource>) : super(input)

    constructor()

    override fun getTypeReference(): TypeReference<MutableList<FintResource?>?>? {
        return null
    }
}

/**
 * This ensures that all links needed for pagination purposes are present.
 * Calculates Links for self, prev and next.
 * Sets offset and totalItems.
 *
 * Example:
 * ```
 * createFintResources(
 *     baseUrl = "https://api.felleskomponent.no",
 *     resourceUri = "utdanning/elev/elev",
 *     resources = listOf(elevResource1, elevResource2),
 *     offset = 0,
 *     size = 2,
 *     totalItems = 10,
 * )
 * ```
 *
 * This produces pagination links based on:
 * - self: `https://api.felleskomponent.no/utdanning/elev/elev?offset=0&size=2`
 * - next: `https://api.felleskomponent.no/utdanning/elev/elev?offset=2&size=2`
 */
fun createFintResources(
    baseUrl: String,
    resourceUri: String,
    resources: List<FintResource>,
    offset: Long,
    size: Int,
    totalItems: Int,
): FintResources {
    val selfUrl = "$baseUrl/$resourceUri"
    val builder = UriComponentsBuilder.fromUriString(selfUrl)

    return FintResources(resources).apply {
        if (size > 0) {
            addSelf(createLink(builder, size, offset))
            if (offset > 0) addPrev(createLink(builder, size, calculatePrev(size, offset)))
            if (offset + size < totalItems) addNext(createLink(builder, size, calculateNext(size, offset)))
        } else {
            addSelf(Link.with(selfUrl))
        }
        this.offset = offset.toInt()
        this.totalItems = totalItems
    }
}

private fun createLink(
    builder: UriComponentsBuilder,
    size: Int,
    offset: Long,
): Link {
    return Link.with(
        builder
            .replaceQueryParam("offset", offset)
            .replaceQueryParam("size", size)
            .toUriString(),
    )
}

// Calculate previous offset
private fun calculatePrev(
    size: Int,
    offset: Long,
) = max(0, offset - size)

// Calculate next offset
private fun calculateNext(
    size: Int,
    offset: Long,
) = offset + size
