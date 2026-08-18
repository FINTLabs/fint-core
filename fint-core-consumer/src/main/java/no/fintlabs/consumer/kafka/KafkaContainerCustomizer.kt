package no.fintlabs.consumer.kafka

import no.fintlabs.consumer.config.KafkaConfiguration
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer

fun <VALUE> ConcurrentMessageListenerContainer<String, VALUE>.applyConsumerFetchSettings(
    kafkaConfiguration: KafkaConfiguration,
) {
    containerProperties.kafkaConsumerProperties.setProperty(
        ConsumerConfig.FETCH_MIN_BYTES_CONFIG,
        kafkaConfiguration.fetchMinBytes.toString(),
    )
    containerProperties.kafkaConsumerProperties.setProperty(
        ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG,
        kafkaConfiguration.fetchMaxWaitMs.toString(),
    )
}

fun <VALUE> ConcurrentMessageListenerContainer<String, VALUE>.applyStartupJitter(
    kafkaConfiguration: KafkaConfiguration,
) {
    if (!kafkaConfiguration.startupJitter.isZero) {
        isAutoStartup = false
    }
}
