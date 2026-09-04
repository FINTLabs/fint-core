package no.fintlabs.provider.kafka

import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.KafkaTopicNames
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.header.internals.RecordHeader
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture

/**
 * Replacement for ParameterizedTemplateFactory and ParameterizedTemplate that was used with fint-kafka
 */
@Service
class EventPublisher(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
    private val providerProperties: ProviderProperties,
    @param:Value("\${spring.application.name:provider}") private val applicationName: String,
) {
    fun publish(
        eventName: String,
        value: Any,
    ): CompletableFuture<SendResult<String, Any>> = publish(eventName, null, value)

    fun publish(
        eventName: String,
        key: String?,
        value: Any,
    ): CompletableFuture<SendResult<String, Any>> {
        val topic = KafkaTopicNames.eventTopic(providerProperties.orgId, eventName)
        val record = ProducerRecord<String, Any>(topic, key, value)
        record.headers().add(
            RecordHeader(ORIGIN_APPLICATION_ID, applicationName.toByteArray(StandardCharsets.UTF_8)),
        )
        return kafkaTemplate.send(record)
    }

    private companion object {
        const val ORIGIN_APPLICATION_ID = "origin.application.id"
    }
}
