package no.fintlabs.adapter.gateway.kafka.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.gateway.config.AdapterKafkaProperties
import no.fintlabs.adapter.gateway.config.ProviderProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EventTopicEnsurerTest {
    private lateinit var kafkaTopicService: KafkaTopicService
    private val adapterKafkaProperties = AdapterKafkaProperties()
    private lateinit var sut: EventTopicEnsurer

    @BeforeEach
    fun setup() {
        kafkaTopicService = mockk()
        every { kafkaTopicService.createOrModifyEventTopic(any(), any(), any()) } just Runs
        every { kafkaTopicService.createOrModifyCompactedTopic(any(), any()) } just Runs
        sut =
            EventTopicEnsurer(
                adapterKafkaProperties,
                kafkaTopicService,
            )
    }

    @Test
    fun `ensureEventTopics creates four event topics and one compacted contract topic`() {
        sut.ensureEventTopics()

        verify(exactly = 4) { kafkaTopicService.createOrModifyEventTopic(any(), any(), any()) }
        verify(exactly = 1) {
            kafkaTopicService.createOrModifyCompactedTopic("novari-no.fint-core.${TopicNamesConstants.ADAPTER_CONTRACT}", any())
        }
    }

    @Test
    fun `ensureEventTopics creates topic for each expected event name`() {
        sut.ensureEventTopics()

        listOf(
            TopicNamesConstants.ADAPTER_HEARTBEAT,
            TopicNamesConstants.ADAPTER_FULL_SYNC,
            TopicNamesConstants.ADAPTER_DELTA_SYNC,
            TopicNamesConstants.ADAPTER_DELETE_SYNC,
        ).forEach { eventName ->
            verify(exactly = 1) {
                kafkaTopicService.createOrModifyEventTopic(
                    "novari-no.fint-core.$eventName",
                    any(),
                    any(),
                )
            }
        }
    }
}
