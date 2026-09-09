package no.fintlabs.provider.event.response

import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.core.shared.kafka.EventTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class ResponseFintEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, Any>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(response: ResponseFintEvent) {
        val topic = EventTopics.responseTopic()

        kafkaTemplate.send(topic, response.corrId, response).whenComplete { _, exception ->
            if (exception != null) {
                logger.error("Failed to publish ResponseFintEvent {} to {}", response.corrId, topic, exception)
            }
        }
    }
}
