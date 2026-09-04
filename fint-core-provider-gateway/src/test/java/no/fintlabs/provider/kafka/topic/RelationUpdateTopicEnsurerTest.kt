package no.fintlabs.provider.kafka.topic

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.provider.config.ComponentConfig
import no.fintlabs.provider.config.ProviderProperties
import no.fintlabs.provider.config.RelationUpdateKafkaProperties
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RelationUpdateTopicEnsurerTest {
    private lateinit var kafkaTopicService: KafkaTopicService
    private val relationUpdateKafkaProperties = RelationUpdateKafkaProperties()

    @BeforeEach
    fun setup() {
        kafkaTopicService = mockk()
        every { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) } just Runs
    }

    private fun sut(components: List<ComponentConfig> = emptyList()) =
        RelationUpdateTopicEnsurer(
            kafkaTopicService,
            relationUpdateKafkaProperties,
            ProviderProperties(orgIdValue = "fintlabs.no", components = components, baseUrl = ""),
        )

    @Test
    fun `ensureRelationUpdateTopics creates topics only for components with relationUpdate enabled`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no", "rogfk-no"), relationUpdate = true),
                ComponentConfig(domainName = "utdanning", "vurdering", listOf("fintlabs-no"), relationUpdate = true),
                ComponentConfig(domainName = "utdanning", "ot", listOf("fintlabs-no"), relationUpdate = false),
                ComponentConfig(domainName = "utdanning", "larling", listOf("fintlabs-no"), relationUpdate = false),
                ComponentConfig(domainName = "administrasjon", "personal", listOf("fintlabs-no"), relationUpdate = false),
            )

        sut(components).ensureRelationUpdateTopics()

        verify(exactly = 3) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureRelationUpdateTopics uses resourceName with relation-update suffix`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "elev", listOf("fintlabs-no"), relationUpdate = true),
            )

        sut(components).ensureRelationUpdateTopics()

        verify(exactly = 1) {
            kafkaTopicService.createOrModifyEntityTopic(
                "fintlabs-no.fint-core.entity.utdanning-elev-relation-update",
                relationUpdateKafkaProperties.partitions,
                relationUpdateKafkaProperties.retentionTime,
            )
        }
    }

    @Test
    fun `ensureRelationUpdateTopics skips components with relationUpdate disabled`() {
        val components =
            listOf(
                ComponentConfig(domainName = "utdanning", "ot", listOf("fintlabs-no"), relationUpdate = false),
                ComponentConfig(domainName = "administrasjon", "personal", listOf("fintlabs-no"), relationUpdate = false),
            )

        sut(components).ensureRelationUpdateTopics()

        verify(exactly = 0) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureRelationUpdateTopics does nothing when components list is empty`() {
        sut(emptyList()).ensureRelationUpdateTopics()

        verify(exactly = 0) { kafkaTopicService.createOrModifyEntityTopic(any(), any(), any()) }
    }

    @Test
    fun `ensureRelationUpdateTopics defaults to 6 partitions`() {
        assertEquals(6, relationUpdateKafkaProperties.partitions)
    }
}
