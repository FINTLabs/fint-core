package no.novari.fint.core.shared.kafka

import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig
import org.springframework.kafka.config.TopicBuilder
import java.time.Duration

/**
 * Builds event topics with the exact configuration the fint-kafka library's
 * `EventTopicService.createOrModifyTopic` produced (delete cleanup, retention, 12h segments),
 * so that `KafkaAdmin.createOrModifyTopics` reconciles existing prod topics to identical configs.
 */
object KafkaTopics {
    val EVENT_SEGMENT: Duration = Duration.ofHours(12)

    fun eventTopic(
        name: String,
        partitions: Int,
        replicas: Int,
        retention: Duration,
    ): NewTopic =
        TopicBuilder
            .name(name)
            .partitions(partitions)
            .replicas(replicas)
            .config(TopicConfig.CLEANUP_POLICY_CONFIG, TopicConfig.CLEANUP_POLICY_DELETE)
            .config(TopicConfig.RETENTION_MS_CONFIG, retention.toMillis().toString())
            .config(TopicConfig.SEGMENT_MS_CONFIG, EVENT_SEGMENT.toMillis().toString())
            .build()
}
