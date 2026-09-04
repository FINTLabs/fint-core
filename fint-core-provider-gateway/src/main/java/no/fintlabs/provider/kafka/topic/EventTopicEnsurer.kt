package no.fintlabs.provider.kafka.topic

import no.fintlabs.provider.config.AdapterKafkaProperties
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.KafkaTopicNames
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-topics"], havingValue = "true", matchIfMissing = true)
class EventTopicEnsurer(
    private val adapterKafkaProperties: AdapterKafkaProperties,
    private val kafkaTopicService: KafkaTopicService,
    private val providerProperties: ProviderProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureEventTopics() =
        with(adapterKafkaProperties) {
            listOf(
                TopicNamesConstants.HEARTBEAT_EVENT_NAME to heartbeatRetentionTime,
                TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME to registerRetentionTime,
                TopicNamesConstants.ADAPTER_FULL_SYNC_EVENT_NAME to fullSyncRetentionTime,
                TopicNamesConstants.ADAPTER_DELTA_SYNC_EVENT_NAME to deltaSyncRetentionTime,
                TopicNamesConstants.ADAPTER_DELETE_SYNC_EVENT_NAME to deleteSyncRetentionTime,
                TopicNamesConstants.PROVIDER_ERROR_EVENT_NAME to PROVIDER_ERROR_RETENTION_TIME,
            ).forEach { (eventName, retentionTime) ->
                kafkaTopicService.createOrModifyEventTopic(
                    KafkaTopicNames.eventTopic(providerProperties.orgId, eventName),
                    partitions,
                    retentionTime,
                )
            }
        }

    private companion object {
        val PROVIDER_ERROR_RETENTION_TIME: java.time.Duration = java.time.Duration.ofDays(7)
    }
}
