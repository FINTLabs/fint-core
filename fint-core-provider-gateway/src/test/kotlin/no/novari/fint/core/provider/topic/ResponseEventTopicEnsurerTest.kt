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
import no.novari.fint.core.provider.kafka.topic.ResponseEventTopicEnsurer
import org.apache.kafka.clients.admin.NewTopic
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaAdmin

class ResponseEventTopicEnsurerTest {

    private lateinit var kafkaAdmin: KafkaAdmin
    private val responseProducerProperties = ProducerProperties()
    private val eventTopicNames = EventTopicNames("fintlabs-no", "fint-core")

    @BeforeEach
    fun setup() {
        kafkaAdmin = mockk()
        every { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) } just Runs
    }

    private fun sut(components: List<ComponentConfig> = emptyList()) =
        ResponseEventTopicEnsurer(
            responseProducerProperties,
            ProviderProperties(components = components),
            eventTopicNames,
            kafkaAdmin,
            1
        )

    @Test
    fun `ensureResponseEventTopics creates a topic for each org-id and component combination`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no", "rogfk-no")),
            ComponentConfig(domainName = "utdanning", "vurdering", listOf("fintlabs-no"))
        )

        sut(components).ensureResponseEventTopics()

        verify(exactly = 3) { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) }
    }

    @Test
    fun `ensureResponseEventTopics uses correct event name with response suffix`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"))
        )

        sut(components).ensureResponseEventTopics()

        verify(exactly = 1) {
            kafkaAdmin.createOrModifyTopics(
                match<NewTopic> { it.name() == "fintlabs-no.fint-core.event.utdanning-elev-response" }
            )
        }
    }

    @Test
    fun `ensureResponseEventTopics does nothing when components list is empty`() {
        sut(emptyList()).ensureResponseEventTopics()

        verify(exactly = 0) { kafkaAdmin.createOrModifyTopics(any<NewTopic>()) }
    }

    @Test
    fun `ensureResponseEventTopics uses component responsePartitions override when set`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"), responsePartitions = 3)
        )
        val topicSlot = slot<NewTopic>()
        every { kafkaAdmin.createOrModifyTopics(capture(topicSlot)) } just Runs

        sut(components).ensureResponseEventTopics()

        assertEquals(3, topicSlot.captured.numPartitions())
    }

    @Test
    fun `ensureResponseEventTopics falls back to global default when responsePartitions is unset`() {
        val components = listOf(
            ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"))
        )
        val topicSlot = slot<NewTopic>()
        every { kafkaAdmin.createOrModifyTopics(capture(topicSlot)) } just Runs

        sut(components).ensureResponseEventTopics()

        assertEquals(responseProducerProperties.partitions, topicSlot.captured.numPartitions())
    }
}
