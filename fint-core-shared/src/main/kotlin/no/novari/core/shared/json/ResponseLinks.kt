package no.novari.core.shared.json

import no.novari.core.shared.uri.LinkCodec
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link
import tools.jackson.core.JsonGenerator
import tools.jackson.databind.BeanDescription
import tools.jackson.databind.SerializationConfig
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.module.SimpleModule
import tools.jackson.databind.ser.BeanPropertyWriter
import tools.jackson.databind.ser.ValueSerializerModifier

const val LINKS_FIELD = "_links"

/**
 * Returns the domain/package part of the current request path, such as "utdanning/elev", or
 * null if there is no request. Some resources (felles:Person and similar) don't have a path of
 * their own, so we need to know which path they were requested through in order to build their
 * links.
 */
typealias ComponentResolver = () -> String?

/**
 * Turns the links stored on a resource into the `_links` map that gets sent in the response:
 * a freshly built `self` link first, followed by every other stored link written out as a full
 * URL. [ResponseLinksPropertyWriter] calls this while writing the JSON response; tests can also
 * call it directly.
 */
fun FintResource.toLinkResponses(
    baseUrl: String,
    componentResolver: ComponentResolver = { null },
): Map<String, List<LinkResponse>> {
    val linkMap = LinkedHashMap<String, List<LinkResponse>>()

    selfLinkResponses(baseUrl, componentResolver)
        .takeIf { it.isNotEmpty() }
        ?.let { linkMap[FintResource.SELF] = it }

    linkMap.putAll(relationLinkResponses(baseUrl, componentResolver))

    return linkMap
}

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
 * Builds every link other than `self`. Any stored `self` entry is skipped, so it never overrides
 * the one built by [selfLinkResponses]. When the target of a link has no path of its own, this
 * falls back to the resolved component, for example when a Person resource links to another
 * Person.
 */
private fun FintResource.relationLinkResponses(
    baseUrl: String,
    componentResolver: ComponentResolver,
): Map<String, List<LinkResponse>> {
    val contextPath = metadata.path ?: componentResolver().orEmpty()

    return links
        .asSequence()
        .filter { (relationName, _) -> relationName != FintResource.SELF }
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

/**
 * Step 1: registration. Adding this module to a Jackson `JsonMapper` is what turns it into the
 * mapper used for responses; [FintJson.responseMapper] is the only place that does this. It works
 * by registering the [ResponseLinksSerializerModifier] below.
 */
class ResponseLinksModule(
    baseUrl: String,
    componentResolver: ComponentResolver = { null },
) : SimpleModule("fint-response-links") {
    init {
        setSerializerModifier(ResponseLinksSerializerModifier(baseUrl, componentResolver))
    }
}

/**
 * Step 2: the hook. Jackson calls this once for each class it needs to serialize, the first time
 * it needs to, not on every request. For any [FintResource] class, it replaces the normal
 * `_links` field writer with [ResponseLinksPropertyWriter]. Everything that isn't a
 * [FintResource] is left alone. This way every resource type is covered without registering
 * anything per type.
 */
class ResponseLinksSerializerModifier(
    private val baseUrl: String,
    private val componentResolver: ComponentResolver = { null },
) : ValueSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription.Supplier,
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

/**
 * Step 3: the write. As far as Jackson is concerned, this *is* the resource's `_links` field, so
 * it runs every time a resource is written to JSON, whether at the top level or nested inside
 * another resource. It writes the result of [toLinkResponses] instead of the stored links, and
 * if that result is empty, it writes nothing at all rather than an empty `_links: {}`.
 */
private class ResponseLinksPropertyWriter(
    base: BeanPropertyWriter,
    private val baseUrl: String,
    private val componentResolver: ComponentResolver,
) : BeanPropertyWriter(base) {
    override fun serializeAsProperty(
        bean: Any,
        generator: JsonGenerator,
        context: SerializationContext,
    ) {
        val links = (bean as FintResource).toLinkResponses(baseUrl, componentResolver)
        if (links.isEmpty()) return

        generator.writeName(LINKS_FIELD)
        context.writeValue(generator, links)
    }
}
