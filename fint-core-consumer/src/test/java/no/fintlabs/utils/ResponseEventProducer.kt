package no.fintlabs.utils

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.springframework.boot.test.context.TestComponent
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

@TestComponent
class ResponseEventProducer(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
    private val consumerConfig: ConsumerConfiguration,
) {
    private val producer = parameterizedTemplateFactory.createTemplate(ResponseFintEvent::class.java)

    fun publish(response: ResponseFintEvent): CompletableFuture<SendResult<String, ResponseFintEvent>> =
        producer.send(
            ParameterizedProducerRecord
                .builder<ResponseFintEvent>()
                .key(response.corrId)
                .topicNameParameters(
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
                ).value(response)
                .build(),
        )
}
