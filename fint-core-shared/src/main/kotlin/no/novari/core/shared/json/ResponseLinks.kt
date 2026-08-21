package no.novari.core.shared.json

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import no.novari.core.shared.uri.LinkCodec
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link

const val LINKS_FIELD = "_links"

/**
 * Supplies the component a common resource is being served through, as the "domain/package" of
 * the request path ("utdanning/elev"), or null when no request is in scope. A common resource —
 * felles:Person and friends — has no path of its own, so its hrefs can only be rendered against
 * the component it was reached through. Resolved lazily per bean, so serialization outside a
 * request stays legal and simply renders such resources without the links that need a component.
 */
typealias ComponentResolver = () -> String?

fun FintResource.toLinkResponses(
    baseUrl: String,
    componentResolver: ComponentResolver = { null },
): Map<String, List<LinkResponse>> {
    val linkMap = LinkedHashMap<String, List<LinkResponse>>()

    selfLinkResponses(baseUrl, componentResolver)
        .takeIf { it.isNotEmpty() }
        ?.let { linkMap["self"] = it }

    linkMap.putAll(relationLinkResponses(baseUrl, componentResolver))

    return linkMap
}

/**
 * Self links are never stored; they are regenerated from whichever id fields carry a value. A
 * common resource takes its path from the resolved component; a resource with neither a path nor
 * a component to borrow, such as a nested Adresse, has nothing to link to.
 */
private fun FintResource.selfLinkResponses(
    baseUrl: String,
    componentResolver: ComponentResolver,
): List<LinkResponse> {
    val path = metadata.path ?: componentResolver()?.let { metadata.pathIn(it) } ?: return emptyList()

    return buildList {
        visitIdentifikators { field, value ->
            add(LinkResponse(Link(field.lowercase(), value).href(baseUrl, path, LinkCodec.encodeIdValue(value))))
        }
    }
}

/**
 * Any stored `self` entry is dropped so it can never beat the generated one. Common targets are
 * resolved against this resource's own path — or against the resolved component when the resource
 * is itself common and has none, as when a served Person links to another Person.
 */
private fun FintResource.relationLinkResponses(
    baseUrl: String,
    componentResolver: ComponentResolver,
): Map<String, List<LinkResponse>> {
    val contextPath = metadata.path ?: componentResolver().orEmpty()

    return links
        .asSequence()
        .filter { (relationName, _) -> relationName != "self" }
        .associate { (relationName, relationLinks) ->
            val targetPath = metadata.relationPath(relationName, contextPath)
            relationName to relationLinks.mapNotNull { it.toLinkResponse(baseUrl, targetPath) }
        }.filterValues { it.isNotEmpty() }
}

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
    componentResolver: ComponentResolver = { null },
) : SimpleModule("fint-response-links") {
    init {
        setSerializerModifier(ResponseLinksSerializerModifier(baseUrl, componentResolver))
    }
}

class ResponseLinksSerializerModifier(
    private val baseUrl: String,
    private val componentResolver: ComponentResolver = { null },
) : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> {
        if (!FintResource::class.java.isAssignableFrom(beanDesc.beanClass)) return beanProperties

        val index = beanProperties.indexOfFirst { it.name == LINKS_FIELD }
        if (index >= 0) {
            beanProperties[index] = ResponseLinksPropertyWriter(beanProperties[index], baseUrl, componentResolver)
        }

        return beanProperties
    }
}

private class ResponseLinksPropertyWriter(
    base: BeanPropertyWriter,
    private val baseUrl: String,
    private val componentResolver: ComponentResolver,
) : BeanPropertyWriter(base) {
    override fun serializeAsField(
        bean: Any,
        generator: JsonGenerator,
        provider: SerializerProvider,
    ) {
        val links = (bean as FintResource).toLinkResponses(baseUrl, componentResolver)
        if (links.isEmpty()) return

        generator.writeFieldName(LINKS_FIELD)
        provider.defaultSerializeValue(links, generator)
    }
}
