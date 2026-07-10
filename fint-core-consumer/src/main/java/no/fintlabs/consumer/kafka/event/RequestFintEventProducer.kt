package no.fintlabs.consumer.kafka.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture

@Service
class RequestFintEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, RequestFintEvent>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    // TODO: What should we name this topic? Is it fine? Should it be shared across all components, or should each org gets it own?
    fun publish(requestFintEvent: RequestFintEvent): CompletableFuture<SendResult<String?, RequestFintEvent>> {
        logger.info("Published RequestFintEvent to Kafka")
        return kafkaTemplate.send("fintlabs-no.fint-core.event.request-fint-even", requestFintEvent)
    }

}
