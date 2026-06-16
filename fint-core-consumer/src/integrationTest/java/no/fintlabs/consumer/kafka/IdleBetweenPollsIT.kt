package no.fintlabs.consumer.kafka

import no.fintlabs.Application
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.producing.ParameterizedProducerRecord
import no.novari.kafka.producing.ParameterizedTemplateFactory
import no.novari.kafka.topic.name.EntityTopicNameParameters
import no.novari.kafka.topic.name.TopicNamePrefixParameters
import org.awaitility.kotlin.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
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
    private lateinit var timingContainer: ConcurrentMessageListenerContainer<String, Any>

    @Autowired
    private lateinit var timingProbe: TimingProbe

    @Autowired
    private lateinit var parameterizedTemplateFactory: ParameterizedTemplateFactory

    @Autowired
    private lateinit var consumerConfiguration: ConsumerConfiguration

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

        val producer = parameterizedTemplateFactory.createTemplate(Any::class.java)
        repeat(4) { index ->
            producer
                .send(
                    ParameterizedProducerRecord
                        .builder<Any>()
                        .key("debug-${UUID.randomUUID()}")
                        .topicNameParameters(debugTopic())
                        .value(mapOf("index" to index))
                        .build(),
                ).get()
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

    private fun debugTopic() =
        EntityTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgId(consumerConfiguration.orgId.asTopicSegment)
                    .domainContextApplicationDefault()
                    .build(),
            ).resourceName("debug-idle-between-polls")
            .build()

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
            parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService,
            errorHandlerFactory: ErrorHandlerFactory,
            consumerConfiguration: ConsumerConfiguration,
            timingProbe: TimingProbe,
        ): ConcurrentMessageListenerContainer<String, Any> {
            val container =
                parameterizedListenerContainerFactoryService
                    .createRecordListenerContainerFactory(
                        Any::class.java,
                        { timingProbe.recordNow() },
                        ListenerConfiguration
                            .stepBuilder()
                            .groupIdApplicationDefaultWithSuffix("-idle-between-polls-it")
                            .maxPollRecords(1)
                            .maxPollIntervalKafkaDefault()
                            .continueFromPreviousOffsetOnAssignment()
                            .build(),
                        errorHandlerFactory.createErrorHandler(
                            KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<Any>(
                                org.slf4j.LoggerFactory.getLogger(IdleBetweenPollsIT::class.java),
                                "idle-between-polls-it",
                            ),
                        ),
                        { listenerContainer ->
                            listenerContainer.concurrency = 1
                            listenerContainer.containerProperties.idleBetweenPolls = 1350L
                            listenerContainer.isAutoStartup = false
                            listenerContainer.applyConsumerFetchSettings(consumerConfiguration.kafka)
                        },
                    ).createContainer(
                        EntityTopicNameParameters
                            .builder()
                            .topicNamePrefixParameters(
                                TopicNamePrefixParameters
                                    .stepBuilder()
                                    .orgId(consumerConfiguration.orgId.asTopicSegment)
                                    .domainContextApplicationDefault()
                                    .build(),
                            ).resourceName("debug-idle-between-polls")
                            .build(),
                    )

            container.isAutoStartup = false
            return container
        }
    }
}
