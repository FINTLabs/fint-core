package no.fintlabs.consumer.config

import com.fasterxml.jackson.databind.ObjectMapper
import no.novari.core.shared.json.FintJson
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * The consumer's primary mapper speaks the response contract: outbound `_links` as full hrefs
 * with a regenerated `self`. Storage-form conversion never goes through this bean — those sites
 * build `FintJson.storageMapper()` themselves.
 */
@Configuration
open class JacksonConfiguration {
    @Bean
    @Primary
    open fun objectMapper(consumerConfiguration: ConsumerConfiguration): ObjectMapper =
        FintJson.responseMapper(consumerConfiguration.baseUrl)
}
