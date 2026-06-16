package no.fintlabs.consumer.kafka.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class RequestFintEventProducer(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
    consumerConfig: ConsumerConfiguration,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val producer = parameterizedTemplateFactory.createTemplate(RequestFintEvent::class.java)

    private val topicNameParameters =
        EventTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgId(consumerConfig.orgId.asTopicSegment)
                    .domainContextApplicationDefault()
                    .build(),
            ).eventName("${consumerConfig.domain}-${consumerConfig.packageName}-request")
            .build()

    fun publish(requestFintEvent: RequestFintEvent) {
        logger.info("Publishing RequestFintEvent: {}", requestFintEvent.corrId)
        producer.send(
            ParameterizedProducerRecord
                .builder<RequestFintEvent>()
                .key(requestFintEvent.corrId)
                .topicNameParameters(topicNameParameters)
                .value(requestFintEvent)
                .build(),
        )
    }
}
