package no.novari.core.shared.json

import com.fasterxml.jackson.annotation.JsonInclude
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.util.StdDateFormat
import tools.jackson.module.kotlin.KotlinModule

/**
 * The two Jackson contracts in fint-core. Every mapper that touches a `FintResource` is built
 * here (production beans and tests alike), so the recipes cannot drift apart. Never assemble
 * such a mapper by hand or copy an injected one.
 */
object FintJson {
    /**
     * The durable, id-based form: `_links` holds `{idField, idValue}` entries and `self` is never
     * present. This is what Mongo documents and the provider's buffer topic contain, and what
     * `FintLinksDeserializer` produces from inbound adapter hrefs. Use it for everything read
     * from or written to storage; it never renders an href.
     */
    fun storageMapper(): JsonMapper = mapperBuilder().build()

    /**
     * The county-facing response form: everything in the storage form, plus `_links` rendered as
     * absolute hrefs against [baseUrl] and `self` regenerated from the resource's id fields.
     * A common resource has no path of its own, so its hrefs render against the component
     * supplied by [componentResolver]: the consumer reads it from the request being served,
     * and the default renders such resources without the links that need one.
     * This is the consumer's primary (HTTP) mapper and nothing else: persisting through it
     * would store hrefs and defeat the id-based storage form.
     */
    fun responseMapper(
        baseUrl: String,
        componentResolver: ComponentResolver = { null },
    ): JsonMapper =
        mapperBuilder()
            .addModule(ResponseLinksModule(baseUrl, componentResolver))
            .defaultDateFormat(StdDateFormat.instance)
            .build()

    private fun mapperBuilder(): JsonMapper.Builder =
        JsonMapper
            .builder()
            .addModules(
                KotlinModule.Builder().build(),
                FintModelModule(),
            ).changeDefaultPropertyInclusion {
                JsonInclude.Value.construct(
                    JsonInclude.Include.NON_NULL,
                    JsonInclude.Include.NON_NULL,
                )
            }.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(MapperFeature.USE_GETTERS_AS_SETTERS)
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
}
