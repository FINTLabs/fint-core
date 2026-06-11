package no.novari.fint.core.provider.event.request

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.provider.config.ProviderProperties
import no.novari.fint.core.provider.kafka.EventTopicNames
import no.novari.metamodel.MetamodelService
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.kafka.listener.MessageListener
import org.springframework.util.backoff.FixedBackOff

@Configuration
open class RequestFintEventConsumer(
    private val requestEventService: RequestEventService,
    private val metamodelService: MetamodelService,
    private val providerProperties: ProviderProperties,
    private val eventTopicNames: EventTopicNames,
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    open fun requestFintEventListenerContainer(
        requestFintEventConsumerFactory: ConsumerFactory<String, RequestFintEvent>,
    ): ConcurrentMessageListenerContainer<String, RequestFintEvent> {
        val containerProperties =
            ContainerProperties(eventTopicNames.eventPattern(configuredOrgIds(), createEventNames()))
        containerProperties.groupId = "${eventTopicNames.defaultOrgId}.provider-event"
        containerProperties.messageListener = MessageListener<String, RequestFintEvent>(this::processEvent)
        val container = ConcurrentMessageListenerContainer(requestFintEventConsumerFactory, containerProperties)
        container.setCommonErrorHandler(
            DefaultErrorHandler(
                { record, exception ->
                    logger.error(
                        "Skipping failed RequestFintEvent: topic={}, partition={}, offset={}",
                        record.topic(),
                        record.partition(),
                        record.offset(),
                        exception,
                    )
                },
                FixedBackOff(0L, 0L),
            ),
        )
        return container
    }

    private fun configuredOrgIds(): List<String> =
        providerProperties.components.flatMap { it.orgIds }.distinct()

    // Example topic: utdanning-vurdering-request
    private fun createEventNames(): List<String> =
        metamodelService.getComponents()
            .map { component -> "${component.domainName}-${component.packageName}-request" }

    private fun processEvent(consumerRecord: ConsumerRecord<String, RequestFintEvent>) {
        val event = consumerRecord.value()
        if (event == null) {
            logger.error(
                "Skipping undeserializable RequestFintEvent: topic={}, partition={}, offset={}",
                consumerRecord.topic(),
                consumerRecord.partition(),
                consumerRecord.offset(),
            )
            return
        }
        logger.info("RequestFintEvent received: {} - {}", event.orgId, event.corrId)
        requestEventService.addEvent(event)
    }
}
