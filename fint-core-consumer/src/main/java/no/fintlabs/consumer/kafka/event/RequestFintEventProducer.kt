package no.fintlabs.consumer.kafka.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.slf4j.LoggerFactory
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class RequestFintEventProducer(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
    private val consumerConfig: ConsumerConfiguration,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val producer = parameterizedTemplateFactory.createTemplate(RequestFintEvent::class.java)

    fun publish(
        requestFintEvent: RequestFintEvent,
        domainName: String,
        packageName: String,
    ): CompletableFuture<SendResult<String, RequestFintEvent>> {
        logger.info("Publishing RequestFintEvent: {}", requestFintEvent.corrId)
        return producer.send(
            ParameterizedProducerRecord
                .builder<RequestFintEvent>()
                .key(requestFintEvent.corrId)
                .topicNameParameters(topicNameParameters(domainName, packageName))
                .value(requestFintEvent)
                .build(),
        )
    }

    private fun topicNameParameters(
        domainName: String,
        packageName: String,
    ) = EventTopicNameParameters
        .builder()
        .topicNamePrefixParameters(
            TopicNamePrefixParameters
                .stepBuilder()
                .orgId(consumerConfig.orgId.asTopicSegment)
                .domainContextApplicationDefault()
                .build(),
        ).eventName("$domainName-$packageName-request")
        .build()
}
