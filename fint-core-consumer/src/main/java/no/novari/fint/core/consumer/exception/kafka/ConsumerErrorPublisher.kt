package no.novari.fint.core.consumer.exception.kafka

import no.fintlabs.status.models.error.ConsumerError
import no.novari.fint.core.consumer.config.OrgId
import no.novari.fint.core.shared.kafka.KafkaTopics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class ConsumerErrorPublisher(
    private val eventKafkaTemplate: KafkaTemplate<String, Any>,
    coreKafkaAdmin: KafkaAdmin,
    @Value("\${novari.kafka.topic.domain-context}") domainContext: String,
    @Value("\${novari.kafka.default-replicas:2}") defaultReplicas: Int,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val topic = "${FINTLABS_ORG_ID.asTopicSegment}.$domainContext.event.consumer-error"

    init {
        coreKafkaAdmin.createOrModifyTopics(
            KafkaTopics.eventTopic(topic, PARTITIONS, defaultReplicas, RETENTION_TIME),
        )
    }

    fun publish(consumerError: ConsumerError) {
        logger.info("Publishing consumer-error to Kafka!")
        eventKafkaTemplate.send(topic, UUID.randomUUID().toString(), consumerError)
    }

    companion object {
        private val FINTLABS_ORG_ID = OrgId.from("fintlabs.no")
        private val RETENTION_TIME: Duration = Duration.ofDays(7)
        private const val PARTITIONS = 1
    }
}
