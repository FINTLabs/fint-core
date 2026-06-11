package no.novari.fint.core.provider.kafka.topic

import no.novari.fint.core.provider.config.AdapterKafkaProperties
import no.novari.fint.core.provider.kafka.EventTopicNames
import no.novari.fint.core.shared.kafka.KafkaTopics
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-topics"], havingValue = "true", matchIfMissing = true)
class EventTopicEnsurer(
    private val adapterKafkaProperties: AdapterKafkaProperties,
    private val eventTopicNames: EventTopicNames,
    private val coreKafkaAdmin: KafkaAdmin,
    @Value("\${novari.kafka.default-replicas:2}") private val defaultReplicas: Int,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureEventTopics() = with(adapterKafkaProperties) {
        listOf(
            TopicNamesConstants.HEARTBEAT_EVENT_NAME to heartbeatRetentionTime,
            TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME to registerRetentionTime,
            TopicNamesConstants.ADAPTER_FULL_SYNC_EVENT_NAME to fullSyncRetentionTime,
            TopicNamesConstants.ADAPTER_DELTA_SYNC_EVENT_NAME to deltaSyncRetentionTime,
            TopicNamesConstants.ADAPTER_DELETE_SYNC_EVENT_NAME to deleteSyncRetentionTime
        ).forEach { (eventName, retentionTime) ->
            coreKafkaAdmin.createOrModifyTopics(
                KafkaTopics.eventTopic(
                    eventTopicNames.event(eventName),
                    partitions,
                    defaultReplicas,
                    retentionTime
                )
            )
        }
    }
}
