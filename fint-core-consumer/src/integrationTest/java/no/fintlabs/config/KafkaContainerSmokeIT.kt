package no.fintlabs.config

import no.fintlabs.Application
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@TestPropertySource(
    properties = [
        "fint.org-id=fintlabs.no",
        "fint.consumer.org-id=fintlabs.no",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=vurdering",
    ],
)
class KafkaContainerSmokeIT : KafkaContainerBaseIT() {
    private data class Expected(
        val name: String,
        val partitions: Int,
        val retention: Duration,
        val cleanupPolicy: String,
    )

    private val thirtyDays = Duration.ofDays(30)
    private val sevenDays = Duration.ofDays(7)
    private val compactDelete = "compact,delete"
    private val delete = "delete"

    private val expected =
        listOf(
            Expected("fintlabs-no.fint-core.entity.utdanning-vurdering", 6, thirtyDays, compactDelete),
            Expected("fintlabs-no.fint-core.entity.utdanning-vurdering-relation-update", 6, thirtyDays, compactDelete),
            Expected("fintlabs-no.fint-core.event.utdanning-vurdering-request", 1, sevenDays, delete),
            Expected("fintlabs-no.fint-core.event.utdanning-vurdering-response", 1, sevenDays, delete),
            Expected("fintlabs-no.fint-core.event.consumer-error", 1, sevenDays, delete),
            Expected("fintlabs-no.fint-core.event.sync-status", 1, sevenDays, delete),
        )

    @Test
    fun `container starts and topics are created with expected partitions and retention`() {
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
                    val desc = descriptions[exp.name] ?: error("Topic missing: ${exp.name}")
                    assertEquals(exp.partitions, desc.partitions().size, "partitions for ${exp.name}")

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
