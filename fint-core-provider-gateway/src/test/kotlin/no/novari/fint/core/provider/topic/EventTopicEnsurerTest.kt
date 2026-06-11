package no.novari.fint.core.provider.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.novari.fint.core.provider.config.AdapterKafkaProperties
import no.novari.fint.core.provider.kafka.EventTopicNames
import no.novari.fint.core.provider.kafka.topic.EventTopicEnsurer
import no.novari.fint.core.provider.kafka.topic.TopicNamesConstants
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin

class EventTopicEnsurerTest {

    private lateinit var kafkaAdmin: KafkaAdmin
    private val adapterKafkaProperties = AdapterKafkaProperties()
    private val eventTopicNames = EventTopicNames("fintlabs-no", "fint-core")
    private lateinit var sut: EventTopicEnsurer

    @BeforeEach
    fun setup() {
        kafkaAdmin = mockk()
        every { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) } just Runs
        sut = EventTopicEnsurer(adapterKafkaProperties, eventTopicNames, kafkaAdmin, 1)
    }

    @Test
    fun `ensureEventTopics creates all five adapter event topics`() {
        sut.ensureEventTopics()

        verify(exactly = 5) { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) }
    }

    @Test
    fun `ensureEventTopics creates topic for each expected event name`() {
        sut.ensureEventTopics()

        listOf(
            TopicNamesConstants.HEARTBEAT_EVENT_NAME,
            TopicNamesConstants.ADAPTER_REGISTER_EVENT_NAME,
            TopicNamesConstants.ADAPTER_FULL_SYNC_EVENT_NAME,
            TopicNamesConstants.ADAPTER_DELTA_SYNC_EVENT_NAME,
            TopicNamesConstants.ADAPTER_DELETE_SYNC_EVENT_NAME
        ).forEach { eventName ->
            verify(exactly = 1) {
                kafkaAdmin.createOrModifyTopics(
                    match<NewTopic> { it.name() == "fintlabs-no.fint-core.event.$eventName" }
                )
            }
        }
    }
}
