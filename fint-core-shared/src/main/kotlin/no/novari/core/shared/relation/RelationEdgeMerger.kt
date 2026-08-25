package no.novari.core.shared.relation

import no.novari.core.shared.store.IdentifierRef
import no.novari.core.shared.store.ResourceEntry
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link

/**
 * Attaches synthesized back-links onto the resources of one response, in memory, before the
 * response form renders `_links`. Each edge resolves its target through the entry's stored
 * identifiers (both sides lowercase by construction) and lands under the edge's inverse
 * relation name. An edge whose idField/idValue pair is already present under that relation is
 * skipped: some adapters deliver both directions of a relation themselves, and a link the
 * adapter stored on the target must not be rendered a second time by our edge.
 */
fun List<RelationEdge>.mergeInto(page: List<Pair<ResourceEntry, FintResource>>) {
    if (isEmpty()) return

    val byIdentifier = HashMap<IdentifierRef, FintResource>()
    page.forEach { (entry, resource) ->
        entry.identifiers.forEach { byIdentifier.putIfAbsent(it, resource) }
    }

    forEach { edge ->
        byIdentifier[IdentifierRef(edge.targetIdField, edge.targetIdValue)]
            ?.addUniqueLink(edge.inverseName, Link(edge.sourceIdField, edge.sourceIdValue))
    }
}

private fun FintResource.addUniqueLink(
    relationName: String,
    link: Link,
) {
    val alreadyPresent =
        links[relationName]
            ?.any { it.idField.equals(link.idField, ignoreCase = true) && it.idValue == link.idValue }
            ?: false

    if (!alreadyPresent) addLink(relationName, link)
}
