package no.fintlabs.consumer.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.util.ISO8601DateFormat
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import no.fintlabs.consumer.resource.wire.WireLinksModule
import no.novari.core.shared.json.FintModelModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
open class JacksonConfiguration {
    /**
     * Outbound resources carry `_links` as full hrefs, which the stored id-based `Link` cannot
     * produce on its own: the target path comes from the owning resource's metadata. The modifier
     * swaps in a writer that renders them, so no serialization rule has to live on the model.
     */
    @Bean
    open fun jackson2ObjectMapperBuilder(consumerConfiguration: ConsumerConfiguration): Jackson2ObjectMapperBuilder =
        Jackson2ObjectMapperBuilder()
            .failOnUnknownProperties(false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .modules(
                JavaTimeModule(),
                KotlinModule.Builder().build(),
                FintModelModule(),
                WireLinksModule(consumerConfiguration.baseUrl),
            ).dateFormat(ISO8601DateFormat())

    @Bean
    @Primary
    open fun objectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper = builder.build()
}
