package no.fintlabs.consumer.resource.links

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import no.fintlabs.consumer.resource.dto.LinkResponse
import no.novari.core.shared.uri.LinkCodec
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link

const val LINKS_FIELD = "_links"

fun FintResource.toLinkResponses(baseUrl: String): Map<String, List<LinkResponse>> {
    val linkMap = LinkedHashMap<String, List<LinkResponse>>()

    selfLinkResponses(baseUrl)
        .takeIf { it.isNotEmpty() }
        ?.let { linkMap["self"] = it }

    linkMap.putAll(relationLinkResponses(baseUrl))

    return linkMap
}

/**
 * Self links are never stored; they are regenerated from whichever id fields carry a value. A
 * resource without its own path, such as a nested Adresse, has nothing to link to.
 */
private fun FintResource.selfLinkResponses(baseUrl: String): List<LinkResponse> {
    val path = metadata.path ?: return emptyList()

    return buildList {
        visitIdentifikators { field, value ->
            add(LinkResponse(Link(field.lowercase(), value).href(baseUrl, path, LinkCodec.encodeIdValue(value))))
        }
    }
}

/**
 * Any stored `self` entry is dropped so it can never beat the generated one.
 */
private fun FintResource.relationLinkResponses(baseUrl: String): Map<String, List<LinkResponse>> =
    links
        .asSequence()
        .filter { (relationName, _) -> relationName != "self" }
        .associate { (relationName, relationLinks) ->
            val targetPath = metadata.relationPath(relationName)
            relationName to relationLinks.mapNotNull { it.toLinkResponse(baseUrl, targetPath) }
        }.filterValues { it.isNotEmpty() }

private fun Link.toLinkResponse(
    baseUrl: String,
    targetPath: String?,
): LinkResponse? {
    unresolved?.let { return LinkResponse(it) }
    val path = targetPath ?: return null

    return LinkResponse(href(baseUrl, path, LinkCodec.encodeIdValue(idValue.orEmpty())))
}

class ResponseLinksModule(
    baseUrl: String,
) : SimpleModule("fint-response-links") {
    init {
        setSerializerModifier(ResponseLinksSerializerModifier(baseUrl))
    }
}

class ResponseLinksSerializerModifier(
    private val baseUrl: String,
) : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> {
        if (!FintResource::class.java.isAssignableFrom(beanDesc.beanClass)) return beanProperties

        val index = beanProperties.indexOfFirst { it.name == LINKS_FIELD }
        if (index >= 0) beanProperties[index] = ResponseLinksPropertyWriter(beanProperties[index], baseUrl)

        return beanProperties
    }
}

private class ResponseLinksPropertyWriter(
    base: BeanPropertyWriter,
    private val baseUrl: String,
) : BeanPropertyWriter(base) {
    override fun serializeAsField(
        bean: Any,
        generator: JsonGenerator,
        provider: SerializerProvider,
    ) {
        val links = (bean as FintResource).toLinkResponses(baseUrl)
        if (links.isEmpty()) return

        generator.writeFieldName(LINKS_FIELD)
        provider.defaultSerializeValue(links, generator)
    }
}
