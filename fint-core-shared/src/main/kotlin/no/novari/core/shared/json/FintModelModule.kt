package no.novari.core.shared.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.BeanProperty
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.JsonDeserializer
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.deser.ContextualDeserializer
import com.fasterxml.jackson.databind.module.SimpleModule
import no.novari.fint.core.model.FintModel
import no.novari.fint.core.model.FintObject
import no.novari.fint.core.model.FintRelation
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.FintResourceMetadata
import no.novari.fint.core.model.FintTypeMetadata
import no.novari.fint.core.model.Link
import no.novari.fint.core.model.resolveLink

abstract class FintObjectMixin {
    @get:JsonIgnore
    abstract val metadata: FintTypeMetadata
}

abstract class FintResourceMixin {
    @get:JsonProperty("_links")
    @get:JsonDeserialize(using = FintLinksDeserializer::class)
    abstract val links: MutableMap<String, MutableList<Link>>
}

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
    ): Link = relation?.resolveLink(href) ?: Link(unresolved = href)

    private fun JsonNode.textOrNull(field: String): String? = get(field)?.takeIf { it.isTextual }?.asText()
}

fun resourceMetadataOf(type: Class<*>): FintResourceMetadata? = FintModel.byType(type.kotlin) as? FintResourceMetadata

class FintModelModule : SimpleModule("fint-model") {
    override fun setupModule(context: SetupContext) {
        context.setMixInAnnotations(FintObject::class.java, FintObjectMixin::class.java)
        context.setMixInAnnotations(FintResource::class.java, FintResourceMixin::class.java)
        super.setupModule(context)
    }
}
