package no.fintlabs.autorelation

import com.fasterxml.jackson.databind.ObjectMapper
import no.fintlabs.autorelation.model.DeepCopyException
import no.fintlabs.autorelation.model.RelationOperation
import no.fintlabs.autorelation.model.RelationUpdate
import no.novari.fint.model.resource.FintResource
import no.novari.fint.model.resource.Link

// TODO: Deep copy through JSON serialization + deserialization is a super resource intensive anti-pattern

/**
 * Creates a complete deep copy of the FintResource using an explicitly provided target class.
 *
 * @throws DeepCopyException if Jackson serialization or deserialization fails.
 */
fun <T : FintResource> FintResource.deepCopy(
    objectMapper: ObjectMapper,
    clazz: Class<T>,
): T =
    runCatching {
        objectMapper
            .writeValueAsBytes(this)
            .let { objectMapper.readValue(it, clazz) }
    }.getOrElse { throw DeepCopyException(clazz, it) }

/**
 * Compares [this] (new resource) with [oldResource] and returns a map of
 * relation names to links that existed in the old resource but are missing in the new one.
 */
fun FintResource.findObsoleteLinks(
    oldResource: FintResource,
    managedRelations: List<String>,
): Map<String, List<Link>> =
    managedRelations
        .associateWith { relationName ->
            val currentLinks = this.links[relationName] ?: emptyList()
            val oldLinks = oldResource.links[relationName] ?: emptyList()

            oldLinks.filter { oldLink ->
                currentLinks.none { currentLink -> currentLink.isSameResource(oldLink) }
            }
        }.filterValues { it.isNotEmpty() }

/**
 * Checks if two links refer to the same resource by comparing the last two segments of their HREF (idField/idValue).
 * Safe to use even if hrefs are null.
 */
fun Link.isSameResource(other: Link): Boolean {
    if (this.href == other.href) return true
    val mySuffix = this.getIdSuffix() ?: return false
    val otherSuffix = other.getIdSuffix() ?: return false
    return mySuffix.equals(otherSuffix, ignoreCase = true)
}

/**
 * Extracts the last two path segments (idField/idValue) from a link's href.
 */
internal fun Link.getIdSuffix(): String? {
    val segments = (this.href ?: return null).split("/").filter { it.isNotBlank() }
    return if (segments.size >= 2) "${segments[segments.size - 2]}/${segments.last()}" else null
}

/**
 * Mutates this resource by applying the given [relationUpdate] (ADD or DELETE).
 * Returns the resource itself for chaining.
 */
fun FintResource.applyUpdate(relationUpdate: RelationUpdate): FintResource {
    val relation = relationUpdate.binding.relationName
    val link = relationUpdate.binding.link

    when (relationUpdate.operation) {
        RelationOperation.ADD -> addUniqueLinks(relation, listOf(link))
        RelationOperation.DELETE -> removeRelationLink(relation, link)
    }
    return this
}

/**
 * Adds [linksToAttach] to the specified [relation], ensuring no duplicates are created based on idField/idValue.
 */
fun FintResource.addUniqueLinks(
    relation: String,
    linksToAttach: List<Link>,
): FintResource {
    if (linksToAttach.isEmpty()) return this
    val existing = getRelationLinks(relation)
    linksToAttach.forEach { new ->
        if (existing.none { it.isSameResource(new) }) {
            existing.add(new)
        }
    }
    return this
}

/**
 * Removes a specific [link] from the [relation] based on idField/idValue.
 * If the relation list becomes empty after removal, the relation key is removed entirely.
 * Does nothing if the relation does not exist.
 */
fun FintResource.removeRelationLink(
    relation: String,
    link: Link,
) {
    links[relation]?.let { targetList ->
        targetList.removeIf { it.isSameResource(link) }
        if (targetList.isEmpty()) links.remove(relation)
    }
}

/**
 * Retrieves the list of links for the given [relation], creating a new list if none exists.
 */
fun FintResource.getRelationLinks(relation: String): MutableList<Link> = links.getOrPut(relation) { mutableListOf() }
