package no.fintlabs.provider.kafka.topic

import no.fintlabs.provider.config.ProviderProperties
import no.fintlabs.provider.config.RelationUpdateKafkaProperties
import no.novari.core.shared.kafka.KafkaTopicNames
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-topics"], havingValue = "true", matchIfMissing = true)
class RelationUpdateTopicEnsurer(
    private val kafkaTopicService: KafkaTopicService,
    private val relationUpdateKafkaProperties: RelationUpdateKafkaProperties,
    private val providerProperties: ProviderProperties,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureRelationUpdateTopics() {
        providerProperties.components.filter { it.relationUpdate }.forEach { component ->
            component.orgIds.forEach { orgId ->
                kafkaTopicService.createOrModifyEntityTopic(
                    KafkaTopicNames.entityTopic(
                        orgId,
                        "${component.domainName}-${component.packageName}-relation-update",
                    ),
                    relationUpdateKafkaProperties.partitions,
                    relationUpdateKafkaProperties.retentionTime,
                )
            }
        }
    }
}
