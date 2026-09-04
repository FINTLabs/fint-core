package no.fintlabs.provider.kafka.topic

import no.fintlabs.provider.config.KafkaProperties
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_COMPACT
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_DELETE
import org.apache.kafka.common.config.TopicConfig.DELETE_RETENTION_MS_CONFIG
import org.apache.kafka.common.config.TopicConfig.MAX_COMPACTION_LAG_MS_CONFIG
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

    fun createOrModifyEntityTopic(
        topicName: String,
        partitions: Int,
        retentionTime: Duration,
    ) {
        kafkaAdmin.createOrModifyTopics(
            TopicBuilder
                .name(topicName)
                .partitions(partitions)
                .replicas(kafkaProperties.replicas)
                .config(CLEANUP_POLICY_CONFIG, "$CLEANUP_POLICY_DELETE, $CLEANUP_POLICY_COMPACT")
                .config(RETENTION_MS_CONFIG, retentionTime.toMillis().toString())
                .config(DELETE_RETENTION_MS_CONFIG, retentionTime.toMillis().toString())
                .config(MAX_COMPACTION_LAG_MS_CONFIG, ENTITY_MAX_COMPACTION_LAG.toMillis().toString())
                .config(SEGMENT_MS_CONFIG, ENTITY_SEGMENT_DURATION.toMillis().toString())
                .build(),
        )
    }

    private companion object {
        val EVENT_SEGMENT_DURATION: Duration = Duration.ofHours(12)
        val ENTITY_SEGMENT_DURATION: Duration = Duration.ofHours(12)
        val ENTITY_MAX_COMPACTION_LAG: Duration = Duration.ofHours(24)
    }
}
