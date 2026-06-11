package no.novari.fint.core.provider.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.novari.fint.core.provider.config.ComponentConfig
import no.novari.fint.core.provider.config.ProducerProperties
import no.novari.fint.core.provider.config.ProviderProperties
import no.novari.fint.core.provider.kafka.EventTopicNames
import no.novari.fint.core.provider.kafka.topic.RequestEventTopicEnsurer
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin

class RequestEventTopicEnsurerTest {

    private lateinit var kafkaAdmin: KafkaAdmin
    private val requestProducerProperties = ProducerProperties()
    private val eventTopicNames = EventTopicNames("fintlabs-no", "fint-core")

    @BeforeEach
    fun setup() {
        kafkaAdmin = mockk()
        every { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) } just Runs
    }

    private fun sut(components: List<ComponentConfig> = emptyList()) =
        RequestEventTopicEnsurer(
            requestProducerProperties,
            ProviderProperties(components = components),
            eventTopicNames,
            kafkaAdmin,
            1
        )

    @Test
    fun `ensureRequestEventTopics creates a topic for each org-id and component combination`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no", "rogfk-no")),
            ComponentConfig(domainName = "utdanning", "vurdering", listOf("fintlabs-no"))
        )

        sut(components).ensureRequestEventTopics()

        verify(exactly = 3) { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) }
    }

    @Test
    fun `ensureRequestEventTopics uses correct event name with request suffix`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"))
        )

        sut(components).ensureRequestEventTopics()

        verify(exactly = 1) {
            kafkaAdmin.createOrModifyTopics(
                match<NewTopic> { it.name() == "fintlabs-no.fint-core.event.utdanning-elev-request" }
            )
        }
    }

    @Test
    fun `ensureRequestEventTopics does nothing when components list is empty`() {
        sut(emptyList()).ensureRequestEventTopics()

        verify(exactly = 0) { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) }
    }

    @Test
    fun `ensureRequestEventTopics uses component requestPartitions override when set`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"), requestPartitions = 5)
        )
        val topicSlot = slot<NewTopic>()
        every { kafkaAdmin.createOrModifyTopics(capture(topicSlot)) } just Runs

        sut(components).ensureRequestEventTopics()

        assertEquals(5, topicSlot.captured.numPartitions())
    }

    @Test
    fun `ensureRequestEventTopics falls back to global default when requestPartitions is unset`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"))
        )
        val topicSlot = slot<NewTopic>()
        every { kafkaAdmin.createOrModifyTopics(capture(topicSlot)) } just Runs

        sut(components).ensureRequestEventTopics()

        assertEquals(requestProducerProperties.partitions, topicSlot.captured.numPartitions())
    }
}
