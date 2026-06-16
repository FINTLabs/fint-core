package no.fintlabs.consumer.kafka.entity

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.autorelation.AutoRelationService
import no.fintlabs.autorelation.kafka.RelationUpdateConsumer
import no.fintlabs.autorelation.model.RelationUpdate
import no.fintlabs.autorelation.model.createEntityDescriptor
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.KafkaConfiguration
import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.kafka.KafkaThroughputMetrics
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactory
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.TopicNamePatternParameters
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.TopicPartition
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.ConsumerSeekAware
import java.util.UUID
import java.util.function.Consumer
import kotlin.test.assertTrue

class RelationUpdateConsumerTest {
    private lateinit var autoRelationService: AutoRelationService
    private lateinit var consumerConfig: ConsumerConfiguration
    private lateinit var relationUpdateConsumer: RelationUpdateConsumer
    private lateinit var consumerRecord: ConsumerRecord<String?, RelationUpdate>
    private lateinit var relationUpdate: RelationUpdate
    private lateinit var kafkaThroughputMetrics: KafkaThroughputMetrics

    @BeforeEach
    fun setUp() {
        autoRelationService = mockk(relaxed = true)
        consumerConfig = mockk()
        relationUpdate = mockk(relaxed = true)
        consumerRecord =
            mockk {
                every { value() } returns relationUpdate
            }

        kafkaThroughputMetrics = mockk(relaxed = true)
        relationUpdateConsumer = RelationUpdateConsumer(autoRelationService, consumerConfig, kafkaThroughputMetrics)
    }

    @Test
    fun `listener configuration seeks to beginning on partition assignment`() {
        val config = captureListenerConfig()
        val callback = mockk<ConsumerSeekAware.ConsumerSeekCallback>(relaxed = true)
        val partition = TopicPartition("test-topic", 0)

        assertTrue(config.onPartitionsAssigned.isPresent)
        config.onPartitionsAssigned.get().accept(mapOf(partition to 0L), callback)

        verify { callback.seekToBeginning(setOf(partition)) }
    }

    private fun captureListenerConfig(): ListenerConfiguration {
        val factoryService = mockk<ParameterizedListenerContainerFactoryService>()
        val errorHandlerFactory = mockk<ErrorHandlerFactory>(relaxed = true)
        val factory = mockk<ParameterizedListenerContainerFactory<RelationUpdate>>()
        val container = mockk<ConcurrentMessageListenerContainer<String, RelationUpdate>>(relaxed = true)

        every { consumerConfig.orgId } returns OrgId.from("foo.bar")
        every { consumerConfig.domain } returns "utdanning"
        every { consumerConfig.packageName } returns "vurdering"
        every { consumerConfig.kafka } returns KafkaConfiguration()

        val slot = slot<ListenerConfiguration>()
        every {
            factoryService.createRecordListenerContainerFactory(
                any<Class<RelationUpdate>>(),
                any<Consumer<ConsumerRecord<String, RelationUpdate>>>(),
                capture(slot),
                any(),
                any(),
            )
        } returns factory
        every { factory.createContainer(any<TopicNamePatternParameters>()) } returns container

        relationUpdateConsumer.relationUpdateConsumerContainer(factoryService, errorHandlerFactory)
        return slot.captured
    }

    @Test
    fun `process if consumerConfiguration matches`() {
        val domain = "testdomain"
        val pkg = "pkgtest"

        every { relationUpdate.targetEntity } returns createEntityDescriptor(domain, pkg, "resource")

        every { consumerConfig.matchesComponent(domain, pkg) } returns true

        relationUpdateConsumer.consumeRecord(consumerRecord)

        verify(exactly = 1) { autoRelationService.process(any()) }
    }

    @Test
    fun `records consumer metric when processing completes`() {
        every { relationUpdate.targetEntity } returns createEntityDescriptor("d", "p", "resource")

        relationUpdateConsumer.consumeRecord(consumerRecord)

        verify(exactly = 1) {
            kafkaThroughputMetrics.recordRelationUpdateConsumer("resource", any())
        }
    }
}
