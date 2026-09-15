package no.fintlabs.provider.kafka.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import no.fintlabs.provider.config.KafkaProperties
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.common.config.TopicConfig.CLEANUP_POLICY_CONFIG
import org.apache.kafka.common.config.TopicConfig.RETENTION_MS_CONFIG
import org.apache.kafka.common.config.TopicConfig.SEGMENT_MS_CONFIG
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin
import java.time.Duration

class KafkaTopicServiceTest {
    private val kafkaAdmin: KafkaAdmin = mockk()
    private val topic = slot<NewTopic>()
    private val service = KafkaTopicService(kafkaAdmin, KafkaProperties(replicas = 2))

    init {
        every { kafkaAdmin.createOrModifyTopics(capture(topic)) } just Runs
    }

    @Test
    fun `event topic preserves the Novari cleanup configuration`() {
        service.createOrModifyEventTopic("org.fint-core.event.test", 3, Duration.ofDays(7))

        with(topic.captured) {
            assertThat(name()).isEqualTo("org.fint-core.event.test")
            assertThat(numPartitions()).isEqualTo(3)
            assertThat(replicationFactor()).isEqualTo(2.toShort())
            assertThat(configs()).containsEntry(CLEANUP_POLICY_CONFIG, "delete")
            assertThat(configs()).containsEntry(RETENTION_MS_CONFIG, Duration.ofDays(7).toMillis().toString())
            assertThat(configs()).containsEntry(SEGMENT_MS_CONFIG, Duration.ofHours(12).toMillis().toString())
        }
    }
}
