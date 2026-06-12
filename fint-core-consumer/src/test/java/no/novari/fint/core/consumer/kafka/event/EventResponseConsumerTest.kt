package no.novari.fint.core.consumer.kafka.event

import io.mockk.mockk
import no.fintlabs.adapter.models.event.ResponseFintEvent
import no.novari.fint.core.consumer.config.ConsumerConfiguration
import no.novari.fint.core.shared.event.EventStatusStore
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.kafka.KafkaProperties
import org.springframework.kafka.core.ConsumerFactory
import kotlin.test.assertEquals

/**
 * Pins the literal group id derivation: it must stay byte-identical to what the fint-kafka library
 * produced (`spring.kafka.consumer.group-id` + `-event`) or the listener loses its committed
 * offsets on deploy and reprocesses or skips events.
 */
class EventResponseConsumerTest {
    @Test
    fun `event listener keeps the derived group id so committed offsets carry over`() {
        val kafkaProperties = KafkaProperties()
        kafkaProperties.consumer.groupId = "fint-core-consumer-foo.org-mongodb"
        val consumerConfig =
            ConsumerConfiguration(baseUrl = "https://test.no", orgIdValue = "foo.org", podUrl = "http://pod")

        val container =
            EventResponseConsumer(consumerConfig, mockk<EventStatusStore>(), "fint-core")
                .responseFintEventContainerListener(
                    mockk<ConsumerFactory<String, ResponseFintEvent>>(relaxed = true),
                    kafkaProperties,
                )

        assertEquals("fint-core-consumer-foo.org-mongodb-event", container.containerProperties.groupId)
        assertEquals(
            "^\\Qfoo-org\\E\\.\\Qfint-core\\E\\.event\\..*-response$",
            container.containerProperties.topicPattern?.pattern(),
        )
    }
}
