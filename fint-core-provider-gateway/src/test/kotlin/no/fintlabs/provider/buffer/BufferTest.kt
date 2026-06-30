package no.fintlabs.provider.buffer

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.Application
import no.fintlabs.provider.config.KafkaContainerBaseIT
import no.novari.fint.model.felles.kompleksedatatyper.Identifikator
import no.novari.fint.model.resource.utdanning.elev.ElevResource
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import org.junit.jupiter.api.Test
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
class BufferTest(
    @Autowired private val writer: BufferWriter,
) : KafkaContainerBaseIT() {
    @MockitoSpyBean
    private lateinit var reader: BufferReader

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
            Expected("fintlabs-no.fint-felleskomponent-resource", 1, thirtyDays, compactDelete),
        )

    @Test
    fun `container starts and topics are created with expected partitions and retention`() {
        assertTrue(KAFKA.isRunning, "Kafka container should be running")

        AdminClient
            .create(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to KAFKA.bootstrapServers))
            .use { admin ->
                val existingTopics = admin.listTopics().names().get()
                println("Existing Kafka topics: $existingTopics")
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

    @Test
    fun `tests sends resource to buffer topic`() {
        val resource = SyncPageEntry.of("123", createElev())

        val sync =
            SyncPage(
                SyncPageMetadata(
                    "test",
                    "corr-id-random",
                    "fintlabs-no",
                    1L,
                    1L,
                    1L,
                    1L,
                    "beta.felleskomponent.no/utdanning/utdanning/elev",
                    1782300748715L,
                ),
                listOf(resource),
                SyncType.FULL,
            )
//        writer.sendSyncEntity(sync, resource)
        verify(reader, timeout(5000).times(1))
            .readMessage(any())
    }

    fun createElev() =
        ElevResource().apply {
            systemId = Identifikator().apply { identifikatorverdi = "123" }
        }
}
