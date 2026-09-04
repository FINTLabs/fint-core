package no.fintlabs.provider.kafka.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.provider.config.ComponentConfig
import no.fintlabs.provider.config.EntityKafkaProperties
import no.fintlabs.provider.config.ProviderProperties
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class EntityTopicEnsurerTest {
    private lateinit var kafkaTopicService: KafkaTopicService
    private val entityKafkaProperties = EntityKafkaProperties()

    @BeforeEach
    fun setup() {
        kafkaTopicService = mockk()
        every { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) } just Runs
    }

    private fun sut(components: List<ComponentConfig> = emptyList()) =
        EntityTopicEnsurer(
            kafkaTopicService,
            entityKafkaProperties,
            ProviderProperties(orgIdValue = "fintlabs.no", components = components, baseUrl = ""),
        )

    @Test
    fun `ensureEntityTopics creates a topic for each org-id and component combination`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no", "rogfk-no")),
                ComponentConfig(domainName = "utdanning", "vurdering", listOf("fintlabs-no")),
            )

        sut(components).ensureEntityTopics()

        verify(exactly = 3) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureEntityTopics uses resourceName combining domain and packageName`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no")),
            )

        sut(components).ensureEntityTopics()

        verify(exactly = 1) {
            kafkaTopicService.createOrModifyEntityTopic(
                "fintlabs-no.fint-core.entity.utdanning-elev",
                entityKafkaProperties.partitions,
                entityKafkaProperties.retentionTime,
            )
        }
    }

    @Test
    fun `ensureEntityTopics does nothing when components list is empty`() {
        sut(emptyList()).ensureEntityTopics()

        verify(exactly = 0) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureEntityTopics does nothing when component has no org-ids`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "elev", emptyList()),
            )

        sut(components).ensureEntityTopics()

        verify(exactly = 0) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }
}
