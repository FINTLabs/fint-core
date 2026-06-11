package no.novari.fint.core.provider.kafka.topic

import no.novari.fint.core.provider.config.ProducerProperties
import no.novari.fint.core.provider.config.ProviderProperties
import no.novari.fint.core.provider.kafka.EventTopicNames
import no.novari.fint.core.shared.kafka.KafkaTopics
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Component

@Component
@ConditionalOnProperty(prefix = "fint.provider", name = ["ensure-topics"], havingValue = "true", matchIfMissing = true)
class RequestEventTopicEnsurer(
    @Qualifier("requestProducerProperties") private val requestProducerProperties: ProducerProperties,
    private val providerProperties: ProviderProperties,
    private val eventTopicNames: EventTopicNames,
    private val coreKafkaAdmin: KafkaAdmin,
    @Value("\${novari.kafka.default-replicas:2}") private val defaultReplicas: Int,
) {
    @EventListener(ApplicationReadyEvent::class)
    fun ensureRequestEventTopics() {
        providerProperties.components.forEach { component ->
            val partitions = component.requestPartitions ?: requestProducerProperties.partitions
            component.orgIds.forEach { orgId ->
                coreKafkaAdmin.createOrModifyTopics(
                    KafkaTopics.eventTopic(
                        eventTopicNames.event("${component.domainName}-${component.packageName}-request", orgId),
                        partitions,
                        defaultReplicas,
                        requestProducerProperties.retentionTime
                    )
                )
            }
        }
    }
}
