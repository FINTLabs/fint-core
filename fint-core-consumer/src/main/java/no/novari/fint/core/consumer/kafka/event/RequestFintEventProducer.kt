package no.novari.fint.core.consumer.kafka.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class RequestFintEventProducer(
    private val eventKafkaTemplate: KafkaTemplate<String, Any>,
    private val consumerConfig: ConsumerConfiguration,
    @Value("\${novari.kafka.topic.domain-context}") private val domainContext: String,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(
        requestFintEvent: RequestFintEvent,
        domainName: String,
        packageName: String,
    ): CompletableFuture<SendResult<String, Any>> {
        logger.info("Publishing RequestFintEvent: {}", requestFintEvent.corrId)
        return eventKafkaTemplate.send(
            topicName(domainName, packageName),
            requestFintEvent.corrId,
            requestFintEvent,
        )
    }

    private fun topicName(
        domainName: String,
        packageName: String,
    ) = "${consumerConfig.orgId.asTopicSegment}.$domainContext.event.$domainName-$packageName-request"
}
