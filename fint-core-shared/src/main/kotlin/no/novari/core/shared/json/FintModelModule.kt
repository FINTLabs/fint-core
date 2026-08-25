package no.novari.core.shared.json

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.module.SimpleModule
import no.novari.fint.core.model.FintObject
import no.novari.fint.core.model.FintResource
import no.novari.fint.core.model.FintTypeMetadata
import no.novari.fint.core.model.Link

/**
 * Binding rules for the information model. The model jar is deliberately Jackson-free, so the
 * annotations its types cannot carry themselves live here as mixins. Registered on the two
 * interfaces, they propagate to every implementor; no per-type registration exists or is needed.
 * Both [FintJson] mappers install this module; it is the floor the storage and response
 * contracts share.
 */
class FintModelModule : SimpleModule("fint-model") {
    override fun setupModule(context: SetupContext) {
        context.setMixInAnnotations(FintObject::class.java, FintObjectMixin::class.java)
        context.setMixInAnnotations(FintResource::class.java, FintResourceMixin::class.java)
        super.setupModule(context)
    }
}

/**
 * `metadata` is a live object graph of KClass references, not data: it must never reach JSON
 * in either direction.
 */
abstract class FintObjectMixin {
    @get:JsonIgnore
    abstract val metadata: FintTypeMetadata
}

/**
 * `links` crosses every boundary as `_links`, and everything inbound funnels through
 * [FintLinksDeserializer] so hrefs and stored id-forms alike land as the same [Link] shape.
 * `nestedResources` is a derived traversal view over attribute fields, not data: serializing it
 * would duplicate every nested resource in the document, and reading it back fails on the
 * setterless getter.
 */
abstract class FintResourceMixin {
    @get:JsonProperty("_links")
    @get:JsonDeserialize(using = FintLinksDeserializer::class)
    abstract val links: MutableMap<String, MutableList<Link>>

    @get:JsonIgnore
    abstract val nestedResources: List<FintResource>
}
