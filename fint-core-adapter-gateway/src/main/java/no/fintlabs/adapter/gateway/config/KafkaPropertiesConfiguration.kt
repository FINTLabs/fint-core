package no.fintlabs.adapter.gateway.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class KafkaPropertiesConfiguration(
    private val kafkaProperties: KafkaProperties,
) {
    @Bean
    fun adapterKafkaProperties(): AdapterKafkaProperties = kafkaProperties.adapter
}
