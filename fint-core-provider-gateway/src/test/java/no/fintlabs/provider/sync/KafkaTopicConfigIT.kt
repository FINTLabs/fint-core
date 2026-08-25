package no.fintlabs.provider.sync

import no.fintlabs.provider.ProviderAppIT
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KafkaTopicConfigIT : ProviderAppIT() {
    private data class Expected(
        val name: String,
        val partitions: Int,
        val retention: Duration,
        val cleanupPolicy: String,
    )

    private val expected =
        listOf(
            Expected("fintlabs-no.fint-core.fint-felleskomponent-resource", 1, Duration.ofDays(30), "compact,delete"),
        )

    @Test
    fun `topics are created with expected partitions, retention and cleanup policy`() {
        assertTrue(KAFKA.isRunning, "Kafka container should be running")

        AdminClient
            .create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA.bootstrapServers))
            .use { admin ->
                val names = expected.map { it.name }
                val descriptions = admin.describeTopics(names).allTopicNames().get()
                val configs =
                    admin
                        .describeConfigs(names.map { ConfigResource(ConfigResource.Type.TOPIC, it) })
                        .all()
                        .get()

                expected.forEach { exp ->
                    val description = descriptions[exp.name] ?: error("Topic missing: ${exp.name}")
                    assertEquals(exp.partitions, description.partitions().size, "partitions for ${exp.name}")

                    val topicConfig =
                        configs[ConfigResource(ConfigResource.Type.TOPIC, exp.name)]
                            ?: error("config missing for ${exp.name}")

                    val retentionMs =
                        topicConfig
                            .get(TopicConfig.RETENTION_MS_CONFIG)
                            ?.value()
                            ?.toLong()
                            ?: error("retention.ms missing for ${exp.name}")
                    assertEquals(exp.retention.toMillis(), retentionMs, "retention for ${exp.name}")

                    val cleanupPolicy =
                        topicConfig
                            .get(TopicConfig.CLEANUP_POLICY_CONFIG)
                            ?.value()
                            ?: error("cleanup.policy missing for ${exp.name}")
                    assertEquals(exp.cleanupPolicy, cleanupPolicy, "cleanup.policy for ${exp.name}")
                }
            }
    }
}
