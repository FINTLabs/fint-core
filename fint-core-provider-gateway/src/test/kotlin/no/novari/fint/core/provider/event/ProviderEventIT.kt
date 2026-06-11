package no.novari.fint.core.provider.event

import no.fintlabs.adapter.models.event.RequestFintEvent
import no.novari.fint.core.provider.TestcontainersConfiguration
import no.novari.fint.core.provider.event.request.RequestEventService
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.StringDeserializer
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.test.EmbeddedKafkaBroker
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.KafkaTestUtils
import java.time.Duration
import java.util.UUID

/**
 * Integration coverage for the provider's auto-fail-on-expiry path: when a request reaches the
 * RequestCache already past its time-to-live, Caffeine's expiry callback must publish a failed
 * "Event expired" ResponseFintEvent so the waiting client is not left hanging. The request is fed
 * straight to RequestEventService (the inbound RequestFintEventConsumer subscribes off the
 * metamodel service, which is not populated in the test harness).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@EmbeddedKafka(partitions = 1, topics = [ProviderEventIT.RESPONSE_TOPIC])
@Import(TestcontainersConfiguration::class)
class ProviderEventIT
    @Autowired
    constructor(
        private val requestEventService: RequestEventService,
        private val broker: EmbeddedKafkaBroker,
    ) {
        @Test
        fun `a request that arrives already expired produces a failed event-expired response`() {
            consumer().use { consumer ->
                consumer.subscribe(listOf(RESPONSE_TOPIC))
                val corrId = UUID.randomUUID().toString()

                requestEventService.addEvent(expiredRequest(corrId))

                var failed = false
                awaitUntil("no failed 'Event expired' response was produced") {
                    consumer.poll(Duration.ofMillis(500)).forEach { record ->
                        if (record.value().contains(corrId) && record.value().contains("Event expired")) failed = true
                    }
                    failed
                }
            }
        }

        private fun expiredRequest(corrId: String): RequestFintEvent =
            RequestFintEvent().apply {
                this.corrId = corrId
                orgId = ORG
                domainName = "utdanning"
                packageName = "elev"
                resourceName = "elev"
                created = System.currentTimeMillis()
                timeToLive = System.currentTimeMillis() - 1_000
            }

        private fun consumer(): KafkaConsumer<String, String> {
            val props = KafkaTestUtils.consumerProps("provider-event-it-${UUID.randomUUID()}", "false", broker)
            props[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            props[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] = StringDeserializer::class.java
            props[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
            return KafkaConsumer(props)
        }

        private fun awaitUntil(
            message: String,
            timeoutMs: Long = 30_000,
            condition: () -> Boolean,
        ) {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (condition()) return
                Thread.sleep(50)
            }
            throw AssertionError("$message within ${timeoutMs}ms")
        }

        companion object {
            const val ORG = "fintlabs-no"
            const val RESPONSE_TOPIC = "fintlabs-no.fint-core.event.utdanning-elev-response"
        }
    }
