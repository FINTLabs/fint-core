package no.fintlabs.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean

@TestConfiguration
open class KafkaTestJacksonConfig {
    @Bean("kafkaJsonObjectMapper")
    open fun kafkaJsonObjectMapper(): ObjectMapper =
        ObjectMapper()
            .setFilterProvider(SimpleFilterProvider().setFailOnUnknownId(false))
}
