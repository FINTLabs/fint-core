package no.novari.core.shared.json

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.util.ISO8601DateFormat
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule

/**
 * The two Jackson contracts in fint-core. Every mapper that touches a `FintResource` is built
 * here — production beans and tests alike — so the recipes cannot drift apart. Never assemble
 * such a mapper by hand or copy an injected one.
 */
object FintJson {
    /**
     * The durable, id-based form: `_links` holds `{idField, idValue}` entries and `self` is never
     * present. This is what Mongo documents and the provider's buffer topic contain, and what
     * `FintLinksDeserializer` produces from inbound adapter hrefs. Use it for everything read
     * from or written to storage; it never renders an href.
     */
    fun storageMapper(): ObjectMapper =
        ObjectMapper()
            .registerModules(
                JavaTimeModule(),
                KotlinModule.Builder().build(),
                FintModelModule(),
            ).setSerializationInclusion(JsonInclude.Include.NON_NULL)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

    /**
     * The county-facing wire form: everything in the storage form, plus `_links` rendered as
     * absolute hrefs against [baseUrl] and `self` regenerated from the resource's id fields.
     * A common resource has no path of its own, so its hrefs render against the component
     * supplied by [componentResolver] — the consumer reads it from the request being served,
     * and the default renders such resources without the links that need one.
     * This is the consumer's primary (HTTP) mapper and nothing else — persisting through it
     * would store hrefs and defeat the id-based storage form.
     */
    @Suppress("DEPRECATION")
    fun responseMapper(
        baseUrl: String,
        componentResolver: ComponentResolver = { null },
    ): ObjectMapper =
        storageMapper()
            .registerModule(ResponseLinksModule(baseUrl, componentResolver))
            .setDateFormat(ISO8601DateFormat())
}
