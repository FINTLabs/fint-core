package no.fintlabs.consumer.resource.wire

import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.BeanDescription
import com.fasterxml.jackson.databind.SerializationConfig
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.Link

const val WIRE_LINKS_FIELD = "_links"

fun FintResource.toWireLinks(baseUrl: String): Map<String, List<Map<String, String>>> {
    val wire = LinkedHashMap<String, List<Map<String, String>>>()

    metadata.path?.let { path ->
        val selfLinks =
            buildList {
                visitIdentifikators { field, value ->
                    add(mapOf("href" to Link(field.lowercase(), value).href(baseUrl, path)))
                }
            }
        if (selfLinks.isNotEmpty()) wire["self"] = selfLinks
    }

    links.forEach { (relationName, relationLinks) ->
        if (relationName == "self") return@forEach

        val targetPath = metadata.relationPath(relationName)
        val hrefs =
            relationLinks
                .mapNotNull { link ->
                    when {
                        link.unresolved != null -> link.unresolved
                        targetPath != null -> link.href(baseUrl, targetPath)
                        else -> null
                    }
                }.map { mapOf("href" to it) }

        if (hrefs.isNotEmpty()) wire[relationName] = hrefs
    }

    return wire
}

class WireLinksModule(
    baseUrl: String,
) : SimpleModule("fint-wire-links") {
    init {
        setSerializerModifier(WireLinksSerializerModifier(baseUrl))
    }
}

class WireLinksSerializerModifier(
    private val baseUrl: String,
) : BeanSerializerModifier() {
    override fun changeProperties(
        config: SerializationConfig,
        beanDesc: BeanDescription,
        beanProperties: MutableList<BeanPropertyWriter>,
    ): MutableList<BeanPropertyWriter> {
        if (!FintResource::class.java.isAssignableFrom(beanDesc.beanClass)) return beanProperties

        val index = beanProperties.indexOfFirst { it.name == WIRE_LINKS_FIELD }
        if (index >= 0) beanProperties[index] = WireLinksPropertyWriter(beanProperties[index], baseUrl)

        return beanProperties
    }
}

private class WireLinksPropertyWriter(
    base: BeanPropertyWriter,
    private val baseUrl: String,
) : BeanPropertyWriter(base) {
    override fun serializeAsField(
        bean: Any,
        generator: JsonGenerator,
        provider: SerializerProvider,
    ) {
        val wire = (bean as FintResource).toWireLinks(baseUrl)
        if (wire.isEmpty()) return

        generator.writeFieldName(WIRE_LINKS_FIELD)
        provider.defaultSerializeValue(wire, generator)
    }
}
