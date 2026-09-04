package no.fintlabs.provider.kafka.topic

import no.fintlabs.provider.config.EntityKafkaProperties
import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.KafkaTopicNames
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-topics"], havingValue = "true", matchIfMissing = true)
class EntityTopicEnsurer(
    private val kafkaTopicService: KafkaTopicService,
    private val entityKafkaProperties: EntityKafkaProperties,
    private val providerProperties: ProviderProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureEntityTopics() {
        providerProperties.components.forEach { component ->
            component.orgIds.forEach { orgId ->
                kafkaTopicService.createOrModifyEntityTopic(
                    KafkaTopicNames.entityTopic(orgId, "${component.domainName}-${component.packageName}"),
                    entityKafkaProperties.partitions,
                    entityKafkaProperties.retentionTime,
                )
            }
        }
    }
}
