package no.novari.fint.core.provider.event.response

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.provider.config.KafkaConfig
import no.novari.fint.core.provider.config.ProviderProperties
import no.novari.fint.core.provider.event.request.RequestEventService
import no.novari.kafka.consuming.ErrorHandlerConfiguration
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.EventTopicNamePatternParameters
import no.novari.kafka.topic.name.TopicNamePatternParameterPattern
import no.novari.kafka.topic.name.TopicNamePatternPrefixParameters
import no.novari.metamodel.MetamodelService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.stereotype.Service


@Service
class ResponseFintEventConsumer(
    private val parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService,
    private val errorHandlerFactory: ErrorHandlerFactory,
    private val requestEventService: RequestEventService,
    private val metamodelService: MetamodelService,
    private val kafkaConfig: KafkaConfig,
    private val providerProperties: ProviderProperties,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    fun responseFintEventListenerContainer(): ConcurrentMessageListenerContainer<String?, ResponseFintEvent> =
        parameterizedListenerContainerFactoryService.createRecordListenerContainerFactory(
            ResponseFintEvent::class.java,
            this::processEvent,
            ListenerConfiguration.stepBuilder()
                .groupIdApplicationDefaultWithSuffix(kafkaConfig.groupIdSuffix)
                .maxPollRecordsKafkaDefault()
                .maxPollIntervalKafkaDefault()
                .seekToBeginningOnAssignment()
                .build(),
            errorHandlerFactory.createErrorHandler(
                ErrorHandlerConfiguration
                    .stepBuilder<ResponseFintEvent?>()
                    .noRetries()
                    .skipFailedRecords()
                    .build()
            )
        ).createContainer(
            EventTopicNamePatternParameters
                .builder()
                .topicNamePatternPrefixParameters(
                    TopicNamePatternPrefixParameters
                        .stepBuilder()
                        .orgId(TopicNamePatternParameterPattern.anyOf(configuredOrgIds()))
                        .domainContextApplicationDefault()
                        .build()
                )
                .eventName(TopicNamePatternParameterPattern.anyOf(*createEventNames()))
                .build()
        )

    private fun configuredOrgIds(): List<String> =
        providerProperties.components.flatMap { it.orgIds }.distinct()

    // Example topic: utdanning-vurdering-response
    private fun createEventNames(): Array<String> =
        metamodelService.getComponents()
            .map { component -> "${component.domainName}-${component.packageName}-response" }
            .toTypedArray()

    private fun processEvent(consumerRecord: ConsumerRecord<String?, ResponseFintEvent>) {
        logger.info("ResponseFintEvent received: {} - {}", consumerRecord.value().orgId, consumerRecord.value().corrId)
        requestEventService.removeEvent(consumerRecord.value().corrId)
    }
}
