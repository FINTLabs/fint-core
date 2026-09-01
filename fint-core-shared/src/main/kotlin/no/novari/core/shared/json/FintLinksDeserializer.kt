package no.novari.core.shared.json

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import no.novari.core.shared.uri.LinkCodec
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResourceMetadata
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.resolveLink

/**
 * Reads the `_links` field of a FINT resource and turns each entry into a [Link].
 *
 * A `_links` entry can be written three different ways, depending on where it came from:
 *
 * - a plain URL, like `"https://.../elev/systemid/123"`
 * - `{"href": "https://..."}`, an older format that wraps the URL in an object
 * - `{"idField": ..., "idValue": ...}` or `{"unresolved": ...}`, our own format for a
 *   link that has already been read back from storage
 *
 * All three end up as the same [Link] object, so nothing downstream has to deal with URLs.
 *
 * To turn a URL into a [Link], we need to know which field of the *target* resource its id
 * belongs to, and that depends on which relation the link is under. Different resource types
 * have different relations, so this class implements [ContextualDeserializer] to find out which
 * resource it is currently working on: Jackson first creates a plain instance, then calls
 * [createContextual] once per `links` property to get an instance that knows the owning
 * resource type. [deserialize] looks up the matching relation by name, and
 * [FintRelation.resolveLink] does the actual matching. If a URL doesn't match any id field on
 * the target, for example because it points to a different system, it is kept as-is via
 * [Link.unresolved].
 */
class FintLinksDeserializer private constructor(
    private val owner: FintResourceMetadata?,
) : JsonDeserializer<MutableMap<String, MutableList<Link>>>(),
    ContextualDeserializer {
    constructor() : this(null)

    override fun createContextual(
        context: DeserializationContext,
        property: BeanProperty?,
    ): JsonDeserializer<*> = FintLinksDeserializer(property?.member?.declaringClass?.let(::resourceMetadataOf))

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): MutableMap<String, MutableList<Link>> {
        val node: JsonNode = parser.codec.readTree(parser)
        val links = LinkedHashMap<String, MutableList<Link>>()

        node.fields().forEach { (relationName, entries) ->
            if (!entries.isArray) return@forEach

            val relation = owner?.relation(relationName)
            val resolved = entries.mapNotNull { it.toLink(relation) }
            if (resolved.isNotEmpty()) links[relationName] = resolved.toMutableList()
        }

        return links
    }

    private fun JsonNode.toLink(relation: FintRelation?): Link? {
        if (isNull) return null
        if (isTextual) return resolve(asText(), relation)

        val href = textOrNull("href")
        if (href != null) return resolve(href, relation)

        val idField = textOrNull("idField")
        val idValue = textOrNull("idValue")
        val unresolved = textOrNull("unresolved")
        if (idField == null && idValue == null && unresolved == null) return null

        return Link(idField = idField, idValue = idValue, unresolved = unresolved)
    }

    private fun resolve(
        href: String,
        relation: FintRelation?,
    ): Link {
        val link = relation?.resolveLink(href) ?: return Link(unresolved = href)
        val idValue = link.idValue ?: return link
        return link.copy(idValue = LinkCodec.decodeIdValue(idValue))
    }

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()
}

private fun resourceMetadataOf(type: Class<*>): FintResourceMetadata? = FintModel.byType(type.kotlin) as? FintResourceMetadata
