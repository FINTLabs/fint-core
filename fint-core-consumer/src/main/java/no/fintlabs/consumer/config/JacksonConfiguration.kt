package no.fintlabs.consumer.config

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import com.fasterxml.jackson.databind.util.ISO8601DateFormat
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder

@Configuration
open class JacksonConfiguration {
    @Bean
    open fun jackson2ObjectMapperBuilder(): Jackson2ObjectMapperBuilder =
        Jackson2ObjectMapperBuilder()
            .failOnUnknownProperties(false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .modules(JavaTimeModule(), KotlinModule.Builder().build())
            .dateFormat(ISO8601DateFormat())
            .filters(dontFailOnUnknownId())

    private fun dontFailOnUnknownId() = SimpleFilterProvider().setFailOnUnknownId(false)

    @Bean
    @Primary
    open fun objectMapper(builder: Jackson2ObjectMapperBuilder): ObjectMapper = builder.build()
}
