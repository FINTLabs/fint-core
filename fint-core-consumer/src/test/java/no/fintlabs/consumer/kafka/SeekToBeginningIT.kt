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
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.kafka.test.utils.ContainerTestUtils
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.TestPropertySource
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals

/**
 * Proves that each pod gets a unique group ID and reads all historical messages from the beginning,
 * regardless of what other pods have already consumed.
 *
 * The positive test shows that two consumers with different UUID-based group IDs both independently
 * read all messages from offset 0.
 *
 * The negative test shows the problem that would occur without unique group IDs: a second consumer
 * reusing the same group ID skips messages already committed by the first consumer, and would
 * therefore start with an incomplete in-memory cache.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = [Application::class])
@EmbeddedKafka(partitions = 1)
@TestPropertySource(
    properties = [
        "spring.kafka.bootstrap-servers=\${spring.embedded.kafka.brokers}",
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=seek-to-beginning-it",
        "novari.kafka.default-replicas=1",
        "fint.relation.base-url=https://test.felleskomponent.no",
        "fint.org-id=foo.org",
        "fint.consumer.org-id=foo.org",
        "fint.consumer.domain=utdanning",
        "fint.consumer.package=elev",
        "fint.security.enabled=false",
    ],
)
@Import(KafkaTestJacksonConfig::class)
@DirtiesContext
class SeekToBeginningIT {
    companion object {
        private val logger = LoggerFactory.getLogger(SeekToBeginningIT::class.java)
        private const val MESSAGE_COUNT = 10
    }

    @Autowired
    private lateinit var parameterizedListenerContainerFactoryService: ParameterizedListenerContainerFactoryService

    @Autowired
    private lateinit var errorHandlerFactory: ErrorHandlerFactory

    @Autowired
    private lateinit var parameterizedTemplateFactory: ParameterizedTemplateFactory

    @Autowired
    private lateinit var consumerConfiguration: ConsumerConfiguration

    private val activeContainers = mutableListOf<ConcurrentMessageListenerContainer<String, Any>>()

    @AfterEach
    fun tearDown() {
        activeContainers.forEach { it.stop() }
        activeContainers.clear()
    }

    @Test
    fun `each pod with unique group id independently reads all historical messages from the beginning`() {
        publishMessages("seek-positive", MESSAGE_COUNT)

        // Pod 1: unique group id + seekToBeginning → reads all messages from offset 0
        val pod1Count = AtomicInteger(0)
        val pod1 = createContainer("seek-positive", uniqueGroup = true) { pod1Count.incrementAndGet() }
        pod1.start()
        ContainerTestUtils.waitForAssignment(pod1, 1)

        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(MESSAGE_COUNT, pod1Count.get(), "Pod 1 should read all historical messages")
        }
        pod1.stop()

        // Pod 2: different UUID group → also reads ALL messages from offset 0, not from where pod 1 left off
        val pod2Count = AtomicInteger(0)
        val pod2 = createContainer("seek-positive", uniqueGroup = true) { pod2Count.incrementAndGet() }
        pod2.start()
        ContainerTestUtils.waitForAssignment(pod2, 1)

        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(MESSAGE_COUNT, pod2Count.get(), "Pod 2 should also read all historical messages independently")
        }
        pod2.stop()
    }

    @Test
    fun `negative - pod reusing a shared group id skips messages already committed by a previous pod`() {
        publishMessages("seek-negative", MESSAGE_COUNT)

        // First consumer with a FIXED group id reads and commits all messages
        val firstCount = AtomicInteger(0)
        val first = createContainer("seek-negative", uniqueGroup = false) { firstCount.incrementAndGet() }
        first.start()
        ContainerTestUtils.waitForAssignment(first, 1)

        await.atMost(Duration.ofSeconds(30)).untilAsserted {
            assertEquals(MESSAGE_COUNT, firstCount.get())
        }
        first.stop() // graceful stop commits offsets

        // Second consumer with the SAME group id: offsets are already committed, so it sees no messages
        val secondCount = AtomicInteger(0)
        val second = createContainer("seek-negative", uniqueGroup = false) { secondCount.incrementAndGet() }
        second.start()
        ContainerTestUtils.waitForAssignment(second, 1)

        Thread.sleep(2_000)

        assertEquals(
            0,
            secondCount.get(),
            "A pod reusing the same group id should not re-read committed messages — " +
                "it would start with an incomplete cache",
        )
        second.stop()
    }

    private fun publishMessages(
        topicSuffix: String,
        count: Int,
    ) {
        val producer = parameterizedTemplateFactory.createTemplate(Any::class.java)
        repeat(count) { index ->
            producer
                .send(
                    ParameterizedProducerRecord
                        .builder<Any>()
                        .key("msg-$index")
                        .topicNameParameters(topicNameParameters(topicSuffix))
                        .value(mapOf("index" to index))
                        .build(),
                ).get()
        }
    }

    private fun createContainer(
        topicSuffix: String,
        uniqueGroup: Boolean,
        onRecord: () -> Unit,
    ): ConcurrentMessageListenerContainer<String, Any> {
        val listenerConfig =
            ListenerConfiguration
                .stepBuilder()
                .let {
                    if (uniqueGroup) {
                        it.groupIdApplicationDefaultWithUniqueSuffix()
                    } else {
                        it.groupIdApplicationDefaultWithSuffix("-shared-$topicSuffix")
                    }
                }.maxPollRecordsKafkaDefault()
                .maxPollIntervalKafkaDefault()
                .let {
                    if (uniqueGroup) {
                        it.seekToBeginningOnAssignment()
                    } else {
                        it.continueFromPreviousOffsetOnAssignment()
                    }
                }.build()

        return parameterizedListenerContainerFactoryService
            .createRecordListenerContainerFactory(
                Any::class.java,
                { onRecord() },
                listenerConfig,
                errorHandlerFactory.createErrorHandler(
                    KafkaConsumerErrorHandling.createLoggingErrorHandlerConfiguration<Any>(logger, topicSuffix),
                ),
                { it.isAutoStartup = false },
            ).createContainer(topicNameParameters(topicSuffix))
            .apply {
                isAutoStartup = false
                activeContainers.add(this)
            }
    }

    private fun topicNameParameters(topicSuffix: String) =
        EntityTopicNameParameters
            .builder()
            .topicNamePrefixParameters(
                TopicNamePrefixParameters
                    .stepBuilder()
                    .orgId(consumerConfiguration.orgId.asTopicSegment)
                    .domainContextApplicationDefault()
                    .build(),
            ).resourceName(topicSuffix)
            .build()
}
