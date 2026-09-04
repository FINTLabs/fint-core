package no.fintlabs.provider.kafka.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.provider.config.AdapterKafkaProperties
import no.fintlabs.provider.config.ProviderProperties
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
        sut =
            EventTopicEnsurer(
                adapterKafkaProperties,
                kafkaTopicService,
                ProviderProperties(orgIdValue = "fintlabs.no", baseUrl = ""),
            )
    }

    @Test
    fun `ensureEventTopics creates adapter and provider-error event topics`() {
        sut.ensureEventTopics()

        verify(exactly = 6) { kafkaTopicService.createOrModifyEventTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureEventTopics creates topic for each expected event name`() {
        sut.ensureEventTopics()

        listOf(
            TopicNamesConstants.HEARTBEAT_EVENT_NAME,
            TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME,
            TopicNamesConstants.ADAPTER_FULL_SYNC_EVENT_NAME,
            TopicNamesConstants.ADAPTER_DELTA_SYNC_EVENT_NAME,
            TopicNamesConstants.ADAPTER_DELETE_SYNC_EVENT_NAME,
            TopicNamesConstants.PROVIDER_ERROR_EVENT_NAME,
        ).forEach { eventName ->
            verify(exactly = 1) {
                kafkaTopicService.createOrModifyEventTopic(
                    "fintlabs-no.fint-core.event.$eventName",
                    any(),
                    any(),
                )
            }
        }
    }
}
