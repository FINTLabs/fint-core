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
 * The single reader for every `_links` field that enters the platform. Whatever a resource
 * arrives as, each entry lands as the same id-based [Link], so storage and everything behind it
 * never see an href again. Three entry shapes are accepted:
 *
 * - `"https://…/elev/systemid/123"`: a bare href, as adapters send them,
 * - `{"href": "https://…"}`: the same href wrapped in the old platform's link object,
 * - `{"idField": …, "idValue": …}` / `{"unresolved": …}`: our own storage form, read back
 *   from Mongo and the provider's buffer topic.
 *
 * An href can only be resolved against the id fields of the resource it points *to*, and which
 * resource that is depends on which relation of the *owning* resource the entry sits under.
 * Hence [ContextualDeserializer]: Jackson builds the no-arg instance from the mixin annotation,
 * then [createContextual] replaces it per `links` property with one that has captured the
 * declaring class's metadata. [deserialize] looks the relation up by entry name and
 * [FintRelation.resolveLink] does the metadata-driven split. An href naming no id field of the
 * target (a foreign host, a target without a matching id field) is kept verbatim as
 * [Link.unresolved], never discarded.
 *
 * Decoding happens here and not in the model library, on purpose: the model does the structural
 * split and holds no codec (see [LinkCodec]), because what an ingress has already decoded is
 * platform knowledge. Storage holds decoded values; [ResponseLinksModule] re-encodes on the way
 * out.
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

private fun resourceMetadataOf(type: Class<*>): FintResourceMetadata? =
    FintModel.byType(type.kotlin) as? FintResourceMetadata
