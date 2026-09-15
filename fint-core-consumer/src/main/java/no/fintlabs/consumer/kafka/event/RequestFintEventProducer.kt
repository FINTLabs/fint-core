package no.fintlabs.consumer.kafka.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.core.shared.kafka.EventTopics
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service

@Service
class RequestFintEventProducer(
    private val kafkaTemplate: KafkaTemplate<String, RequestFintEvent>,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    fun publish(event: RequestFintEvent) {
        val topic = EventTopics.requestTopic()

        kafkaTemplate.send(topic, event.corrId, event).whenComplete { _, exception ->
            if (exception != null) {
                logger.error("Failed to publish RequestFintEvent {} to {}", event.corrId, topic, exception)
            }
        }
    }
}
