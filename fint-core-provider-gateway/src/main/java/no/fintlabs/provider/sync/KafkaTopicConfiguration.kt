package no.fintlabs.provider.sync

import no.fintlabs.provider.config.ProviderProperties
import no.novari.core.shared.kafka.EventTopics
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_COMPACT
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_DELETE
import org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.TopicBuilder

@Configuration
class KafkaTopicConfiguration {
    /**
     * We need variable topic names that needs to be computed at runtime.
     * Therefore we bean it up here.
     */
    @Bean
    fun topicBufferName(properties: ProviderProperties): String =
        "${properties.orgId.asTopicSegment}.fint-core.fint-felleskomponent-resource"

    @Bean
    fun kafkaTopic(
        @Qualifier("topicBufferName") topic: String,
    ): NewTopic =
        TopicBuilder
            .name(topic)
            .partitions(1)
            .replicas(1)
            .config(CLEANUP_POLICY_CONFIG, "$CLEANUP_POLICY_COMPACT, $CLEANUP_POLICY_DELETE")
            .config(RETENTION_MS_CONFIG, "2592000000")
            .build()

    @Bean
    fun eventRequestTopic(properties: ProviderProperties): NewTopic = eventTopic(EventTopics.requestTopic(properties.orgId))

    @Bean
    fun eventResponseTopic(properties: ProviderProperties): NewTopic = eventTopic(EventTopics.responseTopic(properties.orgId))

    private fun eventTopic(name: String): NewTopic =
        TopicBuilder
            .name(name)
            .partitions(1)
            .replicas(1)
            .config(CLEANUP_POLICY_CONFIG, CLEANUP_POLICY_DELETE)
            .config(RETENTION_MS_CONFIG, "86400000")
            .build()
}
