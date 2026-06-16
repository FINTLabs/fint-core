package no.fintlabs.consumer.kafka.event

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.KafkaConsumerErrorHandling
import no.fintlabs.consumer.kafka.applyConsumerFetchSettings
import no.fintlabs.consumer.kafka.applyStartupJitter
import no.fintlabs.consumer.resource.event.EventStatusCache
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer

@Configuration
class EventResponseConsumer(
    private val consumerConfig: ConsumerConfiguration,
    private val eventStatusCache: EventStatusCache,
) {
    @Bean
    fun responseFintEventContainerListener(
        parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService,
        errorHandlerFactory: ErrorHandlerFactory,
    ): ConcurrentMessageListenerContainer<String, ResponseFintEvent> =
        parameterizedListenerContainerFactoryService
            .createRecordListenerContainerFactory(
                ResponseFintEvent::class.java,
                this::consumeRecord,
                ListenerConfiguration
                    .stepBuilder()
                    .groupIdApplicationDefaultWithUniqueSuffix()
                    .maxPollRecordsKafkaDefault()
                    .maxPollIntervalKafkaDefault()
                    .seekToBeginningOnAssignment()
                    .build(),
                errorHandlerFactory.createErrorHandler(
                    KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<ResponseFintEvent>(
                        logger,
                        CONSUMER_NAME,
                    ),
                ),
                { container ->
                    container.concurrency = consumerConfig.kafka.responseConcurrency
                    container.containerProperties.idleBetweenPolls = consumerConfig.kafka.idleBetweenPolls
                    container.applyConsumerFetchSettings(consumerConfig.kafka)
                    container.applyStartupJitter(consumerConfig.kafka)
                },
            ).createContainer(
                EventTopicNameParameters
                    .builder()
                    .topicNamePrefixParameters(
                        TopicNamePrefixParameters
                            .stepBuilder()
                            .orgId(consumerConfig.orgId.asTopicSegment)
                            .domainContextApplicationDefault()
                            .build(),
                    ).eventName("${consumerConfig.domain}-${consumerConfig.packageName}-response")
                    .build(),
            )

    private fun consumeRecord(consumerRecord: ConsumerRecord<String, ResponseFintEvent>) {
        logger.info("Received Response: {}", consumerRecord.value())
        eventStatusCache.trackResponse(consumerRecord.value().corrId, consumerRecord.value())
    }

    companion object {
        private val logger = LoggerFactory.getLogger(EventResponseConsumer::class.java)
        private const val CONSUMER_NAME = "event-response"
    }
}
