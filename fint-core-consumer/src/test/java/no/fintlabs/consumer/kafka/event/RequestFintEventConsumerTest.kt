package no.fintlabs.consumer.kafka.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import no.fintlabs.adapter.models.event.RequestFintEvent
import no.fintlabs.consumer.config.ConsumerConfiguration
import no.fintlabs.consumer.config.KafkaConfiguration
import no.fintlabs.consumer.config.OrgId
import no.fintlabs.consumer.resource.event.EventStatusCache
import no.novari.kafka.consuming.ErrorHandlerFactory
import no.novari.kafka.consuming.ListenerConfiguration
import no.novari.kafka.consuming.ParameterizedListenerContainerFactory
import no.novari.kafka.consuming.ParameterizedListenerContainerFactoryService
import no.novari.kafka.topic.name.TopicNameParameters
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

class RequestFintEventConsumerTest {
    private lateinit var consumerConfig: ConsumerConfiguration
    private lateinit var eventStatusCache: EventStatusCache
    private lateinit var factoryService: ParameterizedListenerContainerFactoryService
    private lateinit var errorHandlerFactory: ErrorHandlerFactory
    private lateinit var factory: ParameterizedListenerContainerFactory<RequestFintEvent>
    private lateinit var container: ConcurrentMessageListenerContainer<String, RequestFintEvent>
    private lateinit var requestFintEventConsumer: RequestFintEventConsumer

    @BeforeEach
    fun setUp() {
        consumerConfig = mockk()
        eventStatusCache = mockk(relaxed = true)
        factoryService = mockk()
        errorHandlerFactory = mockk(relaxed = true)
        factory = mockk()
        container = mockk(relaxed = true)

        every { consumerConfig.orgId } returns OrgId.from("foo.bar")
        every { consumerConfig.domain } returns "utdanning"
        every { consumerConfig.packageName } returns "vurdering"
        every { consumerConfig.kafka } returns KafkaConfiguration()

        every {
            factoryService.createRecordListenerContainerFactory(
                any<Class<RequestFintEvent>>(),
                any<Consumer<ConsumerRecord<String, RequestFintEvent>>>(),
                any(),
                any(),
                any(),
            )
        } returns factory
        every { factory.createContainer(any<TopicNameParameters>()) } returns container

        requestFintEventConsumer = RequestFintEventConsumer(consumerConfig, eventStatusCache)
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
        val slot = slot<ListenerConfiguration>()
        every {
            factoryService.createRecordListenerContainerFactory(
                any<Class<RequestFintEvent>>(),
                any<Consumer<ConsumerRecord<String, RequestFintEvent>>>(),
                capture(slot),
                any(),
                any(),
            )
        } returns factory
        requestFintEventConsumer.requestFintEventRequestListenerContainer(factoryService, errorHandlerFactory)
        return slot.captured
    }
}
