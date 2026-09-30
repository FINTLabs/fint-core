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

    val byIdentifier = HashMap<IdentifierRef, BackLinkTarget>()
    page.forEach { (entry, resource) ->
        val target = BackLinkTarget(resource)
        entry.identifiers.forEach { byIdentifier.putIfAbsent(it, target) }
    }

    forEach { edge ->
        byIdentifier[IdentifierRef(edge.targetIdField, edge.targetIdValue)]?.add(edge)
    }
}

/**
 * One resource of the page, with a set per relation name of the links it already has. Two links
 * are the same when the id field matches in any case and the id value matches exactly. The
 * `unresolved` href is not part of that check, so the set holds a [LinkKey] and not the [Link]
 * itself. A set lookup stays fast when one resource has thousands of links under one relation,
 * for example the elevforhold of a large school.
 */
private class BackLinkTarget(
    private val resource: FintResource,
) {
    private val present = HashMap<String, MutableSet<LinkKey>>()

    fun add(edge: RelationEdge) {
        val keys =
            present.getOrPut(edge.inverseName) {
                resource.relationLinks(edge.inverseName).mapNotNullTo(HashSet()) { it.key() }
            }

        if (keys.add(LinkKey(edge.sourceIdField.lowercase(), edge.sourceIdValue))) {
            resource.addLink(edge.inverseName, Link(edge.sourceIdField, edge.sourceIdValue))
        }
    }
}

private data class LinkKey(
    val idField: String,
    val idValue: String,
)

private fun Link.key(): LinkKey? {
    val field = idField ?: return null
    val value = idValue ?: return null
    return LinkKey(field.lowercase(), value)
}
