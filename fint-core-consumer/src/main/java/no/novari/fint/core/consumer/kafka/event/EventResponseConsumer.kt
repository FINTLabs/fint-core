package no.novari.fint.core.consumer.kafka.event

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import no.novari.fint.core.consumer.kafka.KafkaConsumerErrorHandling
import no.novari.fint.core.consumer.kafka.applyConsumerFetchSettings
import no.novari.fint.core.consumer.kafka.applyStartupJitter
import no.novari.fint.core.shared.event.EventStatusStore
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import java.util.regex.Pattern

@Configuration
class EventResponseConsumer(
    private val consumerConfig: ConsumerConfiguration,
    private val eventStatusStore: EventStatusStore,
    @Value("\${novari.kafka.topic.domain-context}") private val domainContext: String,
) {
    @Bean
    fun responseFintEventContainerListener(
        responseFintEventConsumerFactory: ConsumerFactory<String, ResponseFintEvent>,
        kafkaProperties: KafkaProperties,
    ): ConcurrentMessageListenerContainer<String, ResponseFintEvent> {
        val topicPattern =
            Pattern.compile(
                "^${Pattern.quote(
                    consumerConfig.orgId.asTopicSegment,
                )}\\.${Pattern.quote(domainContext)}\\.event\\..*-response$",
            )
        val containerProperties = ContainerProperties(topicPattern)
        containerProperties.groupId = "${kafkaProperties.consumer.groupId}-event"
        containerProperties.idleBetweenPolls = consumerConfig.kafka.idleBetweenPolls
        containerProperties.messageListener = MessageListener<String, ResponseFintEvent>(this::consumeRecord)
        val container = ConcurrentMessageListenerContainer(responseFintEventConsumerFactory, containerProperties)
        container.concurrency = consumerConfig.kafka.responseConcurrency
        container.applyConsumerFetchSettings(consumerConfig.kafka)
        container.applyStartupJitter(consumerConfig.kafka)
        container.setCommonErrorHandler(KafkaConsumerErrorHandling.loggingErrorHandler(logger, CONSUMER_NAME))
        return container
    }

    private fun consumeRecord(consumerRecord: ConsumerRecord<String, ResponseFintEvent>) {
        val response = consumerRecord.value()
        if (response == null) {
            logger.error(
                "Skipping undeserializable ResponseFintEvent: topic={}, partition={}, offset={}",
                consumerRecord.topic(),
                consumerRecord.partition(),
                consumerRecord.offset(),
            )
            return
        }
        if (!eventStatusStore.attachResponse(response.corrId, response)) {
            logger.info("Dropping response {} — no matching request (expired or unknown)", response.corrId)
        }
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EventResponseConsumer::class.java)
        private const val CONSUMER_NAME = "event-response"
    }
}
