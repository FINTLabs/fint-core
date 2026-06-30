package no.fintlabs.provider.buffer

import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_COMPACT
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_DELETE
import org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfig(
    @Qualifier("topicBufferName") private val topic: String,
) {
    @Bean
    fun kafkaTopic() =
        TopicBuilder
            .name(topic)
            .partitions(1)
            .replicas(1)
            .config(CLEANUP_POLICY_CONFIG, "$CLEANUP_POLICY_COMPACT, $CLEANUP_POLICY_DELETE")
            .config(RETENTION_MS_CONFIG, "2592000000")
            .build()
}
