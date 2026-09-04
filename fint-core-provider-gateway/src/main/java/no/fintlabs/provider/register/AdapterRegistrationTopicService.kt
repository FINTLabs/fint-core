package no.fintlabs.provider.register

import no.fintlabs.adapter.models.AdapterCapability
import no.fintlabs.adapter.models.AdapterContract
import no.fintlabs.provider.config.EntityKafkaProperties
import no.fintlabs.provider.kafka.topic.KafkaTopicService
import no.novari.core.shared.kafka.KafkaTopicNames
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

@Service
class AdapterRegistrationTopicService(
    private val kafkaTopicService: KafkaTopicService,
    private val entityKafkaProperties: EntityKafkaProperties,
) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val ensuredTopics = ConcurrentHashMap.newKeySet<String>()

    /**
     * Ensures a Kafka entity topic exists for each unique component (domainName-packageName combination)
     * in the adapter contract. Topics are created per org, and if a topic has already been
     * ensured for a given org and component in this runtime, it is skipped.
     *
     * @param adapterContract the contract containing the org and its capabilities
     */
    fun createCapabilityTopics(adapterContract: AdapterContract) {
        adapterContract.capabilities
            .distinctBy { it.component }
            .forEach { ensureTopicExists(adapterContract.orgId, it) }
    }

    private fun ensureTopicExists(
        orgId: String,
        capability: AdapterCapability,
    ) {
        val topicKey = "$orgId:${capability.component}"
        if (!ensuredTopics.add(topicKey)) return

        logger.debug(
            "Ensuring entity-topic for org: {} component: {} with partitions: {}",
            orgId,
            capability.component,
            entityKafkaProperties.partitions,
        )

        kafkaTopicService.createOrModifyEntityTopic(
            KafkaTopicNames.entityTopic(orgId, capability.component),
            entityKafkaProperties.partitions,
            entityKafkaProperties.retentionTime,
        )
    }
}
