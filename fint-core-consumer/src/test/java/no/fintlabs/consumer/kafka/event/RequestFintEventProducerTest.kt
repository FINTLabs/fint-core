package no.fintlabs.consumer.kafka.event

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import no.fintlabs.adapter.models.event.RequestFintEvent
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.util.concurrent.CompletableFuture

class RequestFintEventProducerTest {
    private val kafkaTemplate: KafkaTemplate<String, RequestFintEvent> = mockk()

    private val producer = RequestFintEventProducer(kafkaTemplate)

    @Test
    fun `publishes every event on the shared request topic`() {
        every { kafkaTemplate.send(any<String>(), any(), any()) } returns
            CompletableFuture.completedFuture(mockk<SendResult<String, RequestFintEvent>>())

        val event =
            RequestFintEvent().apply {
                corrId = "corr-1"
                orgId = "test.novari.no"
            }

        producer.publish(event)

        verify {
            kafkaTemplate.send("fintlabs.fint-core.fint-felleskomponent-event-request", "corr-1", event)
        }
    }
}
