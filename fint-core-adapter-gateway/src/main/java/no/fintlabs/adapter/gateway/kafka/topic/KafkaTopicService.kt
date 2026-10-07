package no.fintlabs.adapter.gateway.kafka.topic

import no.fintlabs.adapter.gateway.config.KafkaProperties
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_COMPACT
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_DELETE
import org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG
import org.apache.kafka.common.config.TopicConfig.SEGMENT_MS_CONFIG
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.KafkaAdmin
import org.springframework.stereotype.Service
import java.time.Duration

@Service
class KafkaTopicService(
    private val kafkaAdmin: KafkaAdmin,
    private val kafkaProperties: KafkaProperties,
) {
    fun createOrModifyEventTopic(
        topicName: String,
        partitions: Int,
        retentionTime: Duration,
    ) {
        kafkaAdmin.createOrModifyTopics(
            TopicBuilder
                .name(topicName)
                .partitions(partitions)
                .replicas(kafkaProperties.replicas)
                .config(CLEANUP_POLICY_CONFIG, CLEANUP_POLICY_DELETE)
                .config(RETENTION_MS_CONFIG, retentionTime.toMillis().toString())
                .config(SEGMENT_MS_CONFIG, EVENT_SEGMENT_DURATION.toMillis().toString())
                .build(),
        )
    }

    /**
     * A topic that keeps the latest record per key for as long as the topic exists. A record
     * with a null value (a tombstone) removes the key once compaction has run.
     */
    fun createOrModifyCompactedTopic(
        topicName: String,
        partitions: Int,
    ) {
        kafkaAdmin.createOrModifyTopics(
            TopicBuilder
                .name(topicName)
                .partitions(partitions)
                .replicas(kafkaProperties.replicas)
                .config(CLEANUP_POLICY_CONFIG, CLEANUP_POLICY_COMPACT)
                .config(RETENTION_MS_CONFIG, "-1")
                .config(SEGMENT_MS_CONFIG, EVENT_SEGMENT_DURATION.toMillis().toString())
                .build(),
        )
    }

    fun listTopicNames(): Set<String> = withAdminClient { it.listTopics().names().get() }

    fun deleteTopics(topicNames: Collection<String>): Map<String, Throwable?> =
        withAdminClient { client ->
            client
                .deleteTopics(topicNames)
                .topicNameValues()
                .mapValues { (_, future) -> runCatching { future.get() }.exceptionOrNull() }
        }

    private fun <T> withAdminClient(action: (AdminClient) -> T): T = AdminClient.create(kafkaAdmin.configurationProperties).use(action)

    private companion object {
        val EVENT_SEGMENT_DURATION: Duration = Duration.ofHours(12)
    }
}
