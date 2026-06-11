package no.novari.fint.core.provider.datasync.ingest

import no.fintlabs.adapter.models.sync.SyncType
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.core.shared.cache.CacheService
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1)
@Import(TestcontainersConfiguration::class)
class SyncIngestErrorHandlingIntegrationTest
    @Autowired
    constructor(
        @Qualifier("syncIngestKafkaTemplate") private val kafkaTemplate: KafkaTemplate<String, SyncIngestRecord>,
        private val topics: SyncIngestTopics,
        private val cacheService: CacheService,
        private val embeddedKafka: EmbeddedKafkaBroker,
    ) {
        private val resourceKey = "utdanning_elev_elev"

        @Test
        fun `a poison record is dead-lettered while the rest of the batch is written`() {
            val time = System.currentTimeMillis()
            val cache = cacheService.getCache(resourceKey)

            send(record("ing-p1", time, validResource("ing-p1")))
            send(record("ing-p2", time, mapOf("systemId" to mapOf("identifikatorverdi" to "ing-p2"), "gjest" to mapOf("not" to "a-boolean"))))
            send(record("ing-p3", time, validResource("ing-p3")))

            awaitUntil { cache.get("ing-p1") != null && cache.get("ing-p3") != null }
            assertNull(cache.get("ing-p2"), "the unconvertible record must not reach the cache")

            val dltRecords = consumeDlt()
            assertTrue(
                dltRecords.any { it.value().contains("\"identifier\":\"ing-p2\"") },
                "the unconvertible record must land on the DLT",
            )
        }

        @Test
        fun `duplicate delivery of the same record is idempotent`() {
            val time = System.currentTimeMillis()
            val cache = cacheService.getCache(resourceKey)

            send(record("ing-d1", time, validResource("ing-d1")))
            send(record("ing-d1", time, validResource("ing-d1")))
            send(record("ing-d2", time, validResource("ing-d2")))

            awaitUntil { cache.get("ing-d1") != null && cache.get("ing-d2") != null }
        }

        private fun send(record: SyncIngestRecord) {
            kafkaTemplate.send(topics.topic, "${record.resourceKey}:${record.identifier}", record).get()
        }

        private fun record(
            identifier: String,
            time: Long,
            resource: Any?,
        ): SyncIngestRecord =
            SyncIngestRecord(
                resourceKey = resourceKey,
                identifier = identifier,
                orgId = "test.org.no",
                corrId = UUID.randomUUID().toString(),
                syncType = SyncType.DELTA,
                totalSize = 1,
                time = time,
                resource = resource,
            )

        private fun validResource(id: String): Map<String, Any> = mapOf("systemId" to mapOf("identifikatorverdi" to id))

        private fun consumeDlt(): List<org.apache.kafka.clients.consumer.ConsumerRecord<String, String>> {
            val props = KafkaTestUtils.consumerProps("sync-ingest-dlt-test", "true", embeddedKafka)
            props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            DefaultKafkaConsumerFactory(props, StringDeserializer(), StringDeserializer()).createConsumer().use { consumer ->
                consumer.subscribe(listOf(topics.dlt))
                return KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(10)).records(topics.dlt).toList()
            }
        }

        private fun awaitUntil(
            timeoutMs: Long = 15_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            throw AssertionError("condition not met within ${timeoutMs}ms")
        }
    }
