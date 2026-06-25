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
 */
fun createFintResources(
    baseUrl: String,
    resourceUri: String,
    resources: List<FintResource>,
    offset: Int,
    size: Int,
    totalItems: Int,
): FintResources {
    val selfUrl = "$baseUrl/$resourceUri"
    val builder = UriComponentsBuilder.fromUriString(selfUrl)

    return FintResources(resources).apply {
        if (size > 0) {
            addSelf(createLink(builder, size, offset))
            if (offset > 0) addPrev(createLink(builder, size, calculatePrev(size, offset)))
            if (offset + size < totalItems) createLink(builder, size, calculateNext(size, offset))
        } else {
            addSelf(Link.with(selfUrl))
        }
        this.offset = offset
        this.totalItems = totalItems
    }
}

private fun createLink(
    builder: UriComponentsBuilder,
    size: Int,
    offset: Int,
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
    offset: Int,
) = max(0, offset - size)

// Calculate next offset
private fun calculateNext(
    size: Int,
    offset: Int,
) = offset + size
