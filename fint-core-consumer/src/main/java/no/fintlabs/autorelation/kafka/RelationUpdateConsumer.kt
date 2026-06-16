package no.fintlabs.autorelation.kafka

import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.KafkaConsumerErrorHandling
import no.fintlabs.consumer.kafka.KafkaThroughputMetrics
import no.fintlabs.consumer.kafka.applyConsumerFetchSettings
import no.fintlabs.consumer.kafka.applyStartupJitter
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.EntityTopicNamePatternParameters
import no.novari.kafka.topic.name.TopicNamePatternParameterPattern
import no.novari.kafka.topic.name.TopicNamePatternPrefixParameters
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer

@Configuration
class RelationUpdateConsumer(
    private val autoRelationService: AutoRelationService,
    private val consumerConfig: ConsumerConfiguration,
    private val kafkaThroughputMetrics: KafkaThroughputMetrics,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(RelationUpdateConsumer::class.java)
        private const val CONSUMER_NAME = "relation-update"
    }

    @Bean
    @ConditionalOnProperty(
        name = ["fint.consumer.autorelation.enabled"],
        havingValue = "true",
        matchIfMissing = true,
    )
    fun relationUpdateConsumerContainer(
        parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService,
        errorHandlerFactory: ErrorHandlerFactory,
    ): ConcurrentMessageListenerContainer<String, RelationUpdate> =
        parameterizedListenerContainerFactoryService
            .createRecordListenerContainerFactory(
                RelationUpdate::class.java,
                this::consumeRecord,
                ListenerConfiguration
                    .stepBuilder()
                    .groupIdApplicationDefaultWithUniqueSuffix()
                    .maxPollRecordsKafkaDefault()
                    .maxPollIntervalKafkaDefault()
                    .seekToBeginningOnAssignment()
                    .build(),
                errorHandlerFactory.createErrorHandler(
                    KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<RelationUpdate>(
                        logger,
                        CONSUMER_NAME,
                    ),
                ),
                { container ->
                    container.concurrency = consumerConfig.kafka.relationConcurrency
                    container.containerProperties.idleBetweenPolls = consumerConfig.kafka.idleBetweenPolls
                    container.applyConsumerFetchSettings(consumerConfig.kafka)
                    container.applyStartupJitter(consumerConfig.kafka)
                },
            ).createContainer(
                EntityTopicNamePatternParameters
                    .builder()
                    .topicNamePatternPrefixParameters(
                        TopicNamePatternPrefixParameters
                            .stepBuilder()
                            .orgId(TopicNamePatternParameterPattern.exactly(consumerConfig.orgId.asTopicSegment))
                            .domainContextApplicationDefault()
                            .build(),
                        // Makes sure we listen to component patterns such as utdanning-vurdering'-relation-update'
                    ).resource(
                        TopicNamePatternParameterPattern.exactly(
                            "${consumerConfig.domain}-${consumerConfig.packageName}-relation-update",
                        ),
                    ).build(),
            )

    fun consumeRecord(consumerRecord: ConsumerRecord<String?, RelationUpdate>) {
        val startedAt = System.nanoTime()
        val relationUpdate = consumerRecord.value()
        autoRelationService.process(relationUpdate)
        kafkaThroughputMetrics.recordRelationUpdateConsumer(
            relationUpdate.targetEntity.resourceName,
            System.nanoTime() - startedAt,
        )
    }
}
