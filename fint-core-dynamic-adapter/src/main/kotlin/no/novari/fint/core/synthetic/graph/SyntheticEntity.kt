package no.novari.fint.core.synthetic.graph

/**
 * One generated resource: an id plus its links, kept as target ids per relation name.
 *
 * [links] are always written to the output. [autoRelationLinks] are the back-links that
 * autorelation would create on the provider — they are only written when materializing, so the
 * default output leaves them out and lets autorelation prove itself.
 */
class SyntheticEntity(
    val id: String,
) {
    val links: MutableMap<String, MutableSet<String>> = linkedMapOf()
    val autoRelationLinks: MutableMap<String, MutableSet<String>> = linkedMapOf()

    /** The `_links` value to emit: hrefs in the bare-id form (`systemid/<id>`). */
    fun emittedLinks(materializeBackLinks: Boolean): Map<String, List<Map<String, String>>> {
        val emitted = linkedMapOf<String, MutableSet<String>>()
        links.forEach { (relation, ids) -> emitted.getOrPut(relation) { linkedSetOf() } += ids }
        if (materializeBackLinks) {
            autoRelationLinks.forEach { (relation, ids) -> emitted.getOrPut(relation) { linkedSetOf() } += ids }
        }
        return emitted.mapValues { (_, ids) -> ids.map { mapOf("href" to "systemid/$it") } }
    }
}
