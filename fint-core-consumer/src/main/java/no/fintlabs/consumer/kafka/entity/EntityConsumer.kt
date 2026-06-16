package no.fintlabs.consumer.kafka.entity

import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.kafka.KafkaConstants.RESOURCE_NAME
import no.fintlabs.consumer.kafka.KafkaConsumerErrorHandling
import no.fintlabs.consumer.kafka.applyConsumerFetchSettings
import no.fintlabs.consumer.kafka.applyStartupJitter
import no.fintlabs.consumer.kafka.stringValue
import no.fintlabs.consumer.resource.ResourceConverter
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.EntityTopicNamePatternParameters
import no.novari.kafka.topic.name.TopicNamePatternParameterPattern
import no.novari.kafka.topic.name.TopicNamePatternPrefixParameters
import no.novari.metamodel.MetamodelService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.stereotype.Service

@Service
class EntityConsumer(
    private val entityProcessingService: EntityProcessingService,
    private val consumerConfig: ConsumerConfiguration,
    private val resourceConverter: ResourceConverter,
    private val metamodelService: MetamodelService,
) {
    companion object {
        private val logger = LoggerFactory.getLogger(EntityConsumer::class.java)
        private const val CONSUMER_NAME = "entity"
    }

    @Bean
    fun resourceEntityConsumerFactory(
        parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService,
        errorHandlerFactory: ErrorHandlerFactory,
    ): ConcurrentMessageListenerContainer<String, in Any> =
        parameterizedListenerContainerFactoryService
            .createRecordListenerContainerFactory(
                Any::class.java,
                this::consumeRecord,
                ListenerConfiguration
                    .stepBuilder()
                    .groupIdApplicationDefaultWithUniqueSuffix()
                    .maxPollRecordsKafkaDefault()
                    .maxPollIntervalKafkaDefault()
                    .seekToBeginningOnAssignment()
                    .build(),
                errorHandlerFactory.createErrorHandler(
                    KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<Any>(
                        logger,
                        CONSUMER_NAME,
                    ),
                ),
                { container ->
                    container.concurrency = consumerConfig.kafka.entityConcurrency
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
                    ).resource(TopicNamePatternParameterPattern.anyOf(componentTopic(), *legacyResourceTopics()))
                    .build(),
            )

    fun consumeRecord(consumerRecord: ConsumerRecord<String, Any?>) =
        createEntityConsumerRecord(consumerRecord)
            .let { entityProcessingService.processEntityConsumerRecord(it) }

    private fun createEntityConsumerRecord(consumerRecord: ConsumerRecord<String, Any?>) =
        consumerRecord.getResourceName().let { resourceName ->
            consumerRecord
                .value()
                ?.let { resourceConverter.convert(resourceName, it) }
                ?.let { EntityConsumerRecord(resourceName, it, consumerRecord) }
                ?: EntityConsumerRecord(resourceName, null, consumerRecord)
        }

    private fun ConsumerRecord<String, Any?>.getResourceName(): String =
        if (consumerConfig.kafka.consumeLegacyResourceTopics) {
            headers().stringValue(RESOURCE_NAME) ?: topic().split("-").last()
        } else {
            headers().stringValue(RESOURCE_NAME) ?: throw IllegalArgumentException("Resource name header not found")
        }

    private fun componentTopic() = "${consumerConfig.domain}-${consumerConfig.packageName}"

    private fun legacyResourceTopics(): Array<String> {
        if (!consumerConfig.kafka.consumeLegacyResourceTopics) return emptyArray()
        return metamodelService
            .getComponent(consumerConfig.domain, consumerConfig.packageName)!!
            .resources
            .map { resource -> "${consumerConfig.domain}-${consumerConfig.packageName}-${resource.name}" }
            .toTypedArray()
    }
}
