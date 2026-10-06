package no.novari.core.shared.json

import no.novari.fint.core.model.Link
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.ValueSerializer

/**
 * Writes the `_links` field of a FINT resource in the storage form, sorted: relation names in
 * alphabetical order, and the links under each relation by id field, then id value, then the
 * unresolved href. The same links therefore always give the same JSON, whatever order the
 * adapter sent them in, which the content hash depends on.
 *
 * The resource itself is left as it is; only the written JSON is sorted. Nested resources are
 * written through this serializer too, so they are sorted the same way. The response form does
 * not use it, since [ResponseLinksPropertyWriter] writes `_links` there.
 */
class SortedLinksSerializer : ValueSerializer<Map<String, List<Link>>>() {
    override fun serialize(
        links: Map<String, List<Link>>,
        generator: JsonGenerator,
        context: SerializationContext,
    ) {
        generator.writeStartObject()
        links.toSortedMap().forEach { (relation, relationLinks) ->
            generator.writeName(relation)
            generator.writeStartArray()
            relationLinks.sortedWith(LINK_ORDER).forEach { context.writeValue(generator, it) }
            generator.writeEndArray()
        }
        generator.writeEndObject()
    }

    companion object {
        private val LINK_ORDER =
            compareBy<Link, String?>(nullsFirst()) { it.idField }
                .thenBy(nullsFirst()) { it.idValue }
                .thenBy(nullsFirst()) { it.unresolved }
    }
}
