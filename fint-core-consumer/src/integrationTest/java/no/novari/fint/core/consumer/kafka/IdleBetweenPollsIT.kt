package no.novari.fint.core.consumer.kafka

import no.novari.fint.core.consumer.Application
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ContainerProperties
import org.springframework.kafka.listener.MessageListener
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=idle-between-polls-it",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
    ],
)
@Import(IdleBetweenPollsIT.TestConfig::class, KafkaTestJacksonConfig::class)
@DirtiesContext
class IdleBetweenPollsIT {
    @Autowired
    private lateinit var timingContainer: ConcurrentMessageListenerContainer<String, String>

    @Autowired
    private lateinit var timingProbe: TimingProbe

    @Autowired
    private lateinit var kafkaProperties: KafkaProperties

    @AfterEach
    fun tearDown() {
        timingContainer.stop()
        timingProbe.reset()
    }

    @Test
    fun `idleBetweenPolls delays delivery between records across polls`() {
        timingProbe.reset()
        timingContainer.start()
        ContainerTestUtils.waitForAssignment(timingContainer, 1)

        val producer =
            KafkaTemplate(
                DefaultKafkaProducerFactory(
                    HashMap<String, Any>(kafkaProperties.buildProducerProperties(null)),
                    StringSerializer(),
                    StringSerializer(),
                ),
            )
        repeat(4) { index ->
            producer.send(TOPIC, "debug-${UUID.randomUUID()}", "message-$index").get()
        }

        await.atMost(Duration.ofSeconds(20)).untilAsserted {
            assertEquals(4, timingProbe.timestamps.size)
        }

        val timestamps = timingProbe.timestamps.toList()
        val gapsMs =
            timestamps
                .zipWithNext()
                .map { (previous, next) -> next - previous }

        assertEquals(3, gapsMs.size)
        assertTrue(
            gapsMs.all { it >= 1250L },
            "Expected all poll gaps to be at least ~1250ms with idleBetweenPolls=1350ms, but was $gapsMs",
        )
    }

    class TimingProbe {
        val timestamps = CopyOnWriteArrayList<Long>()

        fun recordNow() {
            timestamps.add(System.currentTimeMillis())
        }

        fun reset() {
            timestamps.clear()
        }
    }

    @TestConfiguration
    class TestConfig {
        @Bean
        fun timingProbe() = TimingProbe()

        @Bean
        fun timingContainer(
            kafkaProperties: KafkaProperties,
            timingProbe: TimingProbe,
        ): ConcurrentMessageListenerContainer<String, String> {
            val config = HashMap<String, Any>(kafkaProperties.buildConsumerProperties(null))
            config[ConsumerConfig.MAX_POLL_RECORDS_CONFIG] = 1
            val consumerFactory = DefaultKafkaConsumerFactory(config, StringDeserializer(), StringDeserializer())

            val containerProperties = ContainerProperties(TOPIC)
            containerProperties.groupId = "idle-between-polls-it-timing"
            containerProperties.idleBetweenPolls = 1350L
            containerProperties.messageListener = MessageListener<String, String> { timingProbe.recordNow() }

            val container = ConcurrentMessageListenerContainer(consumerFactory, containerProperties)
            container.concurrency = 1
            container.isAutoStartup = false
            return container
        }
    }

    companion object {
        private const val TOPIC = "foo-org.fint-core.entity.debug-idle-between-polls"
    }
}
