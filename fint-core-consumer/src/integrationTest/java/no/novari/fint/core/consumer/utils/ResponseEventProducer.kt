package no.novari.fint.core.consumer.utils

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import org.springframework.boot.test.context.TestComponent
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

@TestComponent
class ResponseEventProducer(
    private val eventKafkaTemplate: KafkaTemplate<String, Any>,
    private val consumerConfig: ConsumerConfiguration,
) {
    fun publish(
        response: ResponseFintEvent,
        domainName: String = "utdanning",
        packageName: String = "vurdering",
    ): CompletableFuture<SendResult<String, Any>> =
        eventKafkaTemplate.send(
            "${consumerConfig.orgId.asTopicSegment}.fint-core.event.$domainName-$packageName-response",
            response.corrId,
            response,
        )
}
