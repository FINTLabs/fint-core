package no.fintlabs.provider.buffer

import no.fintlabs.provider.config.ProviderProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaTopicConfiguration {

    /**
     * We need variable topic names that needs to be computed at runtime.
     * Therefore we bean it up here.
     */

    @Bean
    fun topicBufferName(properties: ProviderProperties): String {
        return "${properties.orgId.asTopicSegment}.fint-felleskomponent-resource"
    }


}