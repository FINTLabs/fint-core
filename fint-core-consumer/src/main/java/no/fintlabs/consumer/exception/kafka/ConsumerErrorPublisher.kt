package no.fintlabs.consumer.exception.kafka

import no.fintlabs.consumer.config.OrgId
import no.fintlabs.status.models.error.ConsumerError
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplate
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.EventTopicService
import no.novari.kafka.topic.configuration.EventCleanupFrequency
import no.novari.kafka.topic.configuration.EventTopicConfiguration
import no.novari.kafka.topic.name.EventTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Duration
import java.util.UUID

@Service
class ConsumerErrorPublisher(
    parameterizedTemplateFactory: ParameterizedTemplateFactory,
    eventTopicService: EventTopicService,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val eventProducer: ParameterizedTemplate<ConsumerError> =
        parameterizedTemplateFactory.createTemplate(ConsumerError::class.java)
    private val eventName: EventTopicNameParameters = createEventName()

    init {
        eventTopicService.createOrModifyTopic(
            eventName,
            EventTopicConfiguration
                .stepBuilder()
                .partitions(PARTITIONS)
                .retentionTime(RETENTION_TIME)
                .cleanupFrequency(EventCleanupFrequency.NORMAL)
                .build(),
        )
    }

    fun publish(consumerError: ConsumerError) {
        logger.info("Publishing consumer-error to Kafka!")
        eventProducer.send(
            ParameterizedProducerRecord
                .builder<ConsumerError>()
                .key(UUID.randomUUID().toString())
                .topicNameParameters(eventName)
                .value(consumerError)
                .build(),
        )
    }

    private fun createEventName(): EventTopicNameParameters =
        EventTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgId(FINTLABS_ORG_ID.asTopicSegment)
                    .domainContextApplicationDefault()
                    .build(),
            ).eventName("consumer-error")
            .build()

    companion object {
        private val FINTLABS_ORG_ID = OrgId.from("fintlabs.no")
        private val RETENTION_TIME: Duration = Duration.ofDays(7)
        private const val PARTITIONS = 1
    }
}
