package no.fintlabs.utils

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.core.shared.kafka.KafkaTopicNames
import org.springframework.boot.test.context.TestComponent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

@TestComponent
class ResponseEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, ResponseFintEvent>,
    private val consumerConfig: ConsumerConfiguration,
) {
    fun publish(response: ResponseFintEvent): CompletableFuture<SendResult<String, ResponseFintEvent>> =
        kafkaTemplate.send(
            KafkaTopicNames.eventTopic(
                consumerConfig.orgId,
                "${consumerConfig.domain}-${consumerConfig.packageName}-response",
            ),
            response.corrId,
            response,
        )
}
