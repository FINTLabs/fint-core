package no.fintlabs.provider.sync

import no.fintlabs.adapter.models.sync.SyncPage
import no.fintlabs.adapter.models.sync.SyncPageEntry
import no.fintlabs.adapter.models.sync.SyncPageMetadata
import no.fintlabs.adapter.models.sync.SyncType
import no.fintlabs.provider.Application
import no.fintlabs.provider.KafkaContainerBaseIT
import no.novari.core.shared.model.ResourceCoordinate
import no.novari.core.shared.relation.StoredRelation
import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.config.TopicConfig
import org.awaitility.kotlin.await
import org.junit.jupiter.api.Test
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.data.mongodb.core.MongoTemplate
import org.springframework.data.mongodb.core.query.Criteria.where
import org.springframework.data.mongodb.core.query.Query
import org.springframework.data.mongodb.core.query.Query.query
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
class BufferIT(
    @Autowired private val writer: BufferWriter,
    @Autowired private val mongoTemplate: MongoTemplate,
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

        val resourceCoordinate =
            ResourceCoordinate(
                "fintlabs.no",
                "utdanning",
                "elev",
                "elev",
            )
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
                    "beta.felleskomponent.no/utdanning/elev",
                    1782300748715L,
                ),
                listOf(resource),
                SyncType.FULL,
            )
        writer.sendSyncEntity(sync, resource, resourceCoordinate)
        verify(reader, timeout(5000).times(1))
            .readMessage(any())
    }

    @Test
    fun `creates relation edge when buffered resource has links`() {
        mongoTemplate.remove(Query(), StoredRelation::class.java)

        val resource =
            SyncPageEntry.of(
                "123",
                mapOf(
                    "systemid" to mapOf("identifikatorverdi" to "123"),
                    "_links" to
                        mapOf(
                            "person" to
                                listOf(
                                    mapOf(
                                        "href" to
                                            "https://api.felleskomponent.no/utdanning/elev/person/fodselsnummer/01010112345",
                                    ),
                                ),
                        ),
                ),
            )

        val resourceCoordinate =
            ResourceCoordinate(
                "fintlabs.no",
                "utdanning",
                "elev",
                "elev",
            )

        val sync =
            SyncPage(
                SyncPageMetadata(
                    "test",
                    "corr-id-edge-test",
                    "fintlabs-no",
                    1L,
                    1L,
                    1L,
                    1L,
                    "beta.felleskomponent.no/utdanning/elev",
                    1782300748715L,
                ),
                listOf(resource),
                SyncType.FULL,
            )

        writer.sendSyncEntity(sync, resource, resourceCoordinate).get()

        await.atMost(Duration.ofSeconds(5)).untilAsserted {
            val edge =
                mongoTemplate.findOne(
                    query(
                        where("source.coordinate.orgId")
                            .`is`("fintlabs.no")
                            .and("source.coordinate.domainName")
                            .`is`("utdanning")
                            .and("source.coordinate.packageName")
                            .`is`("elev")
                            .and("source.coordinate.resourceName")
                            .`is`("elev")
                            .and("source.identifier.field")
                            .`is`("systemid")
                            .and("source.identifier.value")
                            .`is`("123")
                            .and("source.relationName")
                            .`is`("person")
                            .and("target.coordinate.orgId")
                            .`is`("fintlabs.no")
                            .and("target.coordinate.domainName")
                            .`is`("utdanning")
                            .and("target.coordinate.packageName")
                            .`is`("elev")
                            .and("target.coordinate.resourceName")
                            .`is`("person")
                            .and("target.identifier.field")
                            .`is`("fodselsnummer")
                            .and("target.identifier.value")
                            .`is`("01010112345"),
                    ),
                    StoredRelation::class.java,
                )

            assertNotNull(edge)
        }
    }

    fun createElev(): Map<String, Any> = mapOf("systemId" to mapOf("identifikatorverdi" to "123"))
}
