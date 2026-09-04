package no.novari.core.shared.json

import no.novari.core.shared.uri.LinkCodec
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResourceMetadata
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.resolveLink
import tools.jackson.core.JsonParser
import tools.jackson.databind.BeanProperty
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ValueDeserializer

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
 * [ValueDeserializer.createContextual] once per `links` property to get an instance that knows the owning
 * resource type. [deserialize] looks up the matching relation by name, and
 * [FintRelation.resolveLink] does the actual matching. If a URL doesn't match any id field on
 * the target, for example because it points to a different system, it is kept as-is via
 * [Link.unresolved].
 */
class FintLinksDeserializer private constructor(
    private val owner: FintResourceMetadata?,
) : ValueDeserializer<MutableMap<String, MutableList<Link>>>() {
    constructor() : this(null)

    override fun createContextual(
        context: DeserializationContext,
        property: BeanProperty?,
    ): ValueDeserializer<*> = FintLinksDeserializer(property?.member?.declaringClass?.let(::resourceMetadataOf))

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
    ): MutableMap<String, MutableList<Link>> {
        val node: JsonNode = context.readTree(parser)
        val links = LinkedHashMap<String, MutableList<Link>>()

        node.properties().forEach { (relationName, entries) ->
            if (!entries.isArray) return@forEach

            val relation = owner?.relation(relationName)
            val resolved = entries.mapNotNull { it.toLink(relation) }
            if (resolved.isNotEmpty()) links[relationName] = resolved.toMutableList()
        }

        return links
    }

    override fun deserialize(
        parser: JsonParser,
        context: DeserializationContext,
        intoValue: MutableMap<String, MutableList<Link>>,
    ): MutableMap<String, MutableList<Link>> =
        intoValue.apply {
            clear()
            putAll(deserialize(parser, context))
        }

    private fun JsonNode.toLink(relation: FintRelation?): Link? {
        if (isNull) return null
        if (isString) return resolve(asString(), relation)

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

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeIf { it.isString }?.asString()
}

private fun resourceMetadataOf(type: Class<*>): FintResourceMetadata? = FintModel.byType(type.kotlin) as? FintResourceMetadata
